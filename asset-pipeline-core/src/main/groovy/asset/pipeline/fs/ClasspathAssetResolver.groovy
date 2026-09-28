package asset.pipeline.fs

import asset.pipeline.AssetFile
import asset.pipeline.AssetHelper
import asset.pipeline.GenericAssetFile
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.ZipEntry

/**
* Implementation of the {@link AssetResolver} interface for resolving files on the classpath.
* It is important to note that recursive scanning does not function unless an assets.list file is supplied
*
* @author David Estes
*/
@Slf4j
public class ClasspathAssetResolver extends AbstractAssetResolver<Object> {
    static String NATIVE_FILE_SEPARATOR = File.separator
    static String DIRECTIVE_FILE_SEPARATOR = '/'
		static String QUOTED_FILE_SEPARATOR = Pattern.quote("/")
    ClassLoader classLoader
    String prefixPath
    String assetListPath
    Collection<String> assetList = []

	ConcurrentHashMap<String,AssetResolver> subResolvers = new ConcurrentHashMap<>()//new ConcurrentHashMap<String,AssetResolver>()

    ClasspathAssetResolver(String name, String basePath, String assetListPath=null, ClassLoader classLoader = Thread.currentThread().contextClassLoader) {
        super(name)

        this.classLoader = classLoader
        this.prefixPath = basePath
        this.assetListPath = assetListPath
        loadAssetList()
    }


    private loadAssetList() {
        if (!assetListPath) {
            return
        }
        def resources = classLoader.getResources(assetListPath)
        resources.each { URL res ->
            assetList += res?.text?.tokenize("\n") ?: []
        }
    }

    AssetFile getAsset(String relativePath, String contentType = null, String extension = null, AssetFile baseFile = null) {
        if (!relativePath) {
            return null
        }
        def normalizedPath = AssetHelper.normalizePath(relativePath.replace(NATIVE_FILE_SEPARATOR, DIRECTIVE_FILE_SEPARATOR))
        if(!normalizedPath) {
            return null
        }

        if (!extension) {
            extension = AssetHelper.extensionFromURI(relativePath)
        }

        def specs
        if (contentType) {
            specs = AssetHelper.getPossibleFileSpecs(contentType)
        } else {
            specs = AssetHelper.assetFileClasses().findAll { it.extensions.contains(extension) }
        }

        AssetFile assetFile = resolveWildcardAsset(specs, prefixPath, normalizedPath, baseFile, extension)

        return assetFile
    }

    protected Closure<InputStream> createInputStreamClosure(Object file) {
      if(file instanceof URL) {
				URL url = file as URL
				return { -> new BufferedInputStream(url.openStream(), 512) }
			}  else if (file instanceof JarAssetEntry) {
				JarAssetEntry jarAssetEntry = file as JarAssetEntry
				return jarAssetEntry.resolver.createInputStreamClosure(jarAssetEntry.zipEntry)
			} else if(file instanceof File) {
				File f = file as File
				return {-> f.newInputStream() }
			}

			return null
    }

    String relativePathToResolver(Object file, String scanDirectoryPath) {
			if(file instanceof URL) {
				URL url = file as URL
				def filePath = url.path
				if (filePath.contains(scanDirectoryPath)) {
					def i = filePath.indexOf(scanDirectoryPath)
					return filePath.substring(i + scanDirectoryPath.size() + 1)
				} else {
					throw new RuntimeException("File was not sourced from the same ScanDirectory ${filePath}")
				}
			}  else if (file instanceof JarAssetEntry) {
				JarAssetEntry jarAssetEntry = file as JarAssetEntry
				ZipEntry zipEntry = jarAssetEntry.zipEntry
				return jarAssetEntry.resolver.relativePathToResolver(zipEntry,scanDirectoryPath)
			} else if(file instanceof File) {
				File f = file as File
				String filePath
				try {
					filePath = f.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
				} catch(Exception ex2) {
					filePath = f.canonicalPath
				}

				if(filePath.startsWith(scanDirectoryPath)) {
					return filePath.substring(scanDirectoryPath.size() + 1).replace(File.separator, DIRECTIVE_FILE_SEPARATOR)
				} else {
					throw new RuntimeException("File was not sourced from the same ScanDirectory ${filePath} scanDir: ${scanDirectoryPath}")
				}
			} else {
				throw new RuntimeException("File was not sourced from the same ScanDirectory ${prefixPath}")

			}
    }

    @CompileStatic
		Object getRelativeFile(String relativePath, String name) {
        if (name.startsWith('/')) {
            name = name.substring(1)
        }

				if(AssetHelper.isWildcardPath(name)) { //we have some wildcard patterns to resolve.
					URL file = firstWildcardMatch(relativePath, name) { String path -> resourceFile(relativePath, path) }
					if(file) {
						return file
					}
				}

        return resourceFile(relativePath, name)
    }

    /**
     * The resource at {@code path}, unless it is a directory
     */
    @CompileStatic
    private URL resourceFile(String relativePath, String path) {
        URL file = classLoader.getResource("$relativePath/$path")
        if (file?.getProtocol()?.equals("file")) {
            if(new File(file.getPath()).isDirectory()) {
                return null
            }
        } else if(file?.getPath()?.endsWith("/")) {
            return null
        }
        return file
    }

    /**
     * The directories directly under {@code directory} in every jar and every directory on the classpath that has it
     */
    @Override
    @CompileStatic
    protected Collection<String> subdirectoryNames(String prefixPath, String directory) {
        Set<String> names = new HashSet<String>()
        for(URL root in classLoader.getResources([prefixPath, directory].findAll().join(DIRECTIVE_FILE_SEPARATOR) + DIRECTIVE_FILE_SEPARATOR)) {
            if(root.protocol == 'file') {
                names.addAll(new File(root.toURI()).listFiles()?.findAll { File file -> file.isDirectory() }*.name ?: [])
            } else if(root.protocol == 'jar') {
                URL jarUrl = ((JarURLConnection) root.openConnection()).jarFileURL
                if(jarUrl.protocol == 'file') {
                    File jarFile = new File(jarUrl.toURI())
                    JarAssetResolver jarResolver = (JarAssetResolver) subResolvers.computeIfAbsent(jarFile.path) { String path -> new JarAssetResolver("${name}:${path}", path, prefixPath) }
                    names.addAll(jarResolver.subdirectoryNames(prefixPath, directory))
                }
            }
        }
        return names
    }

    @Override
    @CompileStatic
    protected String getFileName(Object file) {
			if(file instanceof URL) {
				URL url = file as URL
				String path = url.path
				String name = path
				if (path.lastIndexOf('/'))
					name = path.substring(path.lastIndexOf('/'))
				return name
			} else if (file instanceof JarAssetEntry) {
				JarAssetEntry jarAssetEntry = file as JarAssetEntry
				return jarAssetEntry.zipEntry.name
			} else if (file instanceof File) {
				File f = file as File
				return f.name
			}
    }

    @CompileStatic
    List<AssetFile> getAssets(String basePath, String contentType = null, String extension = null, Boolean recursive = true, AssetFile relativeFile = null, AssetFile baseFile = null) {
			Collection<Class<AssetFile>> specs
        if (contentType) {
            specs = AssetHelper.getPossibleFileSpecs(contentType)
        }
        if (!assetList) {
            return []
        }

        def extensions = []
        if (extension) {
            extensions << extension
        } else if (specs) {
            for (spec in specs) {
                if(spec.extensions) {
                    extensions.addAll(spec.extensions)
                }
            }
        }

        String translatedBasePath = basePath
        if (!basePath.startsWith('/') && relativeFile != null) {
            List<String> pathArgs = relativeFile.parentPath ? relativeFile.parentPath.split(DIRECTIVE_FILE_SEPARATOR).toList() : new ArrayList<String>()
            //(path should be relative not canonical)
            String[] basePathArgs = basePath.split(DIRECTIVE_FILE_SEPARATOR)
            List<String> parentPathArgs = pathArgs ? pathArgs[0..(pathArgs.size() - 1)] as List<String> : [] as List<String>
            parentPathArgs.addAll(basePathArgs.toList() as List<String>)
            translatedBasePath = (parentPathArgs).join(DIRECTIVE_FILE_SEPARATOR)
            translatedBasePath = AssetHelper.normalizePath(translatedBasePath)
            translatedBasePath = translatedBasePath ? (translatedBasePath + "/") : null
        }

        List<AssetFile> tree = []
        for (String filePath in assetList) {
            if (!translatedBasePath || filePath.startsWith(translatedBasePath)) {
                String[] mimeType = AssetHelper.assetMimeTypeForURI(filePath).toArray(new String[0])
                def url = classLoader.getResource("$prefixPath/$filePath")
                if (url && mimeType && contentType in mimeType) {
                    tree << assetForFile(url, contentType, baseFile, prefixPath)
                }

            }
        }
        return tree
    }

    /**
     * Uses file globbing to scan for files that need precompiled
     */
    public Collection<AssetFile> scanForFiles(List<String> excludePatterns, List<String> includePatterns) {
        def fileList = []
        List<String> excludedPatternList = excludePatterns ? excludePatterns : new ArrayList<String>()
        List<String> includedPatternList = includePatterns ? includePatterns : new ArrayList<String>()

        for (String relativePath in assetList) {
            def entry = classLoader.getResource("$prefixPath/$relativePath")

            if (entry && !isFileMatchingPatterns(relativePath, excludedPatternList) || isFileMatchingPatterns(relativePath, includedPatternList)) {

                def assetFileClass = AssetHelper.assetForFileName(relativePath)
                if (assetFileClass) {
                    fileList << assetFileClass.newInstance(inputStreamSource: createInputStreamClosure(entry), path: relativePath, sourceResolver: this)
                } else {
                    fileList << new GenericAssetFile(inputStreamSource: createInputStreamClosure(entry), path: relativePath)
                }
            }
        }
        return fileList.unique { AssetFile a, AssetFile b -> a.path <=> b.path }
    }
}
