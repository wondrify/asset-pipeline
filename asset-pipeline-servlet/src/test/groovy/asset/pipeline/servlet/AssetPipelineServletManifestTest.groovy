package asset.pipeline.servlet

import asset.pipeline.AssetPipelineConfigHolder
import jakarta.servlet.DispatcherType
import org.apache.http.HttpResponse
import org.apache.http.client.fluent.Request
import org.apache.http.util.EntityUtils
import org.eclipse.jetty.ee11.servlet.FilterHolder
import org.eclipse.jetty.ee11.webapp.WebAppContext
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.resource.ResourceFactory
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

import static org.junit.Assert.assertEquals

/**
 * The production filter serving a compiled application through its manifest, registered the way Spring Boot
 * registers it: for the mapping and for the exact url of a root path. A url without a digest in its name can
 * change content, so only the digested url may be cached for a year.
 */
class AssetPipelineServletManifestTest {
    private static final String CSS = """body { font-family: "Comic Sans", sans-serif; }"""
    private static final String DIGESTED = 'test-0123456789abcdef.css'

    private static Server server
    private static int port
    private static Properties originalManifest

    @BeforeClass
    static void startServer() {
        originalManifest = AssetPipelineConfigHolder.manifest
        Properties manifest = new Properties()
        manifest.setProperty('test.css', DIGESTED)
        AssetPipelineConfigHolder.manifest = manifest

        AssetPipelineFilter filter = new AssetPipelineFilter()
        filter.mapping = 'assets'
        filter.rootPaths = ['test.css']
        filter.assetPipelineServletResourceRepository = new AssetPipelineServletResourceRepository() {
            @Override
            AssetPipelineServletResource getResource(String path) {
                return path == DIGESTED ? new FixtureResource('fixtures/test.css') : null
            }

            @Override
            AssetPipelineServletResource getGzippedResource(String path) {
                return null
            }
        }

        WebAppContext context = new WebAppContext()
        context.setBaseResource(ResourceFactory.of(context).newResource(new File('src/test/resources/web-app').absoluteFile.toPath()))
        context.setContextPath('/')
        FilterHolder holder = new FilterHolder(filter)
        ['/assets/*', '/test.css'].each { String pattern ->
            context.addFilter(holder, pattern, EnumSet.of(DispatcherType.REQUEST))
        }

        server = new Server(0)
        server.setHandler(context)
        server.start()
        port = ((ServerConnector)server.getConnectors()[0]).getLocalPort()
    }

    @AfterClass
    static void stopServer() {
        AssetPipelineConfigHolder.manifest = originalManifest
        server.stop()
    }

    @Test
    void testRootPathIsRevalidatedRatherThanCachedForAYear() {
        assertServed('/test.css', 'no-cache')
    }

    @Test
    void testUndigestedUrlUnderTheMappingIsRevalidatedRatherThanCachedForAYear() {
        assertServed('/assets/test.css', 'no-cache')
    }

    @Test
    void testDigestedUrlIsCachedForAYear() {
        assertServed("/assets/${DIGESTED}", 'public, max-age=31536000')
    }

    @Test
    void testRootPathAnswersARevalidationWithNotModified() {
        HttpResponse res = Request.Get("http://localhost:${port}/test.css").setHeader('If-None-Match', "\"${DIGESTED}\"").execute().returnResponse()
        assertEquals(304, res.statusLine.statusCode)
    }

    private static void assertServed(String uri, String cacheControl) {
        HttpResponse res = Request.Get("http://localhost:${port}${uri}").execute().returnResponse()
        assertEquals(uri, 200, res.statusLine.statusCode)
        assertEquals(uri, CSS, EntityUtils.toString(res.getEntity()).trim())
        assertEquals(uri, cacheControl, res.getFirstHeader('Cache-Control')?.value)
        assertEquals(uri, "\"${DIGESTED}\"".toString(), res.getFirstHeader('ETag')?.value)
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
