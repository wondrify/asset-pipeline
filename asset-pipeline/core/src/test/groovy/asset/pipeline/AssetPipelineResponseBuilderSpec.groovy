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

    @Unroll
    def "Accept-Encoding #acceptEncoding accepts gzip"() {
        expect:
        AssetPipelineResponseBuilder.acceptsGzip(acceptEncoding)

        where:
        acceptEncoding << ['gzip', 'GZIP', 'gzip, deflate', 'br, gzip', 'deflate,gzip', 'deflate,\tgzip',
                           'gzip;q=1.0, identity;q=0.5', 'br, gzip ; q=0.5', 'gzip;q = 0.5', 'gzip;Q=1', 'gzip;q=0.001',
                           'gzip;q=1.000', 'gzip;level=9', 'x-gzip', '*', 'br, *;q=0.1', 'gzip, *;q=0']
    }

    @Unroll
    def "Accept-Encoding #acceptEncoding does not accept gzip"() {
        expect:
        !AssetPipelineResponseBuilder.acceptsGzip(acceptEncoding)

        where:
        acceptEncoding << [null, '', 'identity', 'br, deflate', 'gzipped', 'gzip;q=0', 'gzip;q=0.000', 'GZIP; Q=0',
                           'gzip;q =0', 'gzip; q = 0', '*;q=0', 'gzip;q=0, *', 'gzip;q=none', 'gzip;q=', 'gzip;q',
                           'gzip;q=NaN', '*;q=NaN', 'gzip;q=Infinity', 'gzip;q=1e0', 'gzip;q=1.5', 'gzip;q=-1',
                           'gzip, gzip;q=0', 'gzip;q=0, gzip', 'x-gzip;q=0, gzip']
    }

    @Unroll
    def "Accept-Encoding sent as #lines accepts gzip: #accepted"() {
        expect:
        AssetPipelineResponseBuilder.acceptsGzip(lines == null ? null : Collections.enumeration(lines)) == accepted

        where: 'a request may send the field more than once'
        lines                   | accepted
        ['br', 'gzip']          | true
        ['gzip', 'gzip;q=0']    | false
        ['br', 'deflate']       | false
        []                      | false
        null                    | false
    }

    @Unroll
    def "#uri, with #manifestGiven, is sent #cacheControl"() {
        given:
        Properties props = new Properties()
        props.setProperty('app.js', 'app-0123456789abcdef.js')
        AssetPipelineConfigHolder.manifest = manifestGiven == 'a manifest' ? props : null

        expect: 'only a digested name the manifest gives is cached for a year; any other may change'
        new AssetPipelineResponseBuilder(uri).headers['Cache-Control'] == cacheControl

        cleanup:
        AssetPipelineConfigHolder.manifest = null

        where:
        uri                        | manifestGiven | cacheControl
        'app-0123456789abcdef.js'  | 'a manifest'  | 'public, max-age=31536000'
        '/app-0123456789abcdef.js' | 'a manifest'  | 'public, max-age=31536000'
        'app.js'                   | 'a manifest'  | 'no-cache'
        'other.js'                 | 'a manifest'  | 'no-cache'
        'app-0123456789abcdef.js'  | 'no manifest' | 'no-cache'
    }

    def "a builder given a manifest of its own reads that one, as for a class loader registered with its own assets"() {
        given:
        AssetPipelineConfigHolder.manifest = null
        Properties own = new Properties()
        own.setProperty('lib.js', 'lib-0123456789abcdef.js')

        expect:
        new AssetPipelineResponseBuilder('lib-0123456789abcdef.js', null, null, null, own).headers['Cache-Control'] == 'public, max-age=31536000'
        new AssetPipelineResponseBuilder('lib.js', null, null, null, own).headers['ETag'] == '"lib-0123456789abcdef.js"'
    }

    def "a manifest that gains an entry after a request is read again"() {
        given: 'a manifest a builder has already read'
        Properties props = new Properties()
        props.setProperty('a.js', 'a-0123456789abcdef.js')
        AssetPipelineConfigHolder.manifest = props
        new AssetPipelineResponseBuilder('a-0123456789abcdef.js')

        when:
        props.setProperty('b.js', 'b-0123456789abcdef.js')

        then:
        new AssetPipelineResponseBuilder('b-0123456789abcdef.js').headers['Cache-Control'] == 'public, max-age=31536000'

        cleanup:
        AssetPipelineConfigHolder.manifest = null
    }
}
