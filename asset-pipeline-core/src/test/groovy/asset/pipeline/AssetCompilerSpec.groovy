/*
* Copyright 2026 the original author or authors.
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

class AssetCompilerSpec extends Specification {

	private static final Map DEFAULTS = [compileDir: 'target/assets', enableGzip: true, enableDigests: true, skipNonDigests: false]

	void "the option defaults apply whatever options the compiler is given: #description"() {
		when:
			AssetCompiler compiler = options == 'none' ? new AssetCompiler() : new AssetCompiler(options as Map)
		then:
			compiler.options.subMap(DEFAULTS.keySet()) == DEFAULTS
			compiler.options.excludesGzip.containsAll(['png', 'jpg', 'jpeg', 'gif', 'zip', 'gz'])
		where:
			description             | options
			'no argument'           | 'none'
			'an empty map'          | [:]
			'null'                  | null
			'an unrelated option'   | [minifyJs: false]
	}

	void "the defaults go into the caller's map, and what it sets is kept"() {
		given:
			Map options = [enableDigests: false, compileDir: 'build/assets', excludesGzip: ['svg']]
		when:
			AssetCompiler compiler = new AssetCompiler(options)
		then:
			compiler.options.is(options)
			options.enableDigests == false
			options.compileDir == 'build/assets'
			options.enableGzip == true
			options.excludesGzip.containsAll(['svg', 'png'])
	}

	void "an empty map the caller passes is the one the compiler keeps"() {
		given:
			Map options = [:]
		when:
			AssetCompiler compiler = new AssetCompiler(options)
		then:
			compiler.options.is(options)
			options.enableDigests == true
	}
}
