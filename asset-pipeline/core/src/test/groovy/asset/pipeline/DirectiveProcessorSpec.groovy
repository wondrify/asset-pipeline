package asset.pipeline

import spock.lang.Specification

/**
 * @author Craig Burke
 */
class DirectiveProcessorSpec extends Specification {
    
    def "should be able to load matched directive options from a js file"() {
        DirectiveProcessor directiveProcessor = new DirectiveProcessor('text/javascript')
        String fileContent = """\
            |//= foo
            |//= wrapped
            |//= camelCase
            |//= encoding UTF-8
            |//= bar\
         """.stripMargin()
        
        AssetFile assetFile = new JsAssetFile(inputStreamSource: { new ByteArrayInputStream(fileContent.bytes) } )

        when:
        directiveProcessor.compile(assetFile)
        
        then:
        assetFile.matchedDirectives == ['foo', 'wrapped', 'camelCase', 'bar']
    }

    def "should be able to load matched directive options from a css file"() {
        DirectiveProcessor directiveProcessor = new DirectiveProcessor('text/css')
        String fileContent = """\
            |/*
            |*= foo
            |*= wrapped
            |*= camelCase
            |*= encoding UTF-8
            |*= bar
            |*/\
         """.stripMargin()

        AssetFile assetFile = new CssAssetFile(inputStreamSource: { new ByteArrayInputStream(fileContent.bytes) } )

        when:
        directiveProcessor.compile(assetFile)

        then:
        assetFile.matchedDirectives == ['foo', 'wrapped', 'camelCase', 'bar']
    }
    
}
