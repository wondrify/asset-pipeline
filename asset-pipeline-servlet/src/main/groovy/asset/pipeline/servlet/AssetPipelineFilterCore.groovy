package asset.pipeline.servlet

import asset.pipeline.AssetHelper
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.AssetPipelineResponseBuilder
import jakarta.servlet.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import java.util.logging.Logger

class AssetPipelineFilterCore {

	private static final Logger log = Logger.getLogger(getClass().getName())
	static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz"


	String mapping = "mapping"
	AssetPipelineServletResourceRepository assetPipelineServletResourceRepository
	ServletContext servletContext


	void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {
		if(request instanceof HttpServletRequest) {
			doFilterHttp(request, response, chain)
		} else {
			chain.doFilter(request, response)
		}
	}

	private void doFilterHttp(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain) {
		if(assetPipelineServletResourceRepository == null) {
			throw new IllegalStateException("Property 'assetPipelineServletResourceRepository' is null")
		}

		String fileUri = request.requestURI
		final String baseAssetUrl = request.contextPath == "/" ? "/$mapping" : "${request.contextPath}/${mapping}"
		// A whole segment, so that a root path such as /assets.txt is not read as under /assets
		if(fileUri == baseAssetUrl || fileUri.startsWith(baseAssetUrl.endsWith('/') ? baseAssetUrl : baseAssetUrl + '/')) {
			fileUri = fileUri.substring(baseAssetUrl.length())
		} else if(fileUri.startsWith(request.contextPath)) {
			// Outside the mapping, as Spring Boot's assets.rootPaths are, a url names the asset from the root of the context
			fileUri = fileUri.substring(request.contextPath.length())
		}
		fileUri = AssetHelper.normalizePath(fileUri) //JETTY Security bug, we MUST prevent reverse
		final Properties manifest = AssetPipelineConfigHolder.manifest
		String manifestPath = fileUri
		if(fileUri.startsWith('/')) {
			manifestPath = fileUri.substring(1) //Omit forward slash
		}
		if(manifest) {
			fileUri = manifest.getProperty(manifestPath, manifestPath)
		}

		AssetPipelineServletResource resource = assetPipelineServletResourceRepository.getResource(fileUri)
		if(resource) {
			final Date lastModifiedDate = resource.getLastModified() ? new Date(resource.getLastModified()) : null
			final AssetPipelineResponseBuilder responseBuilder = new AssetPipelineResponseBuilder(
				fileUri,
				request.getHeader('If-None-Match'),
				request.getHeader('If-Modified-Since'),
				lastModifiedDate
			)

			responseBuilder.headers.each { final header ->
				response.setHeader(header.key, header.value)
			}
			if(responseBuilder.statusCode) {
				response.status = responseBuilder.statusCode
			}

			if(response.status != 304) {
				// Check for GZip
				final String acceptsEncoding = request.getHeader("Accept-Encoding")
				if(acceptsEncoding?.tokenize(", ")?.contains("gzip")) {
					final AssetPipelineServletResource gzipResource = assetPipelineServletResourceRepository.getGzippedResource(fileUri)
					if(gzipResource) {
						resource = gzipResource
						response.setHeader('Content-Encoding', 'gzip')
					}
				}
				final String format = servletContext.getMimeType(request.requestURI)
				final String encoding = request.getCharacterEncoding()
				if(encoding) {
					response.setCharacterEncoding(encoding)
				}
				response.setContentType(format)
				final InputStream inputStream
				try {
					final byte[] buffer = new byte[102400]
					int len
					inputStream = resource.inputStream
					final ServletOutputStream out = response.outputStream
					while((len = inputStream.read(buffer)) != -1) {
						out.write(buffer, 0, len)
					}
					response.flushBuffer()
				} catch(final e) {
					log.fine("File Transfer Aborted (Probably by the user): ${e.getMessage()}")
				} finally {
					try { inputStream?.close() } catch(final ie) { /* silent fail */ }
				}
			} else {
				response.flushBuffer()
			}
		}

		if(!response.committed) {
			filterChain.doFilter(request, response)
		}
	}
}
