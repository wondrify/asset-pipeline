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


import asset.pipeline.JsEs6AssetFile
import spock.lang.Specification
import spock.lang.TempDir

/**
* @author David Estes
*/
class JarAssetResolverSpec extends Specification {

	@TempDir
	File tempDir

	void "should be able to fetch files from a jar file"() {
		given:
			def resolver = new JarAssetResolver('application','lib/test-lib.zip','META-INF/assets')
		when:
			def file = resolver.getAsset('jartest','application/javascript')
		then:
			println file?.inputStream?.text
			file instanceof JsEs6AssetFile
	}

	void "should be able to fetch files from a jar file if root path given"() {
		given:
			def resolver = new JarAssetResolver('application','lib/test-lib.zip','META-INF/assets')
		when:
			def file = resolver.getAsset('/jartest','application/javascript')
		then:
			println file?.inputStream?.text
			file instanceof JsEs6AssetFile
	}


	void "should load exact directory and not all directories with the same prefixes"() {
		given:
			def resolver = new JarAssetResolver('application','lib/test-lib.zip','META-INF/assets')
		when:
			def files = resolver.getAssets('jquery','application/javascript')
		then:
            files.name == ['jquery.js']
	}

	void "should prefer .js file over .mjs file when explicitly requesting .js extension from jar"() {
		given:
		def resolver = new JarAssetResolver('application','lib/test-lib.zip','META-INF/assets')

		when:
		def file = resolver.getAsset('jartest.js', 'application/javascript')

		then:
		file != null
		file.name == 'jartest.js'
		file.path.endsWith('.js')
	}

	void "should prefer .mjs file over .js file when explicitly requesting .mjs extension from jar"() {
		given:
		def resolver = new JarAssetResolver('application','lib/test-lib.zip','META-INF/assets')

		when:
		def file = resolver.getAsset('jartest.mjs', 'application/javascript')

		then:
		file != null
		file.name == 'jartest.mjs'
		file.path.endsWith('.mjs')
	}

	void "should prefer .mjs file over .js when no extension is specified"() {
		given:
		def resolver = new JarAssetResolver('application','lib/test-lib.zip','META-INF/assets')

		when:
		def jsFile = resolver.getAsset('jartest', 'application/javascript')

		then:
		jsFile != null
		jsFile instanceof JsEs6AssetFile
		jsFile.name == 'jartest.mjs'
		jsFile.path.endsWith('.mjs')
	}

	void "a wildcard stands for exactly one directory, highest version first, never a hidden one: #path"() {
		given:
			def resolver = new JarAssetResolver('application', TestJars.write(new File(tempDir, 'webjars.jar'), [
				'webjars/marked/4.3.0/lib/marked.js', 'webjars/marked/5.1.2/lib/marked.js',
				'webjars/chart/9.0.0/chart.js', 'webjars/chart/10.0.0/chart.js',
				'webjars/deep/1.0/nested/lib/x.js', 'vendor/.backup/only-in-backup.js', 'literal/%/in-percent.js']).path, 'META-INF/resources')
		when:
			def file = resolver.getAsset(path, 'application/javascript', 'js')
		then:
			file?.path == resolved
		where:
			path                            | resolved
			'webjars/marked/%/lib/marked'   | 'webjars/marked/5.1.2/lib/marked.js'
			'webjars/chart/%/chart'         | 'webjars/chart/10.0.0/chart.js'
			'%/marked/%/lib/marked'         | 'webjars/marked/5.1.2/lib/marked.js'
			'webjars/%/%/lib/marked'        | 'webjars/marked/5.1.2/lib/marked.js'
			'webjars/deep/%/lib/x'          | null
			'webjars/deep/%/nested/lib/x'   | 'webjars/deep/1.0/nested/lib/x.js'
			'vendor/%/only-in-backup'       | null
			'literal/%/in-percent'          | 'literal/%/in-percent.js'
			'literal/%/nowhere'             | null
	}

	void "%% stands for any number of directories in a jar, the fewest first, then the highest version: #path"() {
		given:
			def resolver = new JarAssetResolver('application', TestJars.write(new File(tempDir, 'webjars.jar'), [
				'webjars/jquery/3.7.1/dist/jquery.js', 'webjars/other/1.0/vendor/jquery/dist/jquery.js',
				'webjars/marked/4.3.0/lib/marked.js', 'webjars/marked/5.1.2/lib/marked.js', 'webjars/nest/a/b/c/deep.js',
				'webjars/.cache/x/lib/hidden.js']).path, 'META-INF/resources')
		when:
			def file = resolver.getAsset(path, 'application/javascript', 'js')
		then:
			file?.path == resolved
		where:
			path                                  | resolved
			'webjars/jquery/3.7.1/%%/dist/jquery' | 'webjars/jquery/3.7.1/dist/jquery.js'
			'webjars/jquery/%%/dist/jquery'       | 'webjars/jquery/3.7.1/dist/jquery.js'
			'webjars/%%/dist/jquery'              | 'webjars/jquery/3.7.1/dist/jquery.js'
			'webjars/**/dist/jquery'              | 'webjars/jquery/3.7.1/dist/jquery.js'
			'%%/jquery'                           | 'webjars/jquery/3.7.1/dist/jquery.js'
			'webjars/%%/lib/marked'               | 'webjars/marked/5.1.2/lib/marked.js'
			'webjars/%%/deep'                     | 'webjars/nest/a/b/c/deep.js'
			'webjars/%%/hidden'                   | null
			'webjars/%/dist/jquery'               | null
			'/webjars/%%/lib/marked'              | 'webjars/marked/5.1.2/lib/marked.js'
	}

	void "a wildcard resolves in a jar that has no entries for its directories"() {
		given:
			def resolver = new JarAssetResolver('application', TestJars.write(new File(tempDir, 'no-directories.jar'), ['webjars/marked/5.1.2/lib/marked.js'], false).path, 'META-INF/resources')
		expect:
			resolver.getAsset('webjars/marked/%/lib/marked', 'application/javascript', 'js')?.path == 'webjars/marked/5.1.2/lib/marked.js'
	}

	void "a wildcard as the last component stands for a directory, so it names no entry"() {
		given:
			def resolver = new JarAssetResolver('application', TestJars.write(new File(tempDir, 'webjars.jar'), ['webjars/marked/5.1.2/lib/marked.js']).path, 'META-INF/resources')
		expect:
			resolver.getRelativeFile('META-INF/resources', 'webjars/marked/%') == null
	}

}
