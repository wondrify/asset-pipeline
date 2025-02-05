/*
* Copyright 2014-2025 the original author or authors.
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

package asset.pipeline.dart

import asset.pipeline.AssetHelper
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.fs.ClasspathAssetResolver
import asset.pipeline.fs.FileSystemAssetResolver
import spock.lang.Specification

/**
* @author David Estes
*/
class SassProcessorSpec extends Specification {

	void setup() {
		AssetPipelineConfigHolder.config = [sass: [quietDeps: true]]
	}

	void "should compile sass into css"() {
		given:
		AssetPipelineConfigHolder.resolvers = []
		AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('test','assets'))
		def assetFile = AssetHelper.fileForFullName('test.scss')
		def processor = new SassProcessor()
		when:
		def output = processor.process(assetFile.inputStream.text,assetFile)
		then:
		output.contains('margin')
		output.readLines().size() > 1
	}

	void "should compile sass into css on a single line using compressed option"() {
		given:
		AssetPipelineConfigHolder.resolvers = []
		AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('test','assets'))
		AssetPipelineConfigHolder.config = [sass: [quietDeps: true, outputStyle: 'compressed']]
		def assetFile = AssetHelper.fileForFullName('test.scss')
		def processor = new SassProcessor()
		when:
		def output = processor.process(assetFile.inputStream.text,assetFile)
		then:
		output.contains('margin')
		output.readLines().size() == 1
	}

	void "should compile nested sass into css"() {
		given:
		AssetPipelineConfigHolder.resolvers = []
		AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('test','assets'))
		def assetFile = AssetHelper.fileForFullName('partials/forms.scss')
		def processor = new SassProcessor()
		when:
		def output = processor.process(assetFile.inputStream.text,assetFile)
		then:
		output.contains('.sub')
	}

	void "should compile nested foo into css"() {
		given:
		AssetPipelineConfigHolder.resolvers = []
		AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('test','assets'))
		def assetFile = AssetHelper.fileForFullName('foo/foo.scss')
		def processor = new SassProcessor()
		when:
		def output = processor.process(assetFile.inputStream.text,assetFile)
		then:
		output.contains('.bar')
	}

	void "should compile Bourbon"() {
		given:
		AssetPipelineConfigHolder.resolvers = []
		AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('test', 'assets'))
		def assetFile = AssetHelper.fileForFullName('bourbon-test.scss')
		def processor = new SassProcessor()
		when:
		def output = processor.process(assetFile.inputStream.text, assetFile)
		then:
		output.length() > 0
	}

	void "should compile Bootstrap v4"() {
		given:
		AssetPipelineConfigHolder.resolvers = []
		AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('test', 'assets'))
		def assetFile = AssetHelper.fileForFullName('bootstrap/bootstrap.scss')
		def processor = new SassProcessor()
		when:
		def output = processor.process(assetFile.inputStream.text, assetFile)
		then:
		output.contains('Twitter')
	}

	void "should compile absolute imports"() {
		given:
		AssetPipelineConfigHolder.resolvers = []
		AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('test','assets'))
		def assetFile = AssetHelper.fileForFullName('absolute-import/main.scss')
		def processor = new SassProcessor()
		when:
		def output = processor.process(assetFile.inputStream.text,assetFile)
		then:
		output.contains('Twitter')
    }

	void "should compile webjar imports"() {
		given:
		AssetPipelineConfigHolder.resolvers = []
		AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('test','assets'))
		AssetPipelineConfigHolder.registerResolver(new ClasspathAssetResolver('classpath','META-INF/resources'))
		def assetFile = AssetHelper.fileForFullName('webjar-import/main.scss')
		def processor = new SassProcessor()
		when:
		def output = processor.process(assetFile.inputStream.text,assetFile)
		then:
		output.contains('https://getbootstrap.com')
	}
}
