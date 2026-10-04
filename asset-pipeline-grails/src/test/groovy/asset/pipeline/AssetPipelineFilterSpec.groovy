/*
 * Copyright 2014 the original author or authors.
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

import asset.pipeline.fs.AssetResolver
import asset.pipeline.fs.FileSystemAssetResolver
import asset.pipeline.grails.AssetProcessorService
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.support.GenericWebApplicationContext
import spock.lang.Specification
import spock.lang.TempDir

/**
 * What the filter does with the urls it is registered for: everything under the mapping, and the
 * exact url of each of grails.assets.rootPaths.
 */
class AssetPipelineFilterSpec extends Specification {

    static final byte[] FAVICON = [0, 0, 1, 0, 1, 0] as byte[]
    static final byte[] LOGO = [(byte) 0x89, 0x50, 0x4E, 0x47] as byte[]
    static final List<String> ROOT_PATHS = ['favicon.ico', 'assets-logo.png', 'robots.txt']

    @TempDir
    File root

    GenericWebApplicationContext applicationContext
    AssetPipelineFilter filter
    Collection<AssetResolver> originalResolvers
    Map originalConfig
    Properties originalManifest

    void setup() {
        originalResolvers = AssetPipelineConfigHolder.resolvers
        originalConfig = AssetPipelineConfigHolder.config
        originalManifest = AssetPipelineConfigHolder.manifest
        AssetPipelineConfigHolder.config = [:]
        AssetPipelineConfigHolder.manifest = null
    }

    void cleanup() {
        applicationContext?.close()
        AssetPipelineConfigHolder.resolvers = originalResolvers
        AssetPipelineConfigHolder.config = originalConfig
        AssetPipelineConfigHolder.manifest = originalManifest
        filter?.fileCache?.clear()
    }

    void 'in development, root path #uri is served from the root of context #contextPath'() {
        given:
        development()

        when:
        Exchange exchange = request(contextPath, uri)

        then:
        exchange.response.status == 200
        exchange.response.contentAsByteArray == FAVICON
        !exchange.passedOn

        where: 'the context path as the container gives it, encoded, and the uri likewise'
        contextPath  | uri
        ''           | '/favicon.ico'
        '/app'       | '/app/favicon.ico'
        '/my%20app'  | '/my%20app/favicon.ico'
    }

    void 'in development, #uri under the mapping is served as before'() {
        given:
        development()

        when:
        Exchange exchange = request(contextPath, uri)

        then:
        exchange.response.status == 200
        exchange.response.contentAsByteArray == FAVICON
        !exchange.passedOn

        where:
        contextPath  | uri
        ''           | '/assets/favicon.ico'
        '/app'       | '/app/assets/favicon.ico'
        '/my%20app'  | '/my%20app/assets/favicon.ico'
    }

    void 'a root path whose name begins with the mapping is not read as an asset under it'() {
        given: 'before, /assets-logo.png was cut to -logo.png as if it were under /assets'
        development()

        when:
        Exchange exchange = request('', '/assets-logo.png')

        then:
        exchange.response.status == 200
        exchange.response.contentAsByteArray == LOGO
    }

    void 'in development, a root path with no asset passes the request on to the application, under context #contextPath'() {
        given: 'robots.txt is configured as a root path, and the application may answer it itself'
        development()

        when:
        Exchange exchange = request(contextPath, "${contextPath}/robots.txt")

        then:
        exchange.passedOn
        !exchange.response.committed
        exchange.response.status == 200

        where:
        contextPath << ['', '/my%20app']
    }

    void 'a url outside the mapping that is not a root path is left to the application, though an asset has its name'() {
        given: 'a filter registered more widely than the mapping and its root paths, as an application may register it'
        development(['robots.txt'])

        when:
        Exchange exchange = request('', '/favicon.ico')

        then:
        exchange.passedOn
        !exchange.response.committed
        exchange.response.contentAsByteArray.length == 0
    }

    void 'path parameters, which the container leaves out when it maps a request, do not change the asset #uri names'() {
        given:
        development()

        when:
        Exchange exchange = request(contextPath, uri)

        then:
        exchange.response.status == 200
        exchange.response.contentAsByteArray == FAVICON

        where:
        contextPath | uri
        ''          | '/favicon.ico;jsessionid=0123'
        '/app'      | '/app/assets/favicon.ico;v=1'
    }

    void 'a missing asset under the mapping is still a 404'() {
        given:
        development()

        when:
        Exchange exchange = request('', '/assets/missing.png')

        then:
        exchange.response.status == 404
        !exchange.passedOn
    }

    void 'from a compiled war, root path #uri is served through the manifest and revalidated rather than cached for a year'() {
        given:
        compiled()

        when:
        Exchange exchange = request(contextPath, uri)

        then: 'the digested file, under the name the client asked for'
        exchange.response.status == 200
        exchange.response.contentAsByteArray == FAVICON
        !exchange.passedOn

        and: 'a url without a digest can change content, so it is not cached as if it could not'
        exchange.response.getHeader('Cache-Control') == 'no-cache'
        exchange.response.getHeader('ETag') == '"favicon-0123456789abcdef.ico"'

        where:
        contextPath | uri
        ''          | '/favicon.ico'
        '/app'      | '/app/favicon.ico'
    }

    void 'from a compiled war, the digested url under the mapping is still cached for a year'() {
        given:
        compiled()

        when:
        Exchange exchange = request('', '/assets/favicon-0123456789abcdef.ico')

        then:
        exchange.response.status == 200
        exchange.response.getHeader('Cache-Control') == 'public, max-age=31536000'
    }

    void 'from a compiled war, a root path with no asset passes the request on to the application'() {
        given:
        compiled()

        when:
        Exchange exchange = request('', '/robots.txt')

        then:
        exchange.passedOn
        !exchange.response.committed
    }

    void 'from a compiled war, a missing root path is remembered once, whatever path parameters each request adds'() {
        given:
        compiled()

        when:
        List<Exchange> exchanges = (1..3).collect { int n -> request('', "/robots.txt;x=${n}") }

        then:
        exchanges.every { it.passedOn }
        filter.cache.isMissing('robots.txt')
        (1..3).every { int n -> !filter.cache.isMissing("robots.txt;x=${n}") }
    }

    /** Assets compiled on request, as in development: images/ flattened, as grails-app/assets is. */
    private void development(List<String> rootPaths = ROOT_PATHS) {
        File images = new File(root, 'images')
        images.mkdirs()
        new File(images, 'favicon.ico').bytes = FAVICON
        new File(images, 'assets-logo.png').bytes = LOGO
        AssetPipelineConfigHolder.resolvers = [new FileSystemAssetResolver('application', root.absolutePath)]
        start(rootPaths)
    }

    /** Assets compiled ahead of time, as in a war: digested files beside a manifest. */
    private void compiled() {
        File assets = new File(root, 'assets')
        assets.mkdirs()
        new File(assets, 'favicon-0123456789abcdef.ico').bytes = FAVICON
        new File(assets, 'favicon.ico').bytes = FAVICON
        Properties manifest = new Properties()
        manifest.setProperty('favicon.ico', 'favicon-0123456789abcdef.ico')
        AssetPipelineConfigHolder.manifest = manifest
        start(ROOT_PATHS)
    }

    private void start(List<String> rootPaths) {
        MockServletContext servletContext = new MockServletContext("file:${root.absolutePath}")
        applicationContext = new GenericWebApplicationContext(servletContext)
        applicationContext.registerBean('assetProcessorService', AssetProcessorService)
        applicationContext.refresh()
        filter = new AssetPipelineFilter(applicationContext: applicationContext, servletContext: servletContext, rootPaths: rootPaths)
        // Through the filter, whether the cache is shared by every filter or its own
        filter.fileCache.clear()
    }

    private Exchange request(String contextPath, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(filter.servletContext, 'GET', uri)
        request.contextPath = contextPath
        MockHttpServletResponse response = new MockHttpServletResponse()
        MockFilterChain chain = new MockFilterChain()
        filter.doFilter(request, response, chain)
        new Exchange(response: response, passedOn: chain.request != null)
    }

    private static class Exchange {
        MockHttpServletResponse response
        boolean passedOn
    }
}
