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

/**
* Provides methods for fetching contents of assets within the asset-pipeline
 *
* @author David Estes
*/
class AssetPipeline {

	/**
	* Used for serving assets in development mode via the AssetController
	* This method is NOT recommended for public use as behavior changes in production mode.
	*/
	static byte[] serveAsset(String uri, String contentType = null, String extension = null, String encoding = null) {

		def assetFile = AssetHelper.fileForUri(uri, contentType, extension)

		def directiveProcessor = new DirectiveProcessor(contentType)
		if (assetFile) {
			if(assetFile instanceof GenericAssetFile) {
				return assetFile.inputStream.bytes
			}
			def fileContents = directiveProcessor.compile(assetFile)
			encoding = encoding ?: assetFile.encoding
			if(encoding) {
				return fileContents.getBytes(encoding)
			} else {
				return fileContents.bytes
			}

		}

		return null
	}

	/**
	* Used for serving assets in development mode without bundling
	* This method is NOT recommended for public use as behavior changes in production mode.
	*/
	static byte[] serveUncompiledAsset(String uri, String contentType, String extension = null, String encoding=null) {
		def assetFile = AssetHelper.fileForUri(uri, contentType, extension)

		def directiveProcessor = new DirectiveProcessor(contentType)
		if (assetFile) {
			if(assetFile instanceof GenericAssetFile) {
				return assetFile.bytes
			}
			if(encoding) {
				assetFile.encoding = encoding
				return directiveProcessor.fileContents(assetFile).getBytes(encoding)
			} else {
				return directiveProcessor.fileContents(assetFile).bytes
			}

		}

		return null
	}

	/**
	* Used for serving assets in development mode via the AssetController
	* This method is NOT recommended for public use as behavior changes in production mode.
	*/
	static def getDependencyList(String uri, String contentType = null, String extension = null) {
		def assetFile = AssetHelper.fileForUri(uri, contentType, extension)
		def directiveProcessor = new DirectiveProcessor(contentType)
		if (assetFile) {
			return directiveProcessor.getFlattenedRequireList(assetFile)
		}
		return null
	}
}
