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

import asset.pipeline.fs.AssetResolver
import asset.pipeline.processors.JsRequireProcessor
import groovy.transform.CompileStatic
import java.util.regex.Pattern
import java.security.MessageDigest
import java.security.DigestInputStream


/**
* This is the base Asset File specification class. An AssetFile object should extend this abstract base class.
* The AssetFile specification provides information on what processors need to be run on a file.
* A file is matched to an AssetFile specification based on its content type and extension.
* If a file is not matched to a processable AssetFile entity, see the {@link asset.pipeline.GenericAssetFile}.
*
* @author David Estes
*/
// @CompileStatic
abstract class AbstractAssetFile implements AssetFile {

	String path

    /**
     * If this file was loaded as a sub dependency of a parent file this property will contain that baseFile reference
     * This is useful for recalculating base path relative uri patterns within the file.
     */
	AssetFile baseFile
    /**
     * The relevant {@link asset.pipeline.fs.AssetResolver} that was used to find the instance of this file
     */
	AssetResolver sourceResolver

    /**
     * The encoding of the file, typically injected by the {@link DirectiveProcessor}
     * If unspecified, the OS default is assumed
     */
	String encoding


	Pattern directivePattern = null
	Closure inputStreamSource = {} //Implemented by AssetResolver
	byte[] byteCache
	List<String> matchedDirectives = []
	DigestInputStream digestStream
	MessageDigest digest
	private String digestString

    /**
     * Executes the inputStreamSource() closure to fetch a new inputStream object
     * In the case of a standard asset file this result is cached into a Byte Array and also wrapped
     * in a DigestInputStream for efficient md5 digest generation
     * @return InputStream object of files contents (before processing)
     */
	InputStream getInputStream() {
		if(byteCache == null) {
			digest = MessageDigest.getInstance("MD5")
			digestStream = new DigestInputStream((InputStream)inputStreamSource(),digest)
			byteCache = digestStream.getBytes()
		}
		return new ByteArrayInputStream(byteCache)
	}

    /**
     * Returns a HEX encoded byte digest of the file contents (preprocessed)
     * This leverages the DigestStream wrapping the files inputStream for efficient calculation
     * If the stream is not fully read yet it will consume the rest of the stream
     * @return String hexDigest
     */
	public String getByteDigest() {
		if(digestString != null) {
			return digestString
		}
		if(!digestStream || !digest) {
			getInputStream()
		}

		try {
			byte[] buffer = new byte[1024]
			int nRead
			while((nRead = digestStream.read(buffer, 0, buffer.length)) != -1) {
				// noop (just to complete the stream)
			}
		} catch(IOException ioe) {
			// Its ok if the stream is already closed so ignore error
		} finally {
			try { digestStream?.close() } catch(Exception ex) { /*ignore if already closed this reduces open file handles*/ }
		}
		digestString = digest.digest().encodeHex().toString()
		return digestString
	}

    /**
     * Returns the canonicalPath in the context of the AssetResolver file path structure.
     * (this is not the real filesystem canonical path)
     * @return absolute path representation within an AssetResolver context
     */
    String getCanonicalPath() {
        return path
    }

    /**
     * Gets the parent path of the file
     * Behaves similarly to a File.getParent() method
     * @return
     */
	public String getParentPath() {
		String[] pathArgs = path.split("/")
		if(pathArgs.size() == 1) {
			return null
		}
		return (Arrays.copyOfRange(pathArgs,0,pathArgs.size() - 1) as String[]).join("/")
	}

    /**
     * Returns the name of the file without the path elements
     * @return Name of the file
     */
	public String getName() {
		if(path) {
			path.split("/")[-1]	
		}
	}

    /**
     * Returns a Processed String of the files contents
     * This includes a run through of all Processors in the {@link #processors} List.
     * The precompiler object is passed to all processors as well as determines the behavior of the runtime cache
     * manager.
     * @param precompiler reference to the active compiler being used (If NULL development mode is assumed)
     * @param skipCaching defaults to false. Optional flag for forcing a cache skip.
     * @return the final processed contents of the file
     */
	String processedStream(AssetCompiler precompiler, Boolean skipCaching = false) {
		String fileText
		Boolean skipCache = skipCaching ?: precompiler ?: (!processors || processors.size() == 0)
		String cacheKey
		InputStream sourceStream = getInputStream()
		try {
			if(baseFile?.encoding || encoding) {
				fileText = sourceStream?.getText(baseFile?.encoding ? baseFile.encoding : encoding)
			} else {
				fileText = sourceStream?.getText("UTF-8")
			}

			String md5 = null
			if(!skipCache) {
				md5 = getByteDigest()
				Map<String,Object> cache = CacheManager.findCache(path, md5, baseFile?.path)
				if(cache) {
					Map<String,String> moduleMaps = JsRequireProcessor.commonJsModules.get()
					if(moduleMaps != null) {
						if(cache.requireModules) {
							moduleMaps = cache.requireModules + moduleMaps
							JsRequireProcessor.commonJsModules.set(moduleMaps)
						}
					}
					return cache.processedFileText
				}
			}

			if(processors != null) {
				for(Class<Processor> processor in processors) {
					Processor processInstance = processor.newInstance(precompiler) as Processor
					fileText = processInstance.process(fileText, this)
				}	
			}
		    

			if(!skipCache) {
				CacheManager.createCache(path, md5, fileText, baseFile?.path)
			}
		} finally {
			try { sourceStream?.close() } catch(Exception ex) { /*doesnt matter this just ensures it closes at the end*/}
		}

		return fileText
	}

    /**
     * String representation of the object defaults to the full path of the file
     * @return
     */
	public String toString() {
		return path
	}

    /**
     * Returns the directive pattern used to perform bundling in the comments of the file
     * it is possible for the pattern to be NULL if this file type does not support it
     * @return multi-line regex Pattern for finding //=require like directives
     */
	public Pattern getDirectivePattern() {
		return this.directivePattern
	}
}
