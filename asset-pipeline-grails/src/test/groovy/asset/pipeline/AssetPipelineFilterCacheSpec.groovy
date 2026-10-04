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
 * The record a compiled application's filter keeps of each url it has looked up.
 */
class AssetPipelineFilterCacheSpec extends Specification {

    static final byte[] FAVICON = [0, 0, 1, 0, 1, 0] as byte[]
    static final byte[] FAVICON_GZIPPED = [31, -117, 8, 0] as byte[]
    static final String DIGESTED = 'favicon-0123456789abcdef.ico'
    static final AssetAttributes FOUND = new AssetAttributes(true, false, false, 1L, null, null, null, null)
    static final AssetAttributes MISSING = new AssetAttributes(false, false, false, null, null, null, null, null)

    @TempDir
    File root

    File assets
    GenericWebApplicationContext applicationContext

    void setup() {
        AssetPipelineConfigHolder.config = [:]
        resetCache()
        assets = new File(root, 'assets')
        assets.mkdirs()
        new File(assets, DIGESTED).bytes = FAVICON
        new File(assets, "${DIGESTED}.gz").bytes = FAVICON_GZIPPED
        Properties manifest = new Properties()
        manifest.setProperty('favicon.ico', DIGESTED)
        AssetPipelineConfigHolder.manifest = manifest
    }

    void cleanup() {
        applicationContext?.close()
        AssetPipelineConfigHolder.config = [:]
        AssetPipelineConfigHolder.manifest = null
        resetCache()
    }

    void 'the cache holds no more urls than its bound'() {
        given: 'a small bound, and many more assets and missing urls than it allows'
        AssetPipelineFilter.fileCache.maximumSize = 100
        AssetPipelineFilter filter = filter()
        (1..300).each { int i -> new File(assets, "asset-${i}.js").text = "// ${i}" }

        when:
        List<Integer> found = (1..300).collect { int i -> request(filter, "/assets/asset-${i}.js").status }
        List<Integer> missing = (1..5000).collect { int i -> request(filter, "/assets/missing-${i}.js").status }

        then: 'each is still answered, and the cache holds no more than it is allowed'
        found.every { it == 200 }
        missing.every { it == 404 }
        AssetPipelineFilter.fileCache.size() <= 100
    }

    // The two below use a cache of their own: the filter's is static, and Caffeine's record of how
    // often each url is asked for outlives clear(), so it would carry over from one feature to the next

    void 'an asset asked for often stays cached after many more recent ones asked for once'() {
        given: 'a cache already full, as it is once an application has run a while'
        ProductionAssetCache cache = new ProductionAssetCache(100)
        (1..100).each { int i -> ask(cache, "asset-${i}.js", FOUND) }

        when: 'it is asked for a few times, then ten times as many other assets as the cache holds once each'
        5.times { ask(cache, DIGESTED, FOUND) }
        (101..1100).each { int i -> ask(cache, "asset-${i}.js", FOUND) }

        then: 'it is still cached, where a cache that kept only the most recent would have dropped it'
        cache.get(DIGESTED)
    }

    void 'an asset asked for now and then stays cached through a flood of missing urls asked for once each'() {
        given: 'a cache already full'
        ProductionAssetCache cache = new ProductionAssetCache(100)
        (1..100).each { int i -> ask(cache, "missing-${i}.js", MISSING) }

        when: 'a scanner asks for 5,000 urls that do not exist, while the asset is asked for every 200 requests'
        (1..5000).each { int i ->
            if (i % 200 == 1) {
                ask(cache, DIGESTED, FOUND)
            }
            ask(cache, "scanned-${i}.php", MISSING)
        }

        then: 'it is still cached, 200 urls after it was last asked for, twice what the cache holds'
        cache.get(DIGESTED)
        cache.size() <= 100
    }

    void 'the cache is bounded when nothing is configured'() {
        expect:
        ProductionAssetCache.maximumSizeOf([:]) == ProductionAssetCache.DEFAULT_MAXIMUM_SIZE
        ProductionAssetCache.maximumSizeOf(null) == ProductionAssetCache.DEFAULT_MAXIMUM_SIZE
        new ProductionAssetCache().maximumSize == ProductionAssetCache.DEFAULT_MAXIMUM_SIZE
    }

    void 'grails.assets.maxCacheSize sets the bound, from #configured'() {
        expect:
        ProductionAssetCache.maximumSizeOf([maxCacheSize: configured]) == 250

        where: 'a number from application.yml, or a string from a system property or a placeholder'
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
        99999999999999999999G | 'must be at most 9223372036854775807'
        1e20d                 | 'must be at most 9223372036854775807'
    }

    void 'a bound of zero caches nothing and still serves every asset'() {
        given:
        AssetPipelineFilter.fileCache.maximumSize = 0
        AssetPipelineFilter filter = filter()

        when:
        MockHttpServletResponse hit = request(filter, '/assets/favicon.ico')
        MockHttpServletResponse miss = request(filter, '/assets/missing.js')

        then:
        hit.status == 200
        hit.contentAsByteArray == FAVICON
        miss.status == 404
        AssetPipelineFilter.fileCache.isEmpty()
    }

    void 'gzip is served to "#acceptEncoding" whether or not the url is cached'() {
        given:
        AssetPipelineFilter filter = filter()

        when: 'the first request finds the asset, and the second finds it in the cache'
        MockHttpServletResponse miss = request(filter, '/assets/favicon.ico', acceptEncoding)
        MockHttpServletResponse hit = request(filter, '/assets/favicon.ico', acceptEncoding)

        then:
        [miss, hit].every { it.getHeader('Content-Encoding') == 'gzip' && it.contentAsByteArray == FAVICON_GZIPPED }

        where:
        acceptEncoding << ['gzip', 'gzip, deflate', 'br, gzip', 'deflate,gzip', 'GZIP', 'gzip;q=1.0, identity;q=0.5']
    }

    void 'the cache is still a ConcurrentMap of url to what the filter found'() {
        given:
        ProductionAssetCache cache = new ProductionAssetCache(10)
        AssetAttributes found = FOUND
        AssetAttributes missing = MISSING

        when:
        cache.put('a.js', found)
        cache['b.js'] = missing

        then:
        cache instanceof ConcurrentMap
        cache.size() == 2
        cache.keySet() == ['a.js', 'b.js'] as Set
        cache['b.js'].is(missing)
        cache.putIfAbsent('b.js', found).is(missing)
        cache.computeIfAbsent('b.js') { throw new AssertionError('b.js is cached') }.is(missing)
        cache.replace('b.js', missing, found)
        cache['b.js'].is(found)

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

        then:
        cache.isEmpty()
    }

    // What the filter does with each url: look it up, and record what it finds when it is not cached
    private static void ask(ProductionAssetCache cache, String uri, AssetAttributes attributes) {
        if (cache.get(uri) == null) {
            cache.put(uri, attributes)
        }
    }

    private static void resetCache() {
        AssetPipelineFilter.fileCache.maximumSize = ProductionAssetCache.DEFAULT_MAXIMUM_SIZE
        AssetPipelineFilter.fileCache.clear()
    }

    private AssetPipelineFilter filter() {
        MockServletContext servletContext = new MockServletContext("file:${root.absolutePath}")
        applicationContext = new GenericWebApplicationContext(servletContext)
        applicationContext.registerBeanDefinition('assetProcessorService', new RootBeanDefinition(AssetProcessorService))
        applicationContext.refresh()
        new AssetPipelineFilter(applicationContext: applicationContext, servletContext: servletContext)
    }

    private static MockHttpServletResponse request(AssetPipelineFilter filter, String uri, String acceptEncoding = null) {
        MockHttpServletRequest request = new MockHttpServletRequest(filter.servletContext, 'GET', uri)
        if (acceptEncoding) {
            request.addHeader('Accept-Encoding', acceptEncoding)
        }
        MockHttpServletResponse response = new MockHttpServletResponse()
        filter.doFilter(request, response, new MockFilterChain())
        response
    }
}
