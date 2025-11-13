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

import spock.lang.Specification
import asset.pipeline.fs.FileSystemAssetResolver
import asset.pipeline.utils.Handler
/**
 * @author David Estes
 */
class AssetHelperSpec extends Specification {
    def setup() {
        AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('application','assets'))
    }


    void "should get file specs by contentType"() {
    	when:
    		def assetHelper = AssetHelper
    	then:
	    	fileSpecs as Set == assetHelper.getPossibleFileSpecs(contentType) as Set
	    where:
	    	contentType                | fileSpecs
	    	'application/javascript'   | [JsAssetFile, JsEs6AssetFile]
	    	'application/x-javascript' | [JsAssetFile, JsEs6AssetFile]
	    	'text/javascript'          | [JsAssetFile, JsEs6AssetFile]
	    	'text/css'                 | [CssAssetFile]
	    	'text/html'                | [HtmlAssetFile]
	    	'blob/text'                | []
    }

    void "Should Resolve asset:// URL Protocol Spec"() {
        given:
            def url = new java.net.URL(null,"asset:///asset-pipeline/test/test.css", new Handler())
        when:
            def results = url.text
        then:
            results.contains('#logo')
    }

    void "should get asset file object based on file name and extension" () {
    	given:
            def testFileName = 'asset-pipeline/test/test'
            def testFileExt = "css"
            def assetFile
        when:
        	assetFile = AssetHelper.getAssetFileWithExtension(testFileName, testFileExt)
        then:
        	assetFile?.name == 'test.css'

        when:
	        testFileName = 'asset-pipeline/test/test'
            testFileExt = "sass"
        	assetFile = AssetHelper.getAssetFileWithExtension(testFileName, testFileExt)
        then:
        	assetFile == null

        when:
	        testFileName = 'asset-pipeline/test/test.css'
	        testFileExt = null
        	assetFile = AssetHelper.getAssetFileWithExtension(testFileName, testFileExt)
        then:
        	assetFile?.name == 'test.css'
    }

    void "should provide file name without the extension"() {
    	given:
	    	def testName = "test.min.js"
	    	def nameWithoutExt
    	when:
    		nameWithoutExt = AssetHelper.nameWithoutExtension(testName)
    	then:
    		nameWithoutExt == 'test.min'
    }

    void "should extract extension from file name"() {
    	given:
	    	def testName = "test.min.js"
	    	def ext
    	when:
    		ext = AssetHelper.extensionFromURI(testName)
    	then:
    		ext == 'js'
    }

    void "should normalize paths"() {
        when:
        def result = AssetHelper.normalizePath(path)
        then:
        result == normalizedPath
        where:
        path                      | normalizedPath
        'images/../test.png'      | 'test.png'
        '../fonts/test.eot'       | 'test.eot'
    }

    // WebJar resolution tests

    void "resolveWebjarPath handles non-webjar paths"() {
        when:
            def result = AssetHelper.resolveWebjarPath('js/application.js')
        then:
            result == 'js/application.js'
    }

    void "resolveWebjarPath handles null paths"() {
        when:
            def result = AssetHelper.resolveWebjarPath(null)
        then:
            result == null
    }

    void "resolveWebjarPath handles empty string"() {
        when:
            def result = AssetHelper.resolveWebjarPath('')
        then:
            result == ''
    }

    void "resolveWebjarPath handles already-versioned webjar paths"() {
        when:
            def result = AssetHelper.resolveWebjarPath('webjars/jquery/3.7.1/dist/jquery.min.js')
        then:
            result == 'webjars/jquery/3.7.1/dist/jquery.min.js'
    }

    void "resolveWebjarPath handles already-versioned webjar paths with beta versions"() {
        when:
            def result = AssetHelper.resolveWebjarPath('webjars/bootstrap/5.3.0-beta.2/dist/css/bootstrap.css')
        then:
            result == 'webjars/bootstrap/5.3.0-beta.2/dist/css/bootstrap.css'
    }

    void "resolveWebjarPath resolves version-less jQuery path correctly"() {
        when:
            def result = AssetHelper.resolveWebjarPath('webjars/dist/jquery.min.js')
        then:
            // Should resolve to versioned path with jQuery 3.7.1
            result.startsWith('webjars/jquery/')
            result.contains('/dist/jquery.min.js')
            result =~ /webjars\/jquery\/\d+\.\d+/
    }

    void "resolveWebjarPath resolves version-less Bootstrap CSS path correctly"() {
        when:
            def result = AssetHelper.resolveWebjarPath('webjars/dist/css/bootstrap.css')
        then:
            // Should resolve to versioned path with Bootstrap 5.3.0
            result.startsWith('webjars/bootstrap/')
            result.contains('/dist/css/bootstrap.css')
            result =~ /webjars\/bootstrap\/\d+\.\d+/
    }

    void "resolveWebjarPath caches resolved paths"() {
        when:
            // First call should resolve and cache
            def result1 = AssetHelper.resolveWebjarPath('webjars/dist/jquery.min.js')
            // Second call should come from cache
            def result2 = AssetHelper.resolveWebjarPath('webjars/dist/jquery.min.js')
        then:
            result1 == result2
            result1.startsWith('webjars/jquery/')
    }

    void "clearWebJarCache clears the cache"() {
        when:
            AssetHelper.clearWebJarCache()
        then:
            notThrown(Exception)
    }
}
