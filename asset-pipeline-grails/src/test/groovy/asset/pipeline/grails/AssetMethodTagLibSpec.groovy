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
package asset.pipeline.grails


import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.fs.FileSystemAssetResolver
import spock.lang.Specification
import grails.testing.web.taglib.TagLibUnitTest

/**
 * @author David Estes
 */
class AssetMethodTagLibSpec extends Specification implements TagLibUnitTest<AssetMethodTagLib> {

	private static final MOCK_BASE_SERVER_URL = 'http://localhost:8080/foo'


	AssetProcessorService assetProcessorService = new AssetProcessorService()


	def setup() {
		AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('application','grails-app/assets'))

		assetProcessorService.grailsApplication   = grailsApplication
		grails.web.mapping.LinkGenerator linkGenerator = Mock(grails.web.mapping.LinkGenerator)
		linkGenerator.getServerBaseURL() >> MOCK_BASE_SERVER_URL
		assetProcessorService.grailsLinkGenerator = linkGenerator


		tagLib.assetProcessorService = assetProcessorService
	}

	void "should return assetPath"() {
		given:
          final def assetSrc = "asset-pipeline/test/test.css"
		expect:
			tagLib.assetPath(src: assetSrc) == '/assets/asset-pipeline/test/test.css'
	}

	void "should resolve a wildcard directory in assetPath without a manifest"() {
		expect: 'the directory the resolver finds, as <asset:javascript> gives, not a literal %'
			tagLib.assetPath(src: 'asset-pipeline/test/versioned/%/versioned.js') == '/assets/asset-pipeline/test/versioned/1.2.3/versioned.js'
	}
}
