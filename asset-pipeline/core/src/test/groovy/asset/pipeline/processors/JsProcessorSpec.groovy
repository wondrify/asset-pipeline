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
import asset.pipeline.fs.FileSystemAssetResolver
import asset.pipeline.processors.JsProcessor
import spock.lang.Specification

/**
* @author David Estes
*/
class JsProcessorSpec extends Specification {

	def setup() {
		AssetPipelineConfigHolder.resolvers = []
		AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('application', 'assets'))
	}

	def cleanup() {
		AssetPipelineConfigHolder.config = [:]
	}

	void "asset_url() puts one slash between the mapping or base url and the asset: #label"() {
		given:
		AssetPipelineConfigHolder.config = config
		AssetFile script = AssetHelper.fileForUri('asset-pipeline/test/esm/main.js', 'application/javascript')

		expect:
		new JsProcessor(null).process("var logo = asset_url('grails_logo.png')", script) == "var logo = '${expected}'"

		where:
		label                         | config                                | expected
		'no mapping configured'       | [:]                                   | '/assets/grails_logo.png'
		'a mapping'                   | [mapping: 'static']                   | '/static/grails_logo.png'
		'an empty mapping'            | [mapping: '']                         | '/grails_logo.png'
		'a base url ending in /'      | [url: { 'https://cdn.example.com/' }] | 'https://cdn.example.com/grails_logo.png'
		'a base url without the /'    | [url: { 'https://cdn.example.com' }]  | 'https://cdn.example.com/grails_logo.png'
	}
}
