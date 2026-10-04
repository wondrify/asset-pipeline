package asset.pipeline.servlet

import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.fs.AssetResolver
import asset.pipeline.fs.FileSystemAssetResolver
import jakarta.servlet.DispatcherType
import org.apache.http.Header
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
import static org.junit.Assert.assertNotNull

class AssetPipelineServletIntegrationTest {
    private static Server server
    private static AssetPipelineFilter prodFilter
    private static AssetPipelineDevFilter devFilter
    private static int port
    private static Collection<AssetResolver> originalAssetResolvers

    @BeforeClass
    static void startServer() {
        originalAssetResolvers = AssetPipelineConfigHolder.getResolvers()
        server = new Server(0)
        prodFilter = new AssetPipelineFilter()
        prodFilter.mapping = "prod_assets"
        prodFilter.assetPipelineServletResourceRepository = new AssetPipelineServletResourceRepository() {
            @Override
            AssetPipelineServletResource getResource(String path) {
                if (path == "/css/test.css") {
                    return new TestResource("fixtures/test.css")
                }

                if (path == "/css/test.js") {
                    return new TestResource("fixtures/test.js")
                }

                if (path == "/maybe_gzipped/css/test.js") {
                    return new TestResource("fixtures/test.js")
                }

                return null
            }

            @Override
            AssetPipelineServletResource getGzippedResource(String path) {
                if (path == "/maybe_gzipped/css/test.js") {
                    return new TestResource("fixtures/test.js.gz")
                }

                return null
            }
        }
        devFilter = new AssetPipelineDevFilter()
        devFilter.mapping = "dev_assets"

        WebAppContext context = new WebAppContext()
        context.setBaseResource(ResourceFactory.of(context).newResource(new File("src/test/resources/web-app").absoluteFile.toPath()))
        context.addFilter(new FilterHolder(prodFilter), "/*", EnumSet.of(DispatcherType.REQUEST))
        context.addFilter(new FilterHolder(devFilter), "/*", EnumSet.of(DispatcherType.REQUEST))
        context.setContextPath("/")

        server.setHandler(context)

        server.start()
        port = ((ServerConnector)server.getConnectors()[0]).getLocalPort()
    }

    @AfterClass
    static void stopServer() {
        AssetPipelineConfigHolder.setResolvers(originalAssetResolvers)
        server.stop()
    }

    private final static class TestResource implements AssetPipelineServletResource {
        private final File file

        public TestResource(String path) {
            URL resource = getClass().getClassLoader().getResource(path)
            file = new File(resource.toURI())
            if (file == null || !file.isFile()) {
                throw new IllegalArgumentException("Provided path ${path} is not an existing file")
            }
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

    @Test
    void testAssetPipelineServlet() {
        HttpResponse res
        res = Request.Get("http://localhost:${port}/prod_assets/css/test.css").execute().returnResponse()
        assertEquals(200, res.statusLine.statusCode)
        assertEquals("""body { font-family: "Comic Sans", sans-serif; }""", EntityUtils.toString(res.getEntity()))

        res = Request.Get("http://localhost:${port}/prod_assets/css/test.js").execute().returnResponse()
        assertEquals(200, res.statusLine.statusCode)
        assertEquals("""document.write("test");""", EntityUtils.toString(res.getEntity()))

        // Gzipped file has different contents to test that we actually went through gzip
        res = Request.Get("http://localhost:${port}/prod_assets/maybe_gzipped/css/test.js").execute().returnResponse()
        assertEquals(200, res.statusLine.statusCode)
        assertEquals("""document.write("test gzip hack");""", EntityUtils.toString(res.getEntity()))

        // Un-gzipped file for same path is the normal file
        res = Request.Get("http://localhost:${port}/prod_assets/maybe_gzipped/css/test.js").setHeader("Accept-Encoding", "").execute()returnResponse()
        assertEquals(200, res.statusLine.statusCode)
        assertEquals("""document.write("test");""", EntityUtils.toString(res.getEntity()))


        res = Request.Get("http://localhost:${port}/prod_assets/css/test.css").execute().returnResponse()
        Header etagHeader = res.getFirstHeader("ETag")
        assertNotNull(etagHeader)

        res = Request.Get("http://localhost:${port}/prod_assets/css/test.css").setHeader("If-None-Match", etagHeader.getValue()).execute().returnResponse()
        assertEquals(304, res.statusLine.statusCode)
    }

    @Test
    void testConditionalRequests() {
        String url = "http://localhost:${port}/prod_assets/css/test.css"
        HttpResponse initial = Request.Get(url).execute().returnResponse()
        String etag = initial.getFirstHeader('ETag').value
        String lastModified = initial.getFirstHeader('Last-Modified').value

        HttpResponse changed = Request.Get(url)
                .setHeader('If-None-Match', '"old.css"')
                .setHeader('If-Modified-Since', lastModified)
                .execute().returnResponse()
        assertEquals(200, changed.statusLine.statusCode)
        assertEquals('body { font-family: "Comic Sans", sans-serif; }', EntityUtils.toString(changed.entity))

        [['W/' + etag], ['"old.css", ' + etag], ['"old.css"', 'W/' + etag], ['*'], []].each { List<String> tags ->
            Request request = Request.Get(url).setHeader('If-Modified-Since', lastModified)
            tags.each { request.addHeader('If-None-Match', it) }
            HttpResponse unchanged = request.execute().returnResponse()
            assertEquals("If-None-Match: ${tags}", 304, unchanged.statusLine.statusCode)
        }
    }

    @Test
    void testGzippedAssetIsValidatedByItsOwnETag() {
        String url = "http://localhost:${port}/prod_assets/maybe_gzipped/css/test.js"
        String gzipped = '"maybe_gzipped/css/test.js-gz"'
        String asItIs = '"maybe_gzipped/css/test.js"'
        assertEquals(gzipped, Request.Get(url).execute().returnResponse().getFirstHeader('ETag')?.value)
        assertEquals(asItIs, Request.Get(url).setHeader('Accept-Encoding', '').execute().returnResponse().getFirstHeader('ETag')?.value)

        // Each coding is validated by its own tag, so a cache never takes one for the other
        assertEquals(304, Request.Get(url).setHeader('If-None-Match', gzipped).execute().returnResponse().statusLine.statusCode)
        assertEquals(200, Request.Get(url).setHeader('If-None-Match', asItIs).execute().returnResponse().statusLine.statusCode)
        assertEquals(304, Request.Get(url).setHeader('Accept-Encoding', '').setHeader('If-None-Match', asItIs).execute().returnResponse().statusLine.statusCode)
        assertEquals(200, Request.Get(url).setHeader('Accept-Encoding', '').setHeader('If-None-Match', gzipped).execute().returnResponse().statusLine.statusCode)
    }

    @Test
    void testOtherMethodsFailTheirPreconditionRatherThanRevalidate() {
        String url = "http://localhost:${port}/prod_assets/css/test.css"
        HttpResponse initial = Request.Get(url).execute().returnResponse()
        String etag = initial.getFirstHeader('ETag').value
        String lastModified = initial.getFirstHeader('Last-Modified').value
        EntityUtils.consume(initial.entity)

        HttpResponse failed = Request.Post(url).setHeader('If-None-Match', etag).execute().returnResponse()
        assertEquals(412, failed.statusLine.statusCode)
        assertEquals('', failed.entity == null ? '' : EntityUtils.toString(failed.entity))

        // If-Modified-Since is read for GET and HEAD alone
        HttpResponse served = Request.Post(url).setHeader('If-Modified-Since', lastModified).execute().returnResponse()
        assertEquals(200, served.statusLine.statusCode)
        assertEquals('body { font-family: "Comic Sans", sans-serif; }', EntityUtils.toString(served.entity))
    }

    @Test
    void testAssetPipelineDevServlet() {
        FileSystemAssetResolver assetResolver = new FileSystemAssetResolver("Test assets", "src/test/resources/fixtures", false)
        AssetPipelineConfigHolder.setResolvers([assetResolver])

        HttpResponse res
        res = Request.Get("http://localhost:${port}/dev_assets/test.css").execute().returnResponse()
        assertEquals(200, res.statusLine.statusCode)
        assertEquals("""body { font-family: "Comic Sans", sans-serif; }""", EntityUtils.toString(res.getEntity()).trim())
    }
}
