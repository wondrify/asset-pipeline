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

import asset.pipeline.grails.AssetAttributes
import asset.pipeline.grails.AssetProcessorService
import asset.pipeline.grails.ProductionAssetCache
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.support.GenericWebApplicationContext
import spock.lang.Specification
import spock.lang.TempDir

import java.util.concurrent.ConcurrentMap

/**
 * The record a compiled application's filter keeps of each url it has looked up. Each feature
 * creates its own filter, and so starts with an empty cache of its own.
 */
class AssetPipelineFilterCacheSpec extends Specification {

    static final byte[] FAVICON = [0, 0, 1, 0, 1, 0] as byte[]
    static final byte[] FAVICON_GZIPPED = [31, -117, 8, 0] as byte[]
    static final String DIGESTED = 'favicon-0123456789abcdef.ico'
    static final AssetAttributes FOUND = new AssetAttributes(true, false, false, 1L, null, null, null, null)

    @TempDir
    File root

    File assets
    List<GenericWebApplicationContext> applicationContexts = []

    void setup() {
        AssetPipelineConfigHolder.config = [:]
        assets = new File(root, 'assets')
        assets.mkdirs()
        new File(assets, DIGESTED).bytes = FAVICON
        new File(assets, "${DIGESTED}.gz").bytes = FAVICON_GZIPPED
        Properties manifest = new Properties()
        manifest.setProperty('favicon.ico', DIGESTED)
        AssetPipelineConfigHolder.manifest = manifest
    }

    void cleanup() {
        applicationContexts*.close()
        AssetPipelineConfigHolder.config = [:]
        AssetPipelineConfigHolder.manifest = null
    }

    void 'the cache holds no more assets than its bound, and no more missing urls'() {
        given: 'a small bound, and many more assets and missing urls than it allows'
        AssetPipelineFilter filter = filter(maxCacheSize: 100)
        (1..300).each { int i -> new File(assets, "asset-${i}.js").text = "// ${i}" }

        when:
        List<Integer> found = (1..300).collect { int i -> request(filter, "/assets/asset-${i}.js").status }
        List<Integer> missing = (1..5000).collect { int i -> request(filter, "/assets/missing-${i}.js").status }

        then: 'each is still answered, and the cache holds no more of either than it is allowed'
        found.every { it == 200 }
        missing.every { it == 404 }
        filter.cache.size() <= 100
        (1..5000).count { int i -> filter.cache.isMissing("missing-${i}.js") } <= 100
    }

    void 'a url that matched no asset is answered from the cache'() {
        given:
        AssetPipelineFilter filter = filter()

        when:
        int first = request(filter, '/assets/later.js').status
        new File(assets, 'later.js').text = '// added after it was looked up'
        int second = request(filter, '/assets/later.js').status

        then: 'the second request is not looked up again, so it is still a 404'
        first == 404
        second == 404
        filter.cache.isMissing('later.js')
        !filter.cache.containsKey('later.js')
    }

    void 'an asset asked for often stays cached after many more recent ones asked for once'() {
        given: 'a cache already full, as it is once an application has run a while'
        AssetPipelineFilter filter = filter(maxCacheSize: 100)
        (1..1100).each { int i -> new File(assets, "asset-${i}.js").text = "// ${i}" }
        (1..100).each { int i -> request(filter, "/assets/asset-${i}.js") }

        when: 'it is asked for a few times, then ten times as many other assets as the cache holds once each'
        5.times { assert request(filter, '/assets/favicon.ico').status == 200 }
        (101..1100).each { int i -> assert request(filter, "/assets/asset-${i}.js").status == 200 }

        then: 'it is still cached, where a cache that kept only the most recent would have dropped it'
        filter.cache.get(DIGESTED)?.exists()
    }

    void 'urls that match no asset never evict an asset, however often they are asked for'() {
        given:
        AssetPipelineFilter filter = filter(maxCacheSize: 100)

        when: 'an asset is asked for once, then a scanner asks for 2,000 urls that do not exist, each twice'
        request(filter, '/assets/favicon.ico')
        (1..2000).each { int i -> 2.times { assert request(filter, "/assets/scanned-${i}.php").status == 404 } }

        then:
        filter.cache.get(DIGESTED)?.exists()
    }

    void 'the cache is bounded when nothing is configured'() {
        expect:
        filter().cache.maximumSize == ProductionAssetCache.DEFAULT_MAXIMUM_SIZE
        ProductionAssetCache.maximumSizeOf(null) == ProductionAssetCache.DEFAULT_MAXIMUM_SIZE
    }

    void 'grails.assets.maxCacheSize sets the bound, from #configured'() {
        expect:
        filter(maxCacheSize: configured).cache.maximumSize == 250

        where: 'a number from application.yml, or a string from a system property'
        configured << [250, 250L, 250.0d, '250', ' 250 ']
    }

    void 'a bound of #configured is refused: it #reason'() {
        when:
        ProductionAssetCache.maximumSizeOf([maxCacheSize: configured])

        then:
        IllegalArgumentException e = thrown()
        e.message.startsWith("grails.assets.maxCacheSize ${reason}")

        where: 'a fraction as application.yml (a Double), Groovy config (a BigDecimal) or a string gives it'
        configured            | reason
        -1                    | 'must be zero or more'
        -0.5d                 | 'must be zero or more'
        'ten thousand'        | 'must be a whole number'
        1.5d                  | 'must be a whole number'
        1.5G                  | 'must be a whole number'
        '1.5'                 | 'must be a whole number'
        Long.MAX_VALUE        | 'must be at most 9223372034707292160'
        99999999999999999999G | 'must be at most 9223372034707292160'
        1e20d                 | 'must be at most 9223372034707292160'
    }

    void 'the largest bound allowed is the one Caffeine keeps'() {
        expect:
        new ProductionAssetCache(ProductionAssetCache.LARGEST_MAXIMUM_SIZE).maximumSize == ProductionAssetCache.LARGEST_MAXIMUM_SIZE
    }

    void 'a bound of zero caches nothing and still serves every asset'() {
        given:
        AssetPipelineFilter filter = filter(maxCacheSize: 0)

        when:
        MockHttpServletResponse hit = request(filter, '/assets/favicon.ico')
        MockHttpServletResponse miss = request(filter, '/assets/missing.js')

        then:
        hit.status == 200
        hit.contentAsByteArray == FAVICON
        miss.status == 404
        filter.cache.isEmpty()
        !filter.cache.isMissing('missing.js')
    }

    void 'gzip is served to #acceptEncoding whether or not the url is cached'() {
        given:
        AssetPipelineFilter filter = filter()

        when: 'the first request finds the asset, and the second finds it in the cache'
        MockHttpServletResponse miss = request(filter, '/assets/favicon.ico', acceptEncoding)
        MockHttpServletResponse hit = request(filter, '/assets/favicon.ico', acceptEncoding)

        then:
        [miss, hit].every { it.getHeader('Content-Encoding') == 'gzip' && it.contentAsByteArray == FAVICON_GZIPPED }

        where: 'a list is the field sent on more than one line'
        acceptEncoding << ['gzip', 'gzip, deflate', 'br, gzip', 'deflate,gzip', 'GZIP', 'gzip;q=1.0, identity;q=0.5', ['br', 'gzip']]
    }

    void '304 responses for #uri using #validator retain cache headers before and after caching the asset'() {
        given:
        AssetPipelineFilter filter = filter()
        assert new File(assets, DIGESTED).setLastModified(1700000000000L)
        Map<String, String> validators = ['If-None-Match': "\"${DIGESTED}\"",
                                          'If-Modified-Since': 'Wed, 15 Nov 2023 22:13:20 GMT']

        when:
        List<MockHttpServletResponse> responses = (1..2).collect {
            MockHttpServletRequest request = new MockHttpServletRequest(filter.servletContext, 'GET', uri)
            request.addHeader(validator, validators[validator])
            MockHttpServletResponse response = new MockHttpServletResponse()
            filter.doFilter(request, response, new MockFilterChain())
            response
        }
        MockHttpServletResponse full = request(filter, uri)

        then:
        full.status == 200
        full.contentAsByteArray == FAVICON
        full.getHeader('ETag') == "\"${DIGESTED}\""
        full.getHeader('Last-Modified') == 'Tue, 14 Nov 2023 22:13:20 GMT'
        full.getHeader('Vary') == 'Accept-Encoding'
        full.getHeader('Cache-Control') == cacheControl
        responses.each { response ->
            assert response.status == 304
            assert response.contentAsByteArray.length == 0
            ['ETag', 'Last-Modified', 'Vary', 'Cache-Control'].each { header ->
                assert response.getHeader(header) == full.getHeader(header)
            }
        }

        where:
        uri                   | validator           | cacheControl
        '/assets/favicon.ico' | 'If-None-Match'     | 'no-cache'
        '/assets/favicon.ico' | 'If-Modified-Since' | 'no-cache'
        "/assets/${DIGESTED}" | 'If-None-Match'     | 'public, max-age=31536000'
        "/assets/${DIGESTED}" | 'If-Modified-Since' | 'public, max-age=31536000'
    }

    void 'each filter has a cache of its own, and getFileCache() answers with the last one created'() {
        given: 'a filter that has cached an asset'
        AssetPipelineFilter first = filter(maxCacheSize: 100)
        request(first, '/assets/favicon.ico')

        when: 'another is created, as it is when a context starts again in the same JVM'
        AssetPipelineFilter second = filter(maxCacheSize: 50)

        then: 'it starts empty, and sizing it leaves the first alone'
        second.cache.isEmpty()
        first.cache.get(DIGESTED)
        first.cache.maximumSize == 100
        second.cache.maximumSize == 50

        and:
        AssetPipelineFilter.fileCache.is(second.cache)
    }

    void 'the cache is still a ConcurrentMap of url to the asset found'() {
        given:
        ProductionAssetCache cache = new ProductionAssetCache(10)
        AssetAttributes other = new AssetAttributes(true, false, false, 2L, null, null, null, null)

        when:
        cache.put('a.js', FOUND)
        cache['b.js'] = FOUND
        cache.putMissing('c.js')

        then: 'it holds the assets, and keeps urls that matched none apart from them'
        cache instanceof ConcurrentMap
        cache.size() == 2
        cache.keySet() == ['a.js', 'b.js'] as Set
        cache.isMissing('c.js')
        !cache.containsKey('c.js')
        cache.putIfAbsent('b.js', other).is(FOUND)
        cache.computeIfAbsent('b.js') { throw new AssertionError('b.js is cached') }.is(FOUND)
        cache.replace('b.js', FOUND, other)
        cache['b.js'].is(other)

        when: 'it is bounded anew, first above what it holds, then below'
        cache.maximumSize = 5
        int afterGrowing = cache.size()
        cache.maximumSize = 1
        int afterShrinking = cache.size()

        then: 'it keeps what it holds, until the bound leaves no room for it'
        afterGrowing == 2
        afterShrinking == 1
        cache.maximumSize == 1

        when:
        cache.clear()

        then: 'it forgets both'
        cache.isEmpty()
        !cache.isMissing('c.js')
    }

    private AssetPipelineFilter filter(Map config = [:]) {
        AssetPipelineConfigHolder.config = config
        MockServletContext servletContext = new MockServletContext("file:${root.absolutePath}")
        GenericWebApplicationContext applicationContext = new GenericWebApplicationContext(servletContext)
        applicationContexts << applicationContext
        applicationContext.registerBeanDefinition('assetProcessorService', new RootBeanDefinition(AssetProcessorService))
        applicationContext.refresh()
        AssetPipelineFilter filter = new AssetPipelineFilter(applicationContext: applicationContext, servletContext: servletContext)
        filter.afterPropertiesSet() // as Spring does, which sizes the cache
        filter
    }

    private static MockHttpServletResponse request(AssetPipelineFilter filter, String uri, Object acceptEncoding = null) {
        MockHttpServletRequest request = new MockHttpServletRequest(filter.servletContext, 'GET', uri)
        (acceptEncoding instanceof List ? acceptEncoding : [acceptEncoding]).findAll().each { request.addHeader('Accept-Encoding', it) }
        MockHttpServletResponse response = new MockHttpServletResponse()
        filter.doFilter(request, response, new MockFilterChain())
        response
    }
}
