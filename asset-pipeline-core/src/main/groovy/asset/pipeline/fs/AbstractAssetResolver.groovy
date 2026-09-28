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
     * The first non-null result of {@code resolve} for the paths {@code path} stands for, each wildcard component in
     * it replaced by the name of one directory {@link #subdirectoryNames} lists, tried in
     * {@link AssetHelper#wildcardCandidates} order. A wildcard stands for exactly one directory, and a directory's
     * name is never read back as a wildcard, so a directory literally named % is one more candidate.
     */
    @CompileStatic
    protected <R> R firstWildcardMatch(String prefixPath, String path, Closure<R> resolve) {
        return firstMatch(prefixPath, [], path.split(AssetHelper.DIRECTIVE_FILE_SEPARATOR).toList(), resolve)
    }

    @CompileStatic
    private <R> R firstMatch(String prefixPath, List<String> resolved, List<String> rest, Closure<R> resolve) {
        int wildcardIndex = rest.findIndexOf { String component -> AssetHelper.isWildcardComponent(component) }
        if(wildcardIndex < 0) {
            return resolve.call((resolved + rest).join(AssetHelper.DIRECTIVE_FILE_SEPARATOR))
        }
        List<String> directory = resolved + rest.take(wildcardIndex)
        for(String name in AssetHelper.wildcardCandidates(subdirectoryNames(prefixPath, directory.join(AssetHelper.DIRECTIVE_FILE_SEPARATOR)))) {
            R found = firstMatch(prefixPath, directory + name, rest.drop(wildcardIndex + 1), resolve)
            if(found != null) {
                return found
            }
        }
        return null
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
