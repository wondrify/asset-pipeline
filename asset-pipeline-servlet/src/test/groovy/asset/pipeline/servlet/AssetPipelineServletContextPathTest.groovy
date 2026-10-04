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
 * assets.rootPaths, in applications that are not at the root of their server.
 */
class AssetPipelineServletContextPathTest {
    private static final String CSS = """body { font-family: "Comic Sans", sans-serif; }"""
    private static final List<String> URL_PATTERNS = ['/assets/*', '/test.css', '/assets-test.css']

    private static Server server
    private static int port
    private static Collection<AssetResolver> originalAssetResolvers

    @BeforeClass
    static void startServer() {
        originalAssetResolvers = AssetPipelineConfigHolder.getResolvers()
        AssetPipelineConfigHolder.setResolvers([new FileSystemAssetResolver("Test assets", "src/test/resources/fixtures", false)])

        AssetPipelineFilter prodFilter = new AssetPipelineFilter()
        prodFilter.mapping = "assets"
        prodFilter.assetPipelineServletResourceRepository = new AssetPipelineServletResourceRepository() {
            @Override
            AssetPipelineServletResource getResource(String path) {
                return path in ['/test.css', '/assets-test.css'] ? new FixtureResource("fixtures/test.css") : null
            }

            @Override
            AssetPipelineServletResource getGzippedResource(String path) {
                return null
            }
        }
        AssetPipelineDevFilter devFilter = new AssetPipelineDevFilter()
        devFilter.mapping = "assets"

        server = new Server(0)
        server.setHandler(new ContextHandlerCollection(context("/app", prodFilter), context("/dev", devFilter)))
        server.start()
        port = ((ServerConnector)server.getConnectors()[0]).getLocalPort()
    }

    private static WebAppContext context(String contextPath, Filter filter) {
        WebAppContext context = new WebAppContext()
        context.setBaseResource(ResourceFactory.of(context).newResource(new File("src/test/resources/web-app").absoluteFile.toPath()))
        context.setContextPath(contextPath)
        FilterHolder holder = new FilterHolder(filter)
        URL_PATTERNS.each { String pattern ->
            context.addFilter(holder, pattern, EnumSet.of(DispatcherType.REQUEST))
        }
        return context
    }

    @AfterClass
    static void stopServer() {
        AssetPipelineConfigHolder.setResolvers(originalAssetResolvers)
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

    private static void assertServed(String uri) {
        HttpResponse res = Request.Get("http://localhost:${port}${uri}").execute().returnResponse()
        assertEquals(uri, 200, res.statusLine.statusCode)
        assertEquals(uri, CSS, EntityUtils.toString(res.getEntity()).trim())
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
