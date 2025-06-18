/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package asset.pipeline

import java.util.concurrent.ConcurrentHashMap
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * A Cache Manager for the Asset-Pipeline runtime. This reduces repeat processing
 * of files that have already been processed during the runtime of the asset-pipeline.
 * It also is capable of persisting this cache as an ObjectStream to the '.assetcache' file
 * Private API Class
 *
 * @author David Estes
 * @author Graeme Rocher
 */
// @CompileStatic
@Slf4j
public class CacheManager {
    static final String CACHE_LOCATION = ".assetcache"
    static final Integer CACHE_DEBOUNCE_MS = 5000 // De-bounce 5 seconds
    static Map<String, Map<String, Object>> cache = [:]
    static String configCacheBustDigest
    static final Object LOCK_OBJECT = new Object()
    static final Object LOCK_FETCH_OBJECT = new Object()
    static CachePersister cachePersister

    /**
     * Returns the cache string value of a file if it exists in the cache and is unmodified since last checked
     * @param fileName The name of the file also known as the cache key
     * @param md5 The current md5 digest of the file. This is compared against the original file
     * @param originalFileName - Original file name of the file. This is for cache busting bundled assets
     * @return A String value of the cache
     */
    public static Map<String, Object> findCache(String fileName, String md5, String originalFileName = null) {
        loadPersistedCache()
        checkCacheValidity()
        def cacheRecord
        synchronized (LOCK_FETCH_OBJECT) {
            cacheRecord = cache[fileName]
            if (cacheRecord && cacheRecord.md5 == md5 && cacheRecord.originalFileName == originalFileName) {
                def cacheFiles = cacheRecord.dependencies.keySet()
                def expiredCacheFound = cacheFiles.find { String cacheFileName ->
                    def cacheFile = AssetHelper.fileForUri(cacheFileName)
                    if (!cacheFile) {
                        return true
                    }
                    def depMd5 = AssetHelper.getByteDigest(cacheFile.inputStream.bytes)
                    if (cacheRecord.dependencies[cacheFileName] != depMd5) {
                        return true
                    }
                    return false
                }

                if (expiredCacheFound) {
                    cache.remove(fileName)
                    asyncCacheSave()
                    return null
                }
                return cacheRecord
            } else if (cacheRecord) {
                cache.remove(fileName)
                asyncCacheSave()
                return null
            }
        }
    }

    /**
     * Creates a cache entry for a file. This includes a name, md5Hash and  processed file text
     * @param fileName The file name of the file to be cached (cache key)
     * @param md5Hash The current md5 hash of the file
     * @param processedFileText The processed text of the file being cached
     * @param originalFileName The original file name of the base file being persisted
     */
    public static void createCache(String fileName, String md5Hash, String processedFileText, String originalFileName = null) {
        synchronized (LOCK_FETCH_OBJECT) {
            loadPersistedCache()
            checkCacheValidity()
            def thisCache = cache
            def cacheRecord = thisCache[fileName]
            if (cacheRecord) {
                thisCache[fileName] = cacheRecord + [
                        md5              : md5Hash,
                        originalFileName : originalFileName,
                        processedFileText: processedFileText
                ]
            } else {
                thisCache[fileName] = [
                        md5              : md5Hash,
                        originalFileName : originalFileName,
                        processedFileText: processedFileText,
                        requireModules   : [:],
                        dependencies     : [:]
                ]
            }
            asyncCacheSave()
        }
    }

    /**
     * Called during asset processing to add a dependent file to another file cache. This allows for cache busting on
     * things like imported less files or sass files.
     * @param fileName The name of the file we are adding a dependency to
     * @param dependentFile the AssetFile object we are adding as a dependency
     */
    public static void addCacheDependency(String fileName, AssetFile dependentFile) {
        synchronized (LOCK_FETCH_OBJECT) {
            def cacheRecord = cache[fileName]
            if (!cacheRecord) {
                createCache(fileName, null, null)
                cacheRecord = cache[fileName]
            }

            def newMd5 = dependentFile.getByteDigest()
            cacheRecord.dependencies[dependentFile.path] = newMd5
            asyncCacheSave()
        }
    }


    /**
     * Called during asset processing to add a dependent module processed to the cache path.
     * @param fileName The name of the file we are adding a dependency to
     * @param moduleName The name of the module we are caching along side
     * @param dependentModuleContent the AssetFile object we are adding as a dependency
     */
    public static void addCacheModule(String fileName, String moduleName, String dependentModuleContent) {
        synchronized (LOCK_FETCH_OBJECT) {
            def cacheRecord = cache[fileName]
            if (!cacheRecord) {
                createCache(fileName, null, null)
                cacheRecord = cache[fileName]
            }
            cacheRecord.requireModules[moduleName] = dependentModuleContent
            asyncCacheSave()
        }
    }

    /**
     * Asynchronously starts a thread used to persist the cache to disk
     * It also performs a debounce behavior so rapid calls to the save do not cause repeat saves
     */
    public static void asyncCacheSave() {
        synchronized (LOCK_OBJECT) {
            if (!cachePersister) {
                cachePersister = new CachePersister()
                cachePersister.start()
            }
        }
        cachePersister.debounceSave(CACHE_DEBOUNCE_MS);
    }

    /**
     * Called by the async {@link CachePersister} class to save the cache map to disk
     */
    public static void save() {
        synchronized (LOCK_FETCH_OBJECT) {
            String cacheLocation = AssetPipelineConfigHolder.config?.cacheLocation
            if (!cacheLocation) {
                log.warn("Asset Pipeline Cache Location is not set. Using default location: {}", CACHE_LOCATION)
                cacheLocation = CACHE_LOCATION
            }

            FileOutputStream fos = new FileOutputStream(cacheLocation);
            Map<String, Map<String, Object>> cacheSaveData = [configCacheBustDigest: configCacheBustDigest, cache: cache]
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(cacheSaveData)
            oos.close()
        }
    }

    /**
     * Loads an Asset Cache dependency graph for asset-pipeline.
     * This is currently encoded in JSON
     *
     * If the asset cache file does not exist or a cache is already loaded, the cache store is not parsed.
     */
    public static void loadPersistedCache() {
        if (cache) {
            return;
        }
        String cacheLocation = AssetPipelineConfigHolder.config?.cacheLocation
        if (!cacheLocation) {
            log.warn("Asset Pipeline Cache Location is not set. Using default location: {}", CACHE_LOCATION)
            cacheLocation = CACHE_LOCATION
        }

        File assetFile = new File(cacheLocation)
        if (!assetFile.exists()) {
            return;
        }

        try {
            FileInputStream fis = new FileInputStream(cacheLocation)
            ObjectInputStream ois = new ObjectInputStream(fis);
            def fileCache = ois.readObject()
            if (fileCache?.configCacheBustDigest) {
                configCacheBustDigest = fileCache.configCacheBustDigest
            }
            if (fileCache?.cache) {
                fileCache.cache.each { entry ->
                    cache[entry.key] = entry.value
                }
            }

        } catch (ex) {
            // If there is a parser error from a previous bad cache flush ignore it and move on
        }

    }

    /**
     * Checks the config digest name to see if any configurations have changed.
     * If they have the cache needs to be reset and marked as expired
     */
    private static void checkCacheValidity() {
        if (configCacheBustDigest != AssetPipelineConfigHolder.getDigestString()) {
            synchronized (LOCK_FETCH_OBJECT) {
                cache.clear()
            }
            configCacheBustDigest = AssetPipelineConfigHolder.getDigestString()
        }
    }
}
