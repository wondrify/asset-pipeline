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

package asset.pipeline.grails

import asset.pipeline.AssetPipelineConfigHolder
import spock.lang.Specification

class AssetProcessorServiceSpec extends Specification {

	def cleanup() {
		AssetPipelineConfigHolder.manifest = null
	}

	void "a wildcard path picks from the manifest the file a resolver picks in development: #path"() {
		given:
			AssetPipelineConfigHolder.manifest = new Properties()
			['webjars/marked/4.3.0/lib/marked.js', 'webjars/marked/5.1.2/lib/marked.js', 'webjars/chart/9.0.0/chart.js',
			 'webjars/chart/10.0.0/chart.js', 'webjars/deep/1.0/nested/lib/x.js', 'vendor/.backup/lib.js'].each {
				AssetPipelineConfigHolder.manifest.setProperty(it, it.replaceFirst(/\.js$/, '-digest.js'))
			}
		expect:
			new AssetProcessorService().getAssetPath(path, [:], true) == resolved
		where:
			path                            | resolved
			'webjars/marked/%/lib/marked.js' | 'webjars/marked/5.1.2/lib/marked-digest.js'
			'webjars/chart/*/chart.js'      | 'webjars/chart/10.0.0/chart-digest.js'
			'%/marked/%/lib/marked.js'      | 'webjars/marked/5.1.2/lib/marked-digest.js'
			'webjars/deep/%/nested/lib/x.js' | 'webjars/deep/1.0/nested/lib/x-digest.js'
			'webjars/deep/%/lib/x.js'       | 'webjars/deep/%/lib/x.js'
			'vendor/%/lib.js'               | 'vendor/%/lib.js'
	}

	void "%% picks from the manifest what a resolver picks: the fewest directories, then the highest version: #path"() {
		given:
			AssetPipelineConfigHolder.manifest = new Properties()
			['webjars/jquery/3.7.1/dist/jquery.js', 'webjars/other/1.0/vendor/jquery/dist/jquery.js',
				'webjars/marked/4.3.0/lib/marked.js', 'webjars/marked/5.1.2/lib/marked.js', 'webjars/nest/a/b/c/deep.js',
				'webjars/.cache/x/lib/hidden.js'].each {
				AssetPipelineConfigHolder.manifest.setProperty(it, it.replaceFirst(/\.js$/, '-digest.js'))
			}
		expect:
			new AssetProcessorService().getAssetPath(path, [:], true) == resolved
		where:
			path                                     | resolved
			'webjars/jquery/3.7.1/%%/dist/jquery.js' | 'webjars/jquery/3.7.1/dist/jquery-digest.js'
			'webjars/jquery/%%/dist/jquery.js'       | 'webjars/jquery/3.7.1/dist/jquery-digest.js'
			'webjars/%%/dist/jquery.js'              | 'webjars/jquery/3.7.1/dist/jquery-digest.js'
			'webjars/**/dist/jquery.js'              | 'webjars/jquery/3.7.1/dist/jquery-digest.js'
			'%%/jquery.js'                           | 'webjars/jquery/3.7.1/dist/jquery-digest.js'
			'webjars/%%/lib/marked.js'               | 'webjars/marked/5.1.2/lib/marked-digest.js'
			'webjars/%%/deep.js'                     | 'webjars/nest/a/b/c/deep-digest.js'
			'webjars/%%/hidden.js'                   | 'webjars/%%/hidden.js'
			'webjars/%/dist/jquery.js'               | 'webjars/%/dist/jquery.js'
			'/webjars/%%/lib/marked.js'              | 'webjars/marked/5.1.2/lib/marked-digest.js'
	}

	void "a wildcard path with no match in the manifest is looked up once"() {
		given: 'a manifest with something in it, since an empty one reads as none'
			AssetPipelineConfigHolder.manifest = new Properties()
			AssetPipelineConfigHolder.manifest.setProperty('app.js', 'app-digest.js')
			AssetProcessorService service = new AssetProcessorService()
		when: 'a file that would match is added after the first lookup missed'
			String first = service.getAssetPath('webjars/marked/%/lib/marked.js', [:], true)
			AssetPipelineConfigHolder.manifest.setProperty('webjars/marked/5.1.2/lib/marked.js', 'webjars/marked/5.1.2/lib/marked-digest.js')
		then: 'the miss was kept, as a hit is'
			first == 'webjars/marked/%/lib/marked.js'
			service.getAssetPath('webjars/marked/%/lib/marked.js', [:], true) == first
	}
}
