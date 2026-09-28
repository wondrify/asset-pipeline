package asset.pipeline.processors

import asset.pipeline.AssetCompiler
import asset.pipeline.AssetPipelineConfigHolder
import spock.lang.Specification

class JsNodeInjectProcessorSpec extends Specification {

    def cleanup() {
        AssetPipelineConfigHolder.config?.remove('nodeEnv')
    }

    void "injects the process shim by default"() {
        given:
        def processor = new JsNodeInjectProcessor(new AssetCompiler([:]))
        when:
        def result = processor.process("var a = 1;", null)
        then:
        result.startsWith('var process = process || {env: {NODE_ENV: "development"}};')
    }

    void "honours a configured nodeEnv value"() {
        given:
        AssetPipelineConfigHolder.config = (AssetPipelineConfigHolder.config ?: [:]) + [nodeEnv: 'production']
        def processor = new JsNodeInjectProcessor(new AssetCompiler([:]))
        when:
        def result = processor.process("var a = 1;", null)
        then:
        result.startsWith('var process = process || {env: {NODE_ENV: "production"}};')
    }

    void "nodeEnv:false omits the shim entirely"() {
        given:
        AssetPipelineConfigHolder.config = (AssetPipelineConfigHolder.config ?: [:]) + [nodeEnv: false]
        def processor = new JsNodeInjectProcessor(new AssetCompiler([:]))
        when:
        def result = processor.process("var a = 1;", null)
        then:
        result == "var a = 1;"
        !result.contains('NODE_ENV')
    }
}
