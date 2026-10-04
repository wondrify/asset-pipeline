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
    static final String DIGESTED = 'favicon-0123456789abcdef.ico'

    @TempDir
    File root

    GenericWebApplicationContext applicationContext

    void setup() {
        AssetPipelineConfigHolder.config = [:]
        File assets = new File(root, 'assets')
        assets.mkdirs()
        new File(assets, DIGESTED).bytes = FAVICON
        Properties manifest = new Properties()
        manifest.setProperty('favicon.ico', DIGESTED)
        AssetPipelineConfigHolder.manifest = manifest
    }

    void cleanup() {
        applicationContext?.close()
        AssetPipelineConfigHolder.config = [:]
        AssetPipelineConfigHolder.manifest = null
    }

    void 'the cache holds no more entries than its bound'() {
        given: 'a small bound, many times smaller than the urls asked for'
        AssetPipelineConfigHolder.config = [maxCacheSize: 100]
        AssetPipelineFilter filter = filter()

        when: 'an asset the application serves is asked for alongside 5,000 other distinct urls'
        List<Integer> missing = (1..5000).collect { int i ->
            if (i % 50 == 0) {
                assert request(filter, '/assets/favicon.ico').status == 200
            }
            request(filter, "/assets/missing-${i}.js").status
        }

        then: 'each of the others is still a 404, and the cache holds no more than it is allowed'
        missing.every { it == 404 }
        filter.fileCache.size() <= 100

        and: 'the asset is still served'
        MockHttpServletResponse response = request(filter, '/assets/favicon.ico')
        response.status == 200
        response.contentAsByteArray == FAVICON
    }

    void 'the cache is bounded when nothing is configured'() {
        expect:
        filter().fileCache.maximumSize == ProductionAssetCache.DEFAULT_MAXIMUM_SIZE
    }

    void 'grails.assets.maxCacheSize sets the bound, from #configured'() {
        given:
        AssetPipelineConfigHolder.config = [maxCacheSize: configured]

        expect:
        filter().fileCache.maximumSize == 250

        where: 'a number from application.yml, or a string from an environment variable or system property'
        configured << [250, 250L, '250', ' 250 ']
    }

    void 'a bound of zero caches nothing and still serves every asset'() {
        given:
        AssetPipelineConfigHolder.config = [maxCacheSize: 0]
        AssetPipelineFilter filter = filter()

        when:
        MockHttpServletResponse hit = request(filter, '/assets/favicon.ico')
        MockHttpServletResponse miss = request(filter, '/assets/missing.js')

        then:
        hit.status == 200
        hit.contentAsByteArray == FAVICON
        miss.status == 404
        filter.fileCache.size() == 0
    }

    void 'a bound of #configured fails when the filter is created, at startup'() {
        given:
        AssetPipelineConfigHolder.config = [maxCacheSize: configured]

        when:
        filter()

        then:
        IllegalArgumentException e = thrown()
        e.message.contains('grails.assets.maxCacheSize')

        where: 'a negative, a non-number, or a fraction, as application.yml (a Double), Groovy config (a BigDecimal) or a string gives it'
        configured << [-1, 'ten thousand', 1.5d, 1.5G, '1.5']
    }

    private AssetPipelineFilter filter() {
        MockServletContext servletContext = new MockServletContext("file:${root.absolutePath}")
        applicationContext = new GenericWebApplicationContext(servletContext)
        applicationContext.registerBeanDefinition('assetProcessorService', new RootBeanDefinition(AssetProcessorService))
        applicationContext.refresh()
        new AssetPipelineFilter(applicationContext: applicationContext, servletContext: servletContext)
    }

    private static MockHttpServletResponse request(AssetPipelineFilter filter, String uri) {
        MockHttpServletResponse response = new MockHttpServletResponse()
        filter.doFilter(new MockHttpServletRequest(filter.servletContext, 'GET', uri), response, new MockFilterChain())
        response
    }
}
