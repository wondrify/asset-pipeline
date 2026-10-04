package asset.pipeline;

import spock.lang.Specification
import spock.lang.Unroll

public class AssetPipelineResponseBuilderSpec extends Specification {

    Properties originalManifest
    Map originalConfig

    void setup() {
        originalManifest = AssetPipelineConfigHolder.manifest
        originalConfig = AssetPipelineConfigHolder.config
    }

    void cleanup() {
        AssetPipelineConfigHolder.manifest = originalManifest
        AssetPipelineConfigHolder.config = originalConfig
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
        AssetPipelineConfigHolder.config = [immutable: ['vendor/**']]
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
        'vendor/lib.js'    | '"vendor/lib.js"'   | 'public, max-age=31536000'
        '/vendor/lib.js'   | '"vendor/lib.js"'   | 'public, max-age=31536000'
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

    @Unroll
    def "#uri, which immutable #matching, is sent #cacheControl though there is no manifest"() {
        given:
        Map originalConfig = AssetPipelineConfigHolder.config
        AssetPipelineConfigHolder.manifest = null
        AssetPipelineConfigHolder.config = [immutable: ['webjars/**']]

        expect:
        new AssetPipelineResponseBuilder(uri).headers['Cache-Control'] == cacheControl

        cleanup:
        AssetPipelineConfigHolder.config = originalConfig

        where:
        uri                               | matching          | cacheControl
        'webjars/jquery/3.7.1/jquery.js'  | 'matches'         | 'public, max-age=31536000'
        '/webjars/jquery/3.7.1/jquery.js' | 'matches'         | 'public, max-age=31536000'
        'app.js'                          | 'does not match'  | 'no-cache'
    }

    def "a configuration given anew is read anew"() {
        given:
        Map originalConfig = AssetPipelineConfigHolder.config
        AssetPipelineConfigHolder.manifest = null

        when:
        AssetPipelineConfigHolder.config = [immutable: ['a.js']]
        String first = new AssetPipelineResponseBuilder('a.js').headers['Cache-Control']
        AssetPipelineConfigHolder.config = [immutable: ['b.js']]

        then:
        first == 'public, max-age=31536000'
        new AssetPipelineResponseBuilder('a.js').headers['Cache-Control'] == 'no-cache'
        new AssetPipelineResponseBuilder('b.js').headers['Cache-Control'] == 'public, max-age=31536000'

        cleanup:
        AssetPipelineConfigHolder.config = originalConfig
    }
}
