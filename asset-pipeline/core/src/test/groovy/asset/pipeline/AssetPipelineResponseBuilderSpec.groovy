package asset.pipeline;

import spock.lang.Specification
import spock.lang.Unroll

public class AssetPipelineResponseBuilderSpec extends Specification {

    Properties originalManifest

    void setup() {
        originalManifest = AssetPipelineConfigHolder.manifest
    }

    void cleanup() {
        AssetPipelineConfigHolder.manifest = originalManifest
    }

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
    def "304 responses for #uri retain the cache headers of a 200 response"() {
        given:
        Properties manifest = new Properties()
        manifest.setProperty('app.js', 'app-2222.js')
        manifest.setProperty('index.html', 'index-2222.html')
        AssetPipelineConfigHolder.manifest = manifest
        Date modified = new Date(1700000000000L)

        when:
        def initial = new AssetPipelineResponseBuilder(uri, null, null, modified)
        def byETag = new AssetPipelineResponseBuilder(uri, etag, null, modified)
        def byDate = new AssetPipelineResponseBuilder(uri, null, 'Wed, 15 Nov 2023 22:13:20 GMT', modified)

        then:
        initial.statusCode == 200
        initial.headers == ['ETag': etag, 'Last-Modified': 'Tue, 14 Nov 2023 22:13:20 GMT',
                            'Vary': 'Accept-Encoding', 'Cache-Control': cacheControl]
        byETag.statusCode == 304
        byETag.headers == initial.headers
        byDate.statusCode == 304
        byDate.headers == initial.headers

        where:
        uri                | etag                | cacheControl
        'app.js'           | '"app-2222.js"'     | 'no-cache'
        '/app.js'          | '"app-2222.js"'     | 'no-cache'
        'app-2222.js'      | '"app-2222.js"'     | 'public, max-age=31536000'
        '/app-2222.js'     | '"app-2222.js"'     | 'public, max-age=31536000'
        'index.html'       | '"index-2222.html"' | 'no-cache'
        '/index.html'      | '"index-2222.html"' | 'no-cache'
        'index-2222.html'  | '"index-2222.html"' | 'no-cache'
        '/index-2222.html' | '"index-2222.html"' | 'no-cache'
    }

    def "ETag validation retains cache headers when no last-modified date is available"() {
        given:
        Properties manifest = new Properties()
        manifest.setProperty('app.js', 'app-2222.js')
        AssetPipelineConfigHolder.manifest = manifest

        when:
        def response = new AssetPipelineResponseBuilder('app.js', '"app-2222.js"')

        then:
        response.statusCode == 304
        response.headers == ['ETag': '"app-2222.js"', 'Vary': 'Accept-Encoding', 'Cache-Control': 'no-cache']
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

}
