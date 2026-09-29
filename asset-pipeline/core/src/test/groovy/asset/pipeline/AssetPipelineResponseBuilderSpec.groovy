package asset.pipeline;

import spock.lang.Specification
import spock.lang.Unroll

public class AssetPipelineResponseBuilderSpec extends Specification {

    @Unroll
    def "make sure etag is quoted for #filename"() {
        given:
        Properties props = new Properties()
        props.setProperty("global.js", "global-1d9c55a6d7ec00ec71a5aee2b8749d28.js")
        AssetPipelineConfigHolder.setManifest(props)
        AssetPipelineResponseBuilder aprb = new AssetPipelineResponseBuilder(filename)

        when:
        aprb.checkETag()

        then:
        aprb.currentETag == "\"global-1d9c55a6d7ec00ec71a5aee2b8749d28.js\""
        aprb.headers.get('ETag') == "\"global-1d9c55a6d7ec00ec71a5aee2b8749d28.js\""

        where:
        filename << ['global.js', '/global.js']
    }

    @Unroll
    def "when etag is missing from manifest default to file for #filename"() {
        given:
        Properties props = new Properties()
        props.setProperty("global.js", "global-1d9c55a6d7ec00ec71a5aee2b8749d28.js")
        AssetPipelineConfigHolder.setManifest(props)
        AssetPipelineResponseBuilder aprb = new AssetPipelineResponseBuilder(filename)

        when:
        aprb.checkETag()

        then:
        aprb.currentETag == "\"header.js\""
        aprb.headers.get('ETag') == "\"header.js\""

        where:
        filename << ['header.js', '/header.js']
    }

    @Unroll
    def "make sure etag is set for 304 reponse"() {
        given:
        Properties props = new Properties()
        props.setProperty("global.js", "global-1d9c55a6d7ec00ec71a5aee2b8749d28.js")
        AssetPipelineConfigHolder.setManifest(props)
        AssetPipelineResponseBuilder aprb = new AssetPipelineResponseBuilder(filename)

        aprb.ifNoneMatchHeader = "\"global-1d9c55a6d7ec00ec71a5aee2b8749d28.js\""
        aprb.headers = [:]
        aprb.statusCode = 200

        when:
        def result = aprb.checkETag()

        then:
        aprb.headers.get('ETag') == "\"global-1d9c55a6d7ec00ec71a5aee2b8749d28.js\""
        aprb.statusCode == 304
        result == false

        where:
        filename << ['global.js', '/global.js']
    }

}
