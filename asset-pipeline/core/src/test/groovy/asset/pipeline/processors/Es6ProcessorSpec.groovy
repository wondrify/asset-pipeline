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
class Es6ProcessorSpec extends Specification {

	void "should be able to process es6 files and load constants correctly"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
		when:
			def file = resolver.getAsset('asset-pipeline/test/test-es6',null,'js.es6')
			def processedText = file.processedStream(null)
			println processedText
		then:
			processedText.contains('"use strict";')

	}

}
