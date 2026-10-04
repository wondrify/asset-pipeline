package asset.pipeline.servlet

import asset.pipeline.AssetHelper
import asset.pipeline.AssetPaths
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.AssetPipelineResponseBuilder
import jakarta.servlet.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import java.util.logging.Logger

class AssetPipelineFilterCore {

	private static final Logger log = Logger.getLogger(getClass().getName())
	static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz"


	/** The url assets are served under; empty, as it is unless set, serves every url the filter is registered for by its path */
	String mapping = ""
	/** The urls outside the mapping that name an asset, each without its leading slash, as AssetPaths.rootPaths reads them */
	Collection<String> rootPaths = []

	// Read as AssetPaths.rootPaths reads them, so that /favicon.ico names the same asset as favicon.ico
	void setRootPaths(final Collection<String> rootPaths) {
		this.rootPaths = new LinkedHashSet<String>(AssetPaths.rootPaths(rootPaths, null))
	}
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

		final String path = AssetPaths.pathWithinContext(request.requestURI, request.contextPath)
		final AssetPaths.AssetUrl assetUrl = AssetPaths.assetUrl(request.method, path, mapping, rootPaths)
		if(assetUrl == null) {
			// Neither under the mapping nor one of the root paths
			filterChain.doFilter(request, response)
			return
		}
		String fileUri = AssetHelper.normalizePath(assetUrl.path) //JETTY Security bug, we MUST prevent reverse
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
			// The name asked for, as the Grails filter passes it: fileUri is the digested name by now, which
			// the builder would take for a url that can be cached for a year
			final AssetPipelineResponseBuilder responseBuilder = new AssetPipelineResponseBuilder(
				manifestPath,
				AssetPipelineResponseBuilder.combineIfNoneMatchHeaders(request.getHeaders('If-None-Match')),
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
				if(AssetPipelineResponseBuilder.acceptsGzip(request.getHeaders("Accept-Encoding"))) {
					final AssetPipelineServletResource gzipResource = assetPipelineServletResourceRepository.getGzippedResource(fileUri)
					if(gzipResource) {
						resource = gzipResource
						response.setHeader('Content-Encoding', 'gzip')
					}
				}
				final String format = servletContext.getMimeType(path)
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
