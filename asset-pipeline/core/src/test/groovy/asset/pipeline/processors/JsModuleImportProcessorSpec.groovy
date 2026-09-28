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

package asset.pipeline.processors

import asset.pipeline.AssetCompiler
import asset.pipeline.AssetHelper
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.DirectiveProcessor
import asset.pipeline.fs.FileSystemAssetResolver
import spock.lang.Specification
import spock.lang.TempDir

class JsModuleImportProcessorSpec extends Specification {

	@TempDir
	File compileDir

	FileSystemAssetResolver resolver

	def setup() {
		resolver = new FileSystemAssetResolver('application', 'assets')
		AssetPipelineConfigHolder.resolvers = []
		AssetPipelineConfigHolder.registerResolver(resolver)
		AssetPipelineConfigHolder.config = [:]
	}

	void "relative imports name the digested file of the module they import"() {
		given:
		AssetCompiler compiler = new AssetCompiler([enableDigests: true])

		when:
		String main = compile('asset-pipeline/test/esm/main', compiler)
		String math = compile('asset-pipeline/test/esm/lib/math', compiler)

		then: 'every form of relative import is rewritten, in the quotes it was written with'
		main.contains("import { twice } from './lib/math-${digest('asset-pipeline/test/esm/lib/math')}.js'")
		main.contains("} from \"./greet-${digest('asset-pipeline/test/esm/greet')}.js\"")
		main.contains("import './side-effect-${digest('asset-pipeline/test/esm/side-effect')}.js'")
		main.contains("export * from './lib/math-${digest('asset-pipeline/test/esm/lib/math')}.js'")
		main.contains("from '../esm-shared/shared-${digest('asset-pipeline/test/esm-shared/shared')}.js?v=1'")
		main.contains("import('./lazy-${digest('asset-pipeline/test/esm/lazy')}.js')")

		and: 'an imported module is digested with its own imports rewritten'
		math.contains("from '../greet-${digest('asset-pipeline/test/esm/greet')}.js'")

		and: 'bare specifiers, URLs, non-assets and member calls named import are left alone'
		main.contains("from 'some-package'")
		main.contains("from 'https://example.com/remote.js'")
		main.contains("from './missing.js'")
		main.contains("loader.import('./greet.js')")
	}

	void "a compile that writes only digested names leaves every rewritten import pointing at a file it wrote"() {
		given: 'only digested files, as in a CDN bucket synced from the compile directory'
		AssetCompiler compiler = new AssetCompiler([compileDir: compileDir.path, skipNonDigests: true, enableGzip: false, minifyJs: false])
		compiler.excludeRules['default'] = ['**/*']
		compiler.includeRules['default'] = ['asset-pipeline/test/esm/**', 'asset-pipeline/test/esm-shared/**']

		when:
		compiler.compile()
		Properties manifest = new Properties()
		new File(compileDir, 'manifest.properties').withInputStream { manifest.load(it) }
		File main = new File(compileDir, manifest.getProperty('asset-pipeline/test/esm/main.js'))
		File math = new File(compileDir, manifest.getProperty('asset-pipeline/test/esm/lib/math.js'))

		then:
		!new File(compileDir, 'asset-pipeline/test/esm/greet.js').exists()
		unresolvedSpecifiers(main) == ['./missing.js', './greet.js'] as Set
		unresolvedSpecifiers(math).isEmpty()
	}

	void "imports in a cycle keep their plain names, whichever module compiles first"() {
		when:
		AssetCompiler aFirst = new AssetCompiler([enableDigests: true])
		String a1 = compile('asset-pipeline/test/esm-cycle/a', aFirst)
		String b1 = compile('asset-pipeline/test/esm-cycle/b', aFirst)

		AssetCompiler bFirst = new AssetCompiler([enableDigests: true])
		String b2 = compile('asset-pipeline/test/esm-cycle/b', bFirst)
		String a2 = compile('asset-pipeline/test/esm-cycle/a', bFirst)

		then:
		a1.contains("from './b.js'")
		b1.contains("from './a.js'")

		and: 'an import out of the cycle is still rewritten'
		a1.contains("from './leaf-${digest('asset-pipeline/test/esm-cycle/leaf')}.js'")

		and:
		a1 == a2
		b1 == b2
	}

	void "a cycle through a file bundled into a module, or through asset_url(), keeps the import's plain name: #dir"() {
		when:
		AssetCompiler aFirst = new AssetCompiler([enableDigests: true])
		String a1 = compile("asset-pipeline/test/${dir}/a", aFirst)
		String b1 = compile("asset-pipeline/test/${dir}/b", aFirst)

		AssetCompiler bFirst = new AssetCompiler([enableDigests: true])
		String b2 = compile("asset-pipeline/test/${dir}/b", bFirst)
		String a2 = compile("asset-pipeline/test/${dir}/a", bFirst)

		then:
		a1.contains("from './b.js'")
		b1.contains(importOfA)

		and:
		a1 == a2
		b1 == b2

		where:
		dir                            | importOfA
		'esm-directive-cycle'          | "from './a.js'"
		'esm-commonjs-cycle'           | "import('./a.js')"
		'esm-require-first-line-cycle' | "import('./a.js')"
		'esm-babel-cycle'              | "import('./a.js')"
		'esm-url-import-cycle'         | "'/assets/asset-pipeline/test/esm-url-import-cycle/a-"
	}

	void "an asset whose digest depends on its own fails the compile, naming the cycle"() {
		when:
		compile('asset-pipeline/test/url-cycle/a', new AssetCompiler([enableDigests: true]))

		then:
		IllegalStateException e = thrown()
		e.message.contains('asset-pipeline/test/url-cycle/b.js -> asset-pipeline/test/url-cycle/a.js -> asset-pipeline/test/url-cycle/b.js')
	}

	void "a file that requires CommonJS modules keeps them when it also imports a module: #path"() {
		when:
		String withDigests = compile(path, new AssetCompiler([enableDigests: true]))
		String withoutDigests = compile(path, new AssetCompiler([enableDigests: false]))

		then: 'the import is the only difference digests make, so the require runtime and every module survive'
		withoutDigests.startsWith(JsRequireProcessor.requireMethod)
		withoutDigests.contains("_asset_pipeline_modules['asset-pipeline/test/esm-commonjs/helper.js']")
		withDigests == withoutDigests.replace("import('./lazy.js')", "import('./lazy-${digest('asset-pipeline/test/esm-commonjs/lazy')}.js')")

		where:
		path << ['asset-pipeline/test/esm-commonjs/app', 'asset-pipeline/test/esm-commonjs/bundle']
	}

	void "outside a digest compile the imports are left alone"() {
		expect:
		[null, new AssetCompiler([enableDigests: false])].every { AssetCompiler compiler ->
			String main = resolver.getAsset('asset-pipeline/test/esm/main', 'application/javascript', 'js').processedStream(compiler)
			main.contains("from './lib/math.js'") && main.contains("import('./lazy.js')")
		}
	}

	void "a module Babel bundles into require() calls is left to the bundler"() {
		when:
		String entry = compile('asset-pipeline/test/esm-babel/entry', new AssetCompiler([enableDigests: true]), 'js.es6')

		then: 'the imported module is inlined, not referenced by a digested name'
		entry.contains('hello ')
		!entry.contains("greet-${digest('asset-pipeline/test/esm/greet')}")
	}

	private String compile(String path, AssetCompiler compiler, String extension = 'js') {
		new DirectiveProcessor('application/javascript', compiler).compile(resolver.getAsset(path, 'application/javascript', extension))
	}

	// The digest the compiler names the file with, from a compile of its own
	private String digest(String path) {
		AssetHelper.getByteDigest(compile(path, new AssetCompiler([enableDigests: true])).bytes)
	}

	private static Set<String> unresolvedSpecifiers(File module) {
		(module.text =~ /['"](\.{1,2}\/[^'"]+)['"]/).collect { it[1] as String }
			.findAll { !new File(module.parentFile, it.replaceFirst(/[?#].*/, '')).exists() } as Set
	}
}
