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
import groovy.util.logging.Slf4j

import java.nio.file.LinkOption
import java.util.regex.Pattern

/**
 * Implementation of the {@link AssetResolver} interface for the file system
 *
 * @author David Estes
 * @author Graeme Rocher
 */

@Slf4j
class FileSystemAssetResolver extends AbstractAssetResolver<File> {
	static String QUOTED_FILE_SEPARATOR = Pattern.quote(File.separator)
	static String DIRECTIVE_FILE_SEPARATOR = '/'

	File baseDirectory
	List<String> scanDirectories = []
	List<FileSystemAssetResolver> resolvers = []

	FileSystemAssetResolver(String name,String basePath, boolean flattenSubDirectories=true) {
		super(name)
		baseDirectory = new File(basePath)
		if(baseDirectory.exists()) {
			if(flattenSubDirectories) {
				def scopedDirectories = baseDirectory.listFiles()
				for(scopedDirectory in scopedDirectories) {
					if(scopedDirectory.isDirectory() && !scopedDirectory.getName().startsWith('.') && scopedDirectory.getName() != "WEB-INF" && scopedDirectory.getName() != 'META-INF') {
						resolvers << new FileSystemAssetResolver(name, scopedDirectory.canonicalPath, false)
					}
				}
			} else {
				scanDirectories << baseDirectory.canonicalPath
			}

		}
		log.debug "Asset Pipeline FSResolver Initialized with Scan Directories: ${scanDirectories}"
	}


	public AssetFile getAsset(String relativePath, String contentType = null, String extension = null, AssetFile baseFile=null) {
		if(!relativePath) {
			return null
		}
		relativePath = relativePath.replaceAll(QUOTED_FILE_SEPARATOR,DIRECTIVE_FILE_SEPARATOR)

		if(!extension) {
			extension = AssetHelper.extensionFromURI(relativePath)
		}

		def specs
		if(contentType) {
			specs = AssetHelper.getPossibleFileSpecs(contentType)
		} else {
			specs = AssetHelper.assetFileClasses().findAll { it.extensions.contains(extension) }
		}

		for(directoryPath in scanDirectories) {
            AssetFile assetFile = resolveWildcardAsset(specs, directoryPath, relativePath, baseFile, extension)
            if(assetFile) {
                return assetFile
            }
		}
		for(resolver in resolvers) {
			AssetFile assetFile = resolver.getAsset(relativePath, contentType, extension, baseFile)
			if(assetFile) {
				return assetFile
			}
		}
		return null
	}

    @Override
    public File getRelativeFile(String relativePath, String name) {
			if(AssetHelper.isWildcardPath(name)) { //we have some wildcard patterns to resolve.
				File file = firstWildcardMatch(relativePath, name) { String path ->
					File candidate = new File(relativePath, path)
					candidate.isFile() ? candidate : null
				}
				if(file) {
					return file
				}
			}
			return new File(relativePath, name)
    }

    @Override
    protected Collection<String> subdirectoryNames(String prefixPath, String directory) {
        return new File(prefixPath, directory).listFiles()?.findAll { File file -> file.isDirectory() }*.name ?: []
    }

    @Override
    protected Closure<InputStream> createInputStreamClosure(File file) {
        if(file.exists() && !file.isDirectory()) {
            return {-> file.newInputStream() }
        }
        return null
    }

    /**
	* Implementation Requirements
	* Should be able to take a relative to baseFile scenario
	*/
    @CompileStatic
	public List<AssetFile> getAssets(String basePath, String contentType = null, String extension = null,  Boolean recursive = true, AssetFile relativeFile = null, AssetFile baseFile = null) {
		//We are going absolute
        List<AssetFile> fileList = []
        String translatedBasePath = basePath
		if(!basePath.startsWith('/') && relativeFile != null) {
			List<String> pathArgs = relativeFile.parentPath ? relativeFile.parentPath.split(DIRECTIVE_FILE_SEPARATOR).toList() : new ArrayList<String>() //(path should be relative not canonical)
			String[] basePathArgs = basePath.split(DIRECTIVE_FILE_SEPARATOR)
			List<String> parentPathArgs = pathArgs ? pathArgs[0..(pathArgs.size() - 1)] as List<String> : [] as List<String>
			parentPathArgs.addAll(basePathArgs.toList() as List<String>)
			translatedBasePath = (parentPathArgs).join(File.separator)
		}

		for(directoryPath in scanDirectories) {
			File file = new File(directoryPath,translatedBasePath)
			if(file.exists() && file.isDirectory()) {
				recursiveTreeAppend(file, fileList, contentType,baseFile,recursive, directoryPath)
			}
		}
		for(resolver in resolvers) {
			fileList += resolver.getAssets(basePath, contentType, extension, recursive, relativeFile, baseFile)
		}

		return fileList
	}

    @CompileStatic
	protected void recursiveTreeAppend(File directory, List<AssetFile> tree, String contentType=null, AssetFile baseFile, boolean recursive=true, String sourceDirectory) {
		List<File> files = directory.listFiles().toList()
		files = files?.sort { File a, File b -> a.name.compareTo b.name }
		for(File file in files) {
			String[] mimeType = AssetHelper.assetMimeTypeForURI(file.getAbsolutePath()).toArray(new String[0])
			if(file.isDirectory() && recursive) {
				recursiveTreeAppend(file,tree, contentType, baseFile, recursive, sourceDirectory)
			}
			else if(!file.isDirectory() && mimeType && contentType in mimeType) {
				tree << assetForFile(file,contentType, baseFile, sourceDirectory)
			}
		}
	}

    @Override
    @CompileStatic
    protected String getFileName(File file) {
        return file.name
    }

    @CompileStatic
	protected String relativePathToResolver(File file, String scanDirectoryPath) {
		String filePath
		try {
			filePath = file.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
		} catch(Exception ex2) {
			filePath = file.canonicalPath
		}
		
		if(filePath.startsWith(scanDirectoryPath)) {
			return filePath.substring(scanDirectoryPath.size() + 1).replace(File.separator, DIRECTIVE_FILE_SEPARATOR)
		} else {
			for(scanDir in scanDirectories) {
				if(filePath.startsWith(scanDir)) {
					return filePath.substring(scanDir.size() + 1).replace(File.separator, DIRECTIVE_FILE_SEPARATOR)
				}
			}
			throw new RuntimeException("File was not sourced from the same ScanDirectory ${filePath} scanDir: ${scanDirectoryPath}")
		}
	}


	/**
	* Uses file globbing to scan for files that need precompiled
	*/
    @CompileStatic
	public Collection<AssetFile> scanForFiles(List<String> excludePatterns, List<String> includePatterns) {
		List<AssetFile> fileList = []
		// println "Resolver ${name} Looking for Excludes ${excludePatterns} -- Includes: ${includePatterns}"
		List<String> excludedPatternRegex =  excludePatterns ? excludePatterns  as List<String> : new ArrayList<String>()
        List<String> includedPatternRegex =  includePatterns ? includePatterns as List<String> : new ArrayList<String>()

		for(String scanDirectory in scanDirectories) {
			def scanPath = new File(scanDirectory)
			iterateOverFileSystem(scanPath,excludedPatternRegex,includedPatternRegex, fileList, scanDirectory)
		}
		for(resolver in resolvers) {
			fileList += resolver.scanForFiles(excludePatterns, includePatterns)
		}
		fileList.unique { AssetFile a, AssetFile b -> a.path <=> b.path }
		return fileList
	}

    @CompileStatic
	protected iterateOverFileSystem(File dir, List<String> excludePatterns, List<String> includePatterns, List<AssetFile> fileList, String sourcePath) {
		dir.listFiles()?.each { File file ->
			def relativePath = relativePathToResolver(file, sourcePath)
			if(file.isDirectory()) {
					iterateOverFileSystem(file,excludePatterns, includePatterns, fileList, sourcePath)
			} else if(!isFileMatchingPatterns(relativePath,excludePatterns) || isFileMatchingPatterns(relativePath,includePatterns)) {
				if(!file.isDirectory()) {
					def assetFileClass = AssetHelper.assetForFileName(relativePath)
					if(assetFileClass) {
						fileList.add(assetFileClass.newInstance(inputStreamSource: { new BufferedInputStream(file.newInputStream(),512) }, baseFile: null, path: relativePath, sourceResolver: this) as AssetFile)
					} else {
						fileList.add(new GenericAssetFile(inputStreamSource: { new BufferedInputStream(file.newInputStream(),512) }, path: relativePath))
					}
				}

			}
		}
	}


}
