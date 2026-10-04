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

/**
 * The record a compiled application's filter keeps of each url it has looked up.
 */
class AssetPipelineFilterCacheSpec extends Specification {

    static final byte[] FAVICON = [0, 0, 1, 0, 1, 0] as byte[]
    static final byte[] FAVICON_GZIPPED = [31, -117, 8, 0] as byte[]
    static final String DIGESTED = 'favicon-0123456789abcdef.ico'

    @TempDir
    File root

    File assets
    GenericWebApplicationContext applicationContext

    void setup() {
        AssetPipelineConfigHolder.config = [:]
        AssetPipelineFilter.fileCache.maximumSize = ProductionAssetCache.DEFAULT_MAXIMUM_SIZE
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
        AssetPipelineFilter.fileCache.maximumSize = ProductionAssetCache.DEFAULT_MAXIMUM_SIZE
    }

    void 'the cache holds no more assets than its bound, and no more missing urls'() {
        given: 'a small bound, and many more assets and missing urls than it allows'
        AssetPipelineFilter.fileCache.maximumSize = 100
        AssetPipelineFilter filter = filter()
        (1..300).each { int i -> new File(assets, "asset-${i}.js").text = "// ${i}" }

        when:
        List<Integer> found = (1..300).collect { int i -> request(filter, "/assets/asset-${i}.js").status }
        List<Integer> missing = (1..5000).collect { int i -> request(filter, "/assets/missing-${i}.js").status }

        then: 'each is still answered, and the cache holds no more of either than it is allowed'
        found.every { it == 200 }
        missing.every { it == 404 }
        AssetPipelineFilter.fileCache.values().count { it.exists() } <= 100
        AssetPipelineFilter.fileCache.values().count { !it.exists() } <= 100
    }

    void 'an asset asked for often stays cached after many more recent ones asked for once'() {
        given: 'a cache already full, as it is once an application has run a while'
        AssetPipelineFilter.fileCache.maximumSize = 100
        AssetPipelineFilter filter = filter()
        (1..1100).each { int i -> new File(assets, "asset-${i}.js").text = "// ${i}" }
        (1..100).each { int i -> request(filter, "/assets/asset-${i}.js") }

        when: 'it is asked for a few times, then ten times as many other assets as the cache holds once each'
        5.times { assert request(filter, '/assets/favicon.ico').status == 200 }
        (101..1100).each { int i -> assert request(filter, "/assets/asset-${i}.js").status == 200 }

        then: 'it is still cached, where a cache that kept only the most recent would have dropped it'
        AssetPipelineFilter.fileCache.get(DIGESTED)?.exists()
    }

    void 'requests for urls that do not exist never evict an asset'() {
        given:
        AssetPipelineFilter.fileCache.maximumSize = 100
        AssetPipelineFilter filter = filter()

        when: 'an asset is asked for once, then many missing urls each more often than that'
        request(filter, '/assets/favicon.ico')
        (1..2000).each { int i -> 2.times { request(filter, "/assets/missing-${i}.js") } }

        then:
        AssetPipelineFilter.fileCache.get(DIGESTED)?.exists()
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

    void 'a bound of #configured is refused'() {
        when:
        ProductionAssetCache.maximumSizeOf([maxCacheSize: configured])

        then:
        IllegalArgumentException e = thrown()
        e.message.contains('grails.assets.maxCacheSize')

        where: 'a negative, a non-number, a fraction as application.yml (a Double), Groovy config (a BigDecimal) or a string gives it, or one too large'
        configured << [-1, 'ten thousand', 1.5d, 1.5G, '1.5', 99999999999999999999G]
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

    void 'the cache is still a Map of url to what the filter found'() {
        given:
        ProductionAssetCache cache = new ProductionAssetCache(10)
        AssetAttributes found = new AssetAttributes(true, false, false, 1L, null, null, null, null)
        AssetAttributes missing = new AssetAttributes(false, false, false, null, null, null, null, null)

        when:
        cache.put('a.js', found)
        cache['b.js'] = missing

        then:
        cache instanceof Map
        cache.size() == 2
        cache.keySet() == ['a.js', 'b.js'] as Set
        cache.containsKey('b.js')
        cache['b.js'].is(missing)

        when: 'the asset at a url that was missing appears'
        cache.put('b.js', found)

        then: 'it is the same entry, not a second one'
        cache.size() == 2
        cache['b.js'].is(found)

        when:
        cache.remove('a.js')
        cache.keySet().remove('b.js')

        then:
        cache.isEmpty()
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
