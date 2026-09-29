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

package asset.pipeline.fs

import asset.pipeline.AssetFile
import asset.pipeline.AssetHelper
import asset.pipeline.GenericAssetFile
import groovy.transform.CompileStatic
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import groovy.util.logging.Slf4j
/**
 * The abstract class for any helper methods in resolving files
 *
 * @author David Estes
 */
 @Slf4j
abstract class AbstractAssetResolver<T> implements AssetResolver<T> {
    String name

    AbstractAssetResolver(String name) {
        this.name = name
    }

    protected abstract String relativePathToResolver(T file, String scanDirectoryPath)

    abstract T getRelativeFile(String relativePath, String name)

    protected abstract Closure<InputStream> createInputStreamClosure(T file)

    /**
     * The names of the directories directly under {@code directory}, a path relative to {@code prefixPath} ('' for
     * {@code prefixPath} itself): what a wildcard component can stand for. This implementation lists none, so a
     * resolver resolves wildcards through {@link #firstWildcardMatch} only once it overrides this.
     */
    protected Collection<String> subdirectoryNames(String prefixPath, String directory) {
        return []
    }

    /**
     * What identifies {@code directory}, a path relative to {@code prefixPath}, for a %% walk to visit each directory
     * once. The path itself here; a resolver whose directories can be reached by more than one path, through a
     * symbolic link, returns something those paths share.
     */
    protected Object directoryIdentity(String prefixPath, String directory) {
        return directory
    }

    /**
     * The first non-null result of {@code resolve} for the paths {@code path} stands for, each % or * in it replaced
     * by the name of one directory {@link #subdirectoryNames} lists, and each %% or ** by any number of them, none
     * included. Directories are tried in {@link AssetHelper#wildcardCandidates} order, and a %% takes the fewest
     * directories first, trying every path one level down before any two levels down. A directory's name is never
     * read back as a wildcard, so a directory literally named % is one more candidate.
     */
    @CompileStatic
    protected <R> R firstWildcardMatch(String prefixPath, String path, Closure<R> resolve) {
        return firstMatch(prefixPath, [], path.split(AssetHelper.DIRECTIVE_FILE_SEPARATOR).toList(), resolve)
    }

    @CompileStatic
    private <R> R firstMatch(String prefixPath, List<String> resolved, List<String> rest, Closure<R> resolve) {
        int wildcardIndex = rest.findIndexOf { String component -> isAnyWildcard(component) }
        if(wildcardIndex < 0) {
            return resolve.call((resolved + rest).join(AssetHelper.DIRECTIVE_FILE_SEPARATOR))
        }
        List<String> directory = resolved + rest.take(wildcardIndex)
        List<String> after = rest.drop(wildcardIndex + 1)
        List<List<String>> level = AssetHelper.isDeepWildcardComponent(rest[wildcardIndex]) ? [directory] : subdirectories(prefixPath, directory)
        Set<Object> visited = [directoryIdentity(prefixPath, directory.join(AssetHelper.DIRECTIVE_FILE_SEPARATOR))] as Set<Object>
        while(level) {
            for(List<String> candidate in level) {
                R found = firstMatch(prefixPath, candidate, after, resolve)
                if(found != null) {
                    return found
                }
            }
            if(!AssetHelper.isDeepWildcardComponent(rest[wildcardIndex])) {
                return null
            }
            // A %% goes one level deeper only once every path at this depth has failed
            level = level.collectMany { List<String> parent -> subdirectories(prefixPath, parent) }.findAll { List<String> candidate ->
                visited.add(directoryIdentity(prefixPath, candidate.join(AssetHelper.DIRECTIVE_FILE_SEPARATOR)))
            }
        }
        return null
    }

    @CompileStatic
    private List<List<String>> subdirectories(String prefixPath, List<String> directory) {
        return AssetHelper.wildcardCandidates(subdirectoryNames(prefixPath, directory.join(AssetHelper.DIRECTIVE_FILE_SEPARATOR))).collect { String name -> directory + name }
    }

    // Not private: resolveWildcardAsset calls it from a closure, which a subclass instance dispatches dynamically
    @CompileStatic
    protected static boolean isAnyWildcard(String component) {
        return AssetHelper.isWildcardComponent(component) || AssetHelper.isDeepWildcardComponent(component)
    }


    /**
     * {@link #resolveAsset} for a resolver that lists its directories ({@link #subdirectoryNames}), expanding the
     * wildcards in the directories of {@code normalizedPath} once, rather than once for every extension resolveAsset
     * tries. Each directory the path can stand for is resolved as a plain path, in {@link #firstWildcardMatch} order,
     * so the first one that holds the file, under any extension, wins.
     */
    protected AssetFile resolveWildcardAsset(specs, String prefixPath, String normalizedPath, AssetFile baseFile, String extension) {
        int nameIndex = normalizedPath.lastIndexOf(AssetHelper.DIRECTIVE_FILE_SEPARATOR)
        if(nameIndex < 0 || !normalizedPath.substring(0, nameIndex).split(AssetHelper.DIRECTIVE_FILE_SEPARATOR).any { String component -> isAnyWildcard(component) }) {
            return resolveAsset(specs, prefixPath, normalizedPath, baseFile, extension)
        }
        return firstWildcardMatch(prefixPath, normalizedPath.substring(0, nameIndex)) { String directory ->
            resolveAsset(specs, prefixPath, directory + AssetHelper.DIRECTIVE_FILE_SEPARATOR + normalizedPath.substring(nameIndex + 1), baseFile, extension)
        }
    }

    protected AssetFile resolveAsset(specs, String prefixPath, String normalizedPath, AssetFile baseFile, String extension) {
        if (specs) {
            def extensionMap = [:]
            for(fileSpec in specs) {
                for(ext in fileSpec.extensions) {
                    if(extensionMap[ext] == null) {
                        extensionMap[ext] = fileSpec
                    }
                }
            }

            def extensions = extensionMap.keySet().sort{a,b -> -(a.size()) <=> -(b.size())}
						//we want to see if there is an extension exact match first before going down the list
						if(extension && extensionMap[extension]) {
							extensions.remove(extension)
							extensions.add(0, extension)
						}
            for (ext in extensions) {
                def fileSpec = extensionMap[ext]
                def fileName = normalizedPath
                if (fileName.endsWith(".${fileSpec.compiledExtension}")) {
                    fileName = fileName.substring(0, fileName.lastIndexOf(".${fileSpec.compiledExtension}"))
                }

                def tmpFileName = fileName
                if (!tmpFileName.endsWith("." + ext)) {
                    tmpFileName += "." + ext
                }
                // log.info("Looking for Relative File: ${tmpFileName} in prefixPath: ${prefixPath}")
                def file = getRelativeFile(prefixPath, tmpFileName)
                def inputStreamClosure = createInputStreamClosure(file)

                if (inputStreamClosure && file != null) {
                    return fileSpec.newInstance(inputStreamSource: inputStreamClosure, baseFile: baseFile, path: relativePathToResolver(file, prefixPath), sourceResolver: this)
                }
                
            }
        }
        //If we cant find a processable entity we load it as Generic
        def fileName = normalizedPath
        if (extension) {
            if (!fileName.endsWith(".${extension}")) {
                fileName += ".${extension}"
            }
        }
        def file = getRelativeFile(prefixPath, fileName)
        def inputStreamClosure = createInputStreamClosure(file)
        if (inputStreamClosure && file != null) {
            return new GenericAssetFile(inputStreamSource: inputStreamClosure, path: relativePathToResolver(file, prefixPath))
        }
        
        return null
    }

    /**
     * A method for converting glob patterns into regex. Not used anymore as Java 7 Path patterns are now used
     * @deprecated
     */
    @CompileStatic
    public Pattern convertGlobToRegEx(String line)
    {
        line = line.trim();
        int strLen = line.length();
        StringBuilder sb = new StringBuilder(strLen);
        // Remove beginning and ending * globs because they're useless
        if (line.startsWith("*"))
        {
            line = line.substring(1);
            strLen--;
        }
        if (line.endsWith("*"))
        {
            line = line.substring(0, strLen-1);
            strLen--;
        }
        boolean escaping = false;
        int inCurlies = 0;
        for (char currentChar : line.toCharArray())
        {
            switch (currentChar)
            {
                case '*':
                if (escaping)
                sb.append("\\*");
                else
                sb.append(".*");
                escaping = false;
                break;
                case '?':
                if (escaping)
                sb.append("\\?");
                else
                sb.append('.');
                escaping = false;
                break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                sb.append('\\');
                sb.append(currentChar);
                escaping = false;
                break;
                case '\\':
                if (escaping)
                {
                    sb.append("\\\\");
                    escaping = false;
                }
                else
                escaping = true;
                break;
                case '{':
                if (escaping)
                {
                    sb.append("\\{");
                }
                else
                {
                    sb.append('(');
                    inCurlies++;
                }
                escaping = false;
                break;
                case '}':
                if (inCurlies > 0 && !escaping)
                {
                    sb.append(')');
                    inCurlies--;
                }
                else if (escaping)
                sb.append("\\}");
                else
                sb.append("}");
                escaping = false;
                break;
                case ',':
                if (inCurlies > 0 && !escaping)
                {
                    sb.append('|');
                }
                else if (escaping)
                sb.append("\\,");
                else
                sb.append(",");
                break;
                default:
                escaping = false;
                sb.append(currentChar);
            }
        }
        return Pattern.compile(sb.toString());
    }

    protected AssetFile assetForFile(T file, String contentType, AssetFile baseFile=null, String sourceDirectory) {
        if(file == null) {
            return null
        }

        if(contentType == null) {
            return new GenericAssetFile(inputStreamSource: createInputStreamClosure(file), path: relativePathToResolver(file,sourceDirectory))
        }

        def possibleFileSpecs = AssetHelper.getPossibleFileSpecs(contentType)
        def longestExtension = null
        def matchingSpec = null
        for(fileSpec in possibleFileSpecs) {
            for(extension in fileSpec.extensions) {
                if(extension.size() > (longestExtension?.size() ?: 0)) {
                    def fileName = getFileName(file)
                    if(fileName.endsWith("." + extension)) {
                        longestExtension = extension
                        matchingSpec = fileSpec
                    }    
                }
            }
        }
        
        if(matchingSpec) {
            return matchingSpec.newInstance(inputStreamSource: createInputStreamClosure(file), baseFile: baseFile, path: relativePathToResolver(file,sourceDirectory), sourceResolver: this)
        }

        return new GenericAssetFile(inputStreamSource: createInputStreamClosure(file), path: relativePathToResolver(file,sourceDirectory))
    }

    protected abstract String getFileName(T file)

    @CompileStatic
    protected boolean isFileMatchingPatterns(String filePath, List<String> patterns) {
        return AssetHelper.isFileMatchingPatterns(filePath,patterns)
    }

}
