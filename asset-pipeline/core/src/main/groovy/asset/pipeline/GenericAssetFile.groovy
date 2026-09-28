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

import groovy.transform.CompileStatic
import java.security.MessageDigest
import java.security.DigestInputStream

/**
 * Generic implementation of the {@link AssetFile} interface
 * This is used to reference objects that do not get otherwise processed. It could be an image,
 * or even a generic file not interpreted by the asset-pipeline system.
 *
 * @author David Estes
 */
@CompileStatic
class GenericAssetFile extends AbstractAssetFile {

	String path

	Closure inputStreamSource = {} //Implemented by AssetResolver

    /**
     * Returns an inputStream reference to the file. Since it is not a processed file this content is not saved in
     * memory. This allows for digesting and inclusion of large unprocessed files without causing memory overhead.
     * @return inputStream of file being read
     */
	InputStream getInputStream() {
		this.digest = MessageDigest.getInstance("MD5")
		this.digestStream = new DigestInputStream((InputStream)inputStreamSource(),digest)
		return digestStream
	}

    /**
     * Returns the parent path or directory with which this file belongs
     * @return the parent path of the file being read (paths without name)
     */
	public String getParentPath() {
		List<String> pathArgs = path.tokenize("/")
		if(pathArgs.size() == 1) {
			return null
		}
		return (pathArgs[0..-2]).join("/")
	}

    /**
     * Returns a byte array of the inputStream contents
     * NOTE: For large files this could result in large memory issues. Not recommended to use anymore.
     * @return
     */
	public Byte[] getBytes() {
		return inputStream.bytes
	}

    /**
     * The name of the file without all path elements
     * @return
     */
	public String getName() {
		path.split("/")[-1]
	}

}
