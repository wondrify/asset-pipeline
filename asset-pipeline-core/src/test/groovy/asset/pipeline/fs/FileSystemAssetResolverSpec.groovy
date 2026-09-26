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


import asset.pipeline.CssAssetFile
import asset.pipeline.GenericAssetFile
import asset.pipeline.JsAssetFile
import spock.lang.Specification

/**
* @author David Estes
*/
class FileSystemAssetResolverSpec extends Specification {

	void "should be able to fetch generic files with seperated extension"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
		when:
			def file = resolver.getAsset('grails_logo',null,'png')
		then:
			file instanceof GenericAssetFile
	}

	void "should not resolve an asset if no extension or content type is specified"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
		when:
			def file = resolver.getAsset('asset-pipeline/test/test')
		then:
			file == null
	}

	void "should be able to fetch generic files without seperated extension"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
		when:
			def file = resolver.getAsset('grails_logo.png')
		then:
			file instanceof GenericAssetFile
	}

	void "should be able to resolve js files based on a content-type"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
		when:
			def file = resolver.getAsset('asset-pipeline/test/test','application/javascript')
		then:
			file instanceof JsAssetFile
	}

	void "should be able to resolve css files based on a content-type"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
		when:
			def file = resolver.getAsset('asset-pipeline/test/test','text/css')
		then:
			file instanceof CssAssetFile
	}

	void "should be able to fetch files recursively by content-type"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
		when:
			def files = resolver.getAssets('asset-pipeline/test/libs','application/javascript')
		then:
			files?.size() == 4
	}

	void "should be able to fetch files recursively by content-type with relative baseFile"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
		when:
			def relativeFile = resolver.getAsset('asset-pipeline/test/libs/file_a','application/javascript')
			println "Fetched Relative File ${relativeFile?.name}"
			def files = resolver.getAssets('.','application/javascript', null, true, relativeFile)
		then:
			files?.size() == 4
	}

	void "should prefer .js file over .mjs file when explicitly requesting .js extension"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
			def jsFile = new File('assets/javascripts/mylibrary.js')
			def mjsFile = new File('assets/javascripts/mylibrary.mjs')
			jsFile.parentFile.mkdirs()
			jsFile.text = '// JavaScript file'
			mjsFile.text = '// ES Module file'
		when:
			def file = resolver.getAsset('mylibrary.js', 'application/javascript')
		then:
			file != null
			file.name == 'mylibrary.js'
			!file.name.endsWith('.mjs')
		cleanup:
			jsFile?.delete()
			mjsFile?.delete()
	}

	void "should resolve .mjs file when explicitly requesting .mjs extension"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
			def jsFile = new File('assets/javascripts/mylibrary.js')
			def mjsFile = new File('assets/javascripts/mylibrary.mjs')
			jsFile.parentFile.mkdirs()
			jsFile.text = '// JavaScript file'
			mjsFile.text = '// ES Module file'
		when:
			def file = resolver.getAsset('mylibrary.mjs', 'application/javascript')
		then:
			file != null
			file.name == 'mylibrary.mjs'
		cleanup:
			jsFile?.delete()
			mjsFile?.delete()
	}

	void "should prefer .mjs file over .js when no extension is specified"() {
		given:
			def resolver = new FileSystemAssetResolver('application','assets')
			def jsFile = new File('assets/javascripts/mylibrary.js')
			def mjsFile = new File('assets/javascripts/mylibrary.mjs')
			jsFile.parentFile.mkdirs()
			jsFile.text = '// JavaScript file'
			mjsFile.text = '// ES Module file'
		when:
			// When no extension is specified, should prefer .mjs (modern ES modules) due to extension priority
			def file = resolver.getAsset('mylibrary', 'application/javascript')
		then:
			file != null
			file.name == 'mylibrary.mjs'
			file.path.endsWith('.mjs')
		cleanup:
			jsFile?.delete()
			mjsFile?.delete()
	}

	void "a wildcard directory resolves to the directory that holds the file, not only the first one listed: #path"() {
		given: 'wildcard-dirs has the subdirectories a, b and c; each only-in-* file exists under one of them'
			def resolver = new FileSystemAssetResolver('application','assets')
		when:
			def file = resolver.getAsset("asset-pipeline/test/wildcard-dirs/${path}", 'application/javascript', 'js')
		then:
			file?.path == (resolved ? "asset-pipeline/test/wildcard-dirs/${resolved}.js" : null)
		where:
			path                | resolved
			'%/only-in-a'       | 'a/only-in-a'
			'%/only-in-b'       | 'b/only-in-b'
			'%/only-in-c'       | 'c/only-in-c'
			'*/only-in-c'       | 'c/only-in-c'
			'%/%/deep'          | 'c/inner/deep'
			'%/in-b-and-c'      | 'b/in-b-and-c'
			'%/nowhere'         | null
	}

}
