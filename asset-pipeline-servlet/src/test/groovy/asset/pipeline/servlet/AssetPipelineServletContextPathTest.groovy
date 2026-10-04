package asset.pipeline.servlet

import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.fs.AssetResolver
import asset.pipeline.fs.FileSystemAssetResolver
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import org.apache.http.HttpResponse
import org.apache.http.client.fluent.Request
import org.apache.http.util.EntityUtils
import org.eclipse.jetty.ee11.servlet.FilterHolder
import org.eclipse.jetty.ee11.webapp.WebAppContext
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.ContextHandlerCollection
import org.eclipse.jetty.util.resource.ResourceFactory
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

import static org.junit.Assert.assertEquals

/**
 * The filters registered the way Spring Boot registers them, for the mapping and for the exact url of each of
 * assets.rootPaths, in applications that are not at the root of their server. One more url, /test.js, is registered
 * without being a root path, as an application that registers the filters itself may do.
 */
class AssetPipelineServletContextPathTest {
    private static final String CSS = """body { font-family: "Comic Sans", sans-serif; }"""
    private static final List<String> ROOT_PATHS = ['test.css', 'assets-test.css', 'empty.txt']
    private static final List<String> URL_PATTERNS = ['/assets/*', '/test.js'] + ROOT_PATHS.collect { "/${it}".toString() }

    private static Server server
    private static int port
    private static Collection<AssetResolver> originalAssetResolvers
    private static Map originalConfig

    @BeforeClass
    static void startServer() {
        originalAssetResolvers = AssetPipelineConfigHolder.getResolvers()
        originalConfig = AssetPipelineConfigHolder.config
        AssetPipelineConfigHolder.config = [immutable: ['assets-test.css']]
        AssetPipelineConfigHolder.setResolvers([new FileSystemAssetResolver("Test assets", "src/test/resources/fixtures", false)])

        AssetPipelineFilter prodFilter = new AssetPipelineFilter()
        prodFilter.mapping = "assets"
        prodFilter.rootPaths = ROOT_PATHS
        prodFilter.assetPipelineServletResourceRepository = new AssetPipelineServletResourceRepository() {
            @Override
            AssetPipelineServletResource getResource(String path) {
                return path in ['/test.css', '/assets-test.css', '/test.js', '/my icon.css'] ? new FixtureResource("fixtures/test.css") : null
            }

            @Override
            AssetPipelineServletResource getGzippedResource(String path) {
                return null
            }
        }
        AssetPipelineDevFilter devFilter = new AssetPipelineDevFilter()
        devFilter.mapping = "assets"
        devFilter.rootPaths = ROOT_PATHS

        server = new Server(0)
        // As a web.xml sets one up, which cannot give it a mapping or root paths
        WebAppContext plain = context("/plain", new AssetPipelineDevFilter(), ['/*'])

        server.setHandler(new ContextHandlerCollection(context("/app", prodFilter), context("/dev", devFilter), plain))
        server.start()
        port = ((ServerConnector)server.getConnectors()[0]).getLocalPort()
    }

    private static WebAppContext context(String contextPath, Filter filter, List<String> urlPatterns = URL_PATTERNS) {
        WebAppContext context = new WebAppContext()
        context.setBaseResource(ResourceFactory.of(context).newResource(new File("src/test/resources/web-app").absoluteFile.toPath()))
        context.setContextPath(contextPath)
        FilterHolder holder = new FilterHolder(filter)
        urlPatterns.each { String pattern ->
            context.addFilter(holder, pattern, EnumSet.of(DispatcherType.REQUEST))
        }
        return context
    }

    @AfterClass
    static void stopServer() {
        AssetPipelineConfigHolder.setResolvers(originalAssetResolvers)
        AssetPipelineConfigHolder.config = originalConfig
        server.stop()
    }

    @Test
    void testRootPathIsNamedFromTheRootOfTheContext() {
        assertServed("/app/test.css")
    }

    @Test
    void testRootPathIsNamedFromTheRootOfTheContextInDevelopment() {
        assertServed("/dev/test.css")
    }

    @Test
    void testAssetUnderTheMappingIsServedAsBefore() {
        assertServed("/app/assets/test.css")
        assertServed("/dev/assets/test.css")
    }

    @Test
    void testRootPathBeginningWithTheMappingIsNotReadAsUnderIt() {
        assertServed("/app/assets-test.css")
    }

    @Test
    void testUrlOutsideTheMappingThatIsNotARootPathIsLeftToTheApplication() {
        // Both filters would find an asset for /test.js, and neither looks
        assertStatus("/app/test.js", 404)
        assertStatus("/dev/test.js", 404)
    }

    @Test
    void testPathParametersDoNotChangeTheAsset() {
        assertServed("/app/test.css;v=1")
        assertServed("/dev/assets/test.css;jsessionid=0123")
    }

    @Test
    void testAssetWhoseNameIsEncodedInTheUrlIsServed() {
        // Read decoded, as the Grails filter reads it: before, the production filter looked for my%20icon.css
        assertServed("/app/assets/my%20icon.css")
        assertServed("/dev/assets/my%20icon.css")
    }

    @Test
    void testRootPathIsRevalidatedWithoutAManifest() {
        // With no manifest the response builder takes any name for a digested one, but a root url never is
        HttpResponse res = Request.Get("http://localhost:${port}/app/test.css").execute().returnResponse()
        assertEquals(200, res.statusLine.statusCode)
        assertEquals('no-cache', res.getFirstHeader('Cache-Control')?.value)
    }

    @Test
    void testFilterWithoutAMappingServesEveryUrlItIsRegisteredForByItsPath() {
        assertServed("/plain/test.css")
    }

    @Test
    void testAssetListedInImmutableIsCachedForAYearWithoutAManifest() {
        HttpResponse res = Request.Get("http://localhost:${port}/app/assets-test.css").execute().returnResponse()
        assertEquals(200, res.statusLine.statusCode)
        assertEquals('public, max-age=31536000', res.getFirstHeader('Cache-Control')?.value)
    }

    @Test
    void testEmptyRootPathIsServedInDevelopment() {
        HttpResponse res = Request.Get("http://localhost:${port}/dev/empty.txt").execute().returnResponse()
        assertEquals(200, res.statusLine.statusCode)
        assertEquals('no-cache, no-store, must-revalidate', res.getFirstHeader('Cache-Control')?.value)
        assertEquals('', EntityUtils.toString(res.getEntity()))
    }

    private static void assertServed(String uri) {
        HttpResponse res = Request.Get("http://localhost:${port}${uri}").execute().returnResponse()
        assertEquals(uri, 200, res.statusLine.statusCode)
        assertEquals(uri, CSS, EntityUtils.toString(res.getEntity()).trim())
    }

    private static void assertStatus(String uri, int status) {
        HttpResponse res = Request.Get("http://localhost:${port}${uri}").execute().returnResponse()
        assertEquals(uri, status, res.statusLine.statusCode)
    }

    private static final class FixtureResource implements AssetPipelineServletResource {
        private final File file

        FixtureResource(String path) {
            file = new File(FixtureResource.classLoader.getResource(path).toURI())
        }

        @Override
        Long getLastModified() {
            return file.lastModified()
        }

        @Override
        InputStream getInputStream() {
            return new FileInputStream(file)
        }
    }
}
