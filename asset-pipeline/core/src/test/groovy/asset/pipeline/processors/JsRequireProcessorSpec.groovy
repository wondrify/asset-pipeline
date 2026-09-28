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

package asset.pipeline.processor

import asset.pipeline.*
import spock.lang.Specification
import asset.pipeline.fs.FileSystemAssetResolver

/**
* @author David Estes
*/
class JsRequireProcessorSpec extends Specification {

	void "should be able to load another js file via commonJs syntax"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
			AssetPipelineConfigHolder.resolvers = []
			AssetPipelineConfigHolder.registerResolver(resolver)
			AssetPipelineConfigHolder.config = [commonJs: true]
		when:
			def file = resolver.getAsset('asset-pipeline/test/test-common-js','application/javascript','js')
			println "file class: ${file.class.name} - ${file.processors}"
			def processedText = file.processedStream(null)
			println processedText
		then:
			processedText.contains('in a required file')
			processedText.contains("S3.require('common_module')");
			processedText.contains("S3require('common_module')");

	}

}
