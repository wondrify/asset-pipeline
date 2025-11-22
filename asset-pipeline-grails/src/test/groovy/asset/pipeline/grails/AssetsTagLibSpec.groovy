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
class AssetsTagLibSpec extends Specification implements TagLibUnitTest<AssetsTagLib>  {
	private static final LINE_BREAK           = System.getProperty('line.separator') ?: '\n'
	private static final MOCK_BASE_SERVER_URL = 'http://localhost:8080/foo'


	AssetProcessorService assetProcessorService = new AssetProcessorService()


	def setup() {
		AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('application', 'grails-app/assets'))

		assetProcessorService.grailsApplication   = grailsApplication
		grails.web.mapping.LinkGenerator linkGenerator = Mock(grails.web.mapping.LinkGenerator)
		linkGenerator.getServerBaseURL() >> MOCK_BASE_SERVER_URL
		assetProcessorService.grailsLinkGenerator = linkGenerator

		final def assetMethodTagLibMock = mockTagLib(AssetMethodTagLib)
		assetMethodTagLibMock.assetProcessorService = assetProcessorService

		tagLib.assetProcessorService = assetProcessorService
	}

	void "should return assetPath"() {
		given:
			final def assetSrc = "asset-pipeline/test/test.css"
		expect:
			tagLib.assetPath(src: assetSrc) == '/assets/asset-pipeline/test/test.css'
	}

	void "should return javascript link tag when debugMode is off"() {
		given:
			grailsApplication.config = grailsApplication.config.merge([grails:[assets:[bundle:true]]])
			// grailsApplication.config.grails.assets.bundle = true
			final def assetSrc = "asset-pipeline/test/test.js"
		expect:
			tagLib.javascript(src: assetSrc) == '<script type="text/javascript" src="/assets/asset-pipeline/test/test.js" ></script>'
	}

	void "should always return javascript link tag when bundle attr is 'true'"() {
		given:
			grailsApplication.config.grails.assets.bundle = false
			grailsApplication.config.grails.assets.allowDebugParam = true
			params."_debugAssets" = "y"
			final def assetSrc = "asset-pipeline/test/test.js"
		expect:
			tagLib.javascript(src: assetSrc, bundle: 'true') == '<script type="text/javascript" src="/assets/asset-pipeline/test/test.js" ></script>'
	}

	void "should return javascript link tag with seperated files when debugMode is on"() {
		given:
			grailsApplication.config.grails.assets.bundle = false
			grailsApplication.config.grails.assets.allowDebugParam = true
			params."_debugAssets" = "y"

			final def assetSrc = "asset-pipeline/test/test.js"
			final def output

		when:
			output = tagLib.javascript(src: assetSrc)
		then:
			output == '<script type="text/javascript" src="/assets/asset-pipeline/test/test.js?compile=false" ></script>' + LINE_BREAK + '<script type="text/javascript" src="/assets/asset-pipeline/test/libs/file_a.js?compile=false" ></script>' + LINE_BREAK + '<script type="text/javascript" src="/assets/asset-pipeline/test/libs/file_c.js?compile=false" ></script>' + LINE_BREAK + '<script type="text/javascript" src="/assets/asset-pipeline/test/libs/file_b.js?compile=false" ></script>' + LINE_BREAK + '<script type="text/javascript" src="/assets/asset-pipeline/test/libs/subset/subset_a.js?compile=false" ></script>' + LINE_BREAK
	}

	void "should not return javascript link twice in uniq mode"() {
		given:
			final def assetSrc = "asset-pipeline/test/test_simple_require.js"
			final def depAssetSrc = "asset-pipeline/test/libs/file_a.js"
		expect:
			tagLib.javascript(src: assetSrc, uniq: true) == '<script type="text/javascript" src="/assets/asset-pipeline/test/libs/file_a.js?compile=false" ></script>' + LINE_BREAK + '<script type="text/javascript" src="/assets/asset-pipeline/test/test_simple_require.js?compile=false" ></script>' + LINE_BREAK
			tagLib.javascript(src: assetSrc, uniq: true) == ''
			tagLib.javascript(src: depAssetSrc, uniq: true) == ''
		cleanup:
			request."${AssetsTagLib.ASSET_REQUEST_MEMO}" = null
	}

	void "should return javascript and stylesheets of the same basename"() {
		given:
			final def jsAssetSrc = "asset-pipeline/test/test.js"
			final def cssAssetSrc = "asset-pipeline/test/test.css"
		expect:
			tagLib.javascript(src: jsAssetSrc, uniq: true) != ''
			tagLib.javascript(src: jsAssetSrc, uniq: true) == ''
			tagLib.stylesheet(src: cssAssetSrc, uniq: true) != ''
	}

	void "should return stylesheet link tag when debugMode is off"() {
		given:
			grailsApplication.config.grails.assets.bundle = true
			final def assetSrc = "asset-pipeline/test/test.css"
		expect:
			tagLib.stylesheet(href: assetSrc) == '<link rel="stylesheet" href="/assets/asset-pipeline/test/test.css" />'
	}

	void "should always return stylesheet link tag when bundle attr is 'true'"() {
		given:
			grailsApplication.config.grails.assets.bundle = false
			grailsApplication.config.grails.assets.allowDebugParam = true
			params."_debugAssets" = "y"
			final def assetSrc = "asset-pipeline/test/test.css"
		expect:
			tagLib.stylesheet(href: assetSrc, bundle: 'true') == '<link rel="stylesheet" href="/assets/asset-pipeline/test/test.css" />'
	}

	void "should return stylesheet link tag with seperated files when debugMode is on"() {
		given:
			grailsApplication.config.grails.assets.bundle = false
			grailsApplication.config.grails.assets.allowDebugParam = true
			params."_debugAssets" = "y"
			final def assetSrc = "asset-pipeline/test/test.css"
			final def output

		when:
			output = tagLib.stylesheet(src: assetSrc)
		then:
			output == '<link rel="stylesheet" href="/assets/asset-pipeline/test/test.css?compile=false" />' + LINE_BREAK + '<link rel="stylesheet" href="/assets/asset-pipeline/test/test2.css?compile=false" />' + LINE_BREAK
	}

	void "should not return stylesheet link twice in uniq mode"() {
		given:
			final def assetSrc = "asset-pipeline/test/test.css"
			final def depAssetSrc = "asset-pipeline/test/test2.css"
		expect:
			tagLib.stylesheet(src: assetSrc, uniq: true) == '<link rel="stylesheet" href="/assets/asset-pipeline/test/test.css?compile=false" />' + LINE_BREAK + '<link rel="stylesheet" href="/assets/asset-pipeline/test/test2.css?compile=false" />' + LINE_BREAK
			tagLib.stylesheet(src: depAssetSrc, uniq: true) == ''
			tagLib.stylesheet(src: assetSrc, uniq: true) == ''
		cleanup:
			request."${AssetsTagLib.ASSET_REQUEST_MEMO}" = null
	}

	void "should return image tag"() {
		given:
			final def assetSrc = "grails_logo.png"
		expect:
			tagLib.image(src: assetSrc, width:'200',height:200) == '<img src="/assets/grails_logo.png" width="200" height="200"/>'
	}

	void "should return image tag with absolute path"() {
		given:
			final def assetSrc = "grails_logo.png"
		expect:
			tagLib.image(src: assetSrc, absolute: true) == "<img src=\"$MOCK_BASE_SERVER_URL/assets/grails_logo.png\" />"
	}

	void "should return link tag"() {
		given:
			final def assetSrc = "grails_logo.png"
		expect:
			tagLib.link(href: assetSrc, rel:'test') == '<link rel="test" href="/assets/grails_logo.png"/>'
	}

	void "test if asset path exists in dev mode"() {
		given:
			final def fileUri = "asset-pipeline/test/test.css"
		expect:
			tagLib.assetPathExists([src: fileUri])
	}

	void "test if asset path is missing in dev mode"() {
		given:
			final def fileUri = "asset-pipeline/test/missing.css"
		expect:
			!tagLib.assetPathExists([src: fileUri])
	}

	void "test if asset path exists in dev mode and closure renders the body"() {
		given:
			final def fileUri = "asset-pipeline/test/test.css"
		expect:
			applyTemplate( "<asset:assetPathExists src=\"$fileUri\">text to render</asset:assetPathExists>" ) == 'text to render'
	}

	void "test if asset path is missing in dev mode and closure doesn't render the body"() {
		given:
			final def fileUri = "asset-pipeline/test/missing.css"
		expect:
			applyTemplate( "<asset:assetPathExists src=\"$fileUri\">text to render</asset:assetPathExists>" ) == ''
	}

	void "test if asset path exists in prod mode"() {
		given:
			final def fileUri = "asset-pipeline/test/test.css"
			final Properties manifestProperties = new Properties()
			manifestProperties.setProperty(fileUri,fileUri)
			grailsApplication.config.grails.assets.manifest = manifestProperties
		expect:
			tagLib.assetPathExists([src: fileUri])
	}

	void "asset path should not exist in dev mode"() {
		given:
			final def fileUri = "asset-pipeline/test/notfound.css"
		expect:
			!tagLib.assetPathExists([src: fileUri])
	}

	void "should render deferred scripts"() {
		given:
			final def script1 = "console.log('hello world 1');"
			final def script2 = "console.log('hello world 2');"

		when:
			applyTemplate("<asset:script type='text/javascript'>$script1</asset:script>")
			applyTemplate("<asset:script type='text/javascript'>$script2</asset:script>")
		then:
			applyTemplate("<asset:deferredScripts/>") == "<script type=\"text/javascript\">${script1}</script><script type=\"text/javascript\">${script2}</script>"
	}

	void "should render deferred scripts and evaluate nested groovy expressions"() {
		when:
			applyTemplate('<asset:script type="text/javascript"><g:if test="${isTrue}">alert("foo");</g:if></asset:script>', [isTrue: true])
		then:
			applyTemplate("<asset:deferredScripts/>") == '<script type="text/javascript">alert("foo");</script>'
	}
}
