package asset.pipeline.servlet

import asset.pipeline.AssetPaths
import asset.pipeline.AssetPipeline
import jakarta.servlet.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import java.util.logging.Logger

class AssetPipelineDevFilterCore {

	private final static Logger log = Logger.getLogger(getClass().getName())


	String mapping = "mapping"
	/** The urls outside the mapping that name an asset, each without its leading slash, as AssetPaths.rootPaths reads them */
	Collection<String> rootPaths = []
	ServletContext servletContext


	void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {
		if(request instanceof HttpServletRequest) {
			doFilterHttp(request, response, chain)
		} else {
			chain.doFilter(request, response)
		}
	}

	private void doFilterHttp(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain) {
		final String path = AssetPaths.pathWithinContext(request.requestURI, request.contextPath)
		String fileUri = AssetPaths.assetPath(path, mapping, rootPaths)
		if(fileUri == null) {
			// Neither under the mapping nor one of the root paths
			filterChain.doFilter(request, response)
			return
		}
		if(fileUri.startsWith('/')) {
			fileUri = fileUri.substring(1)
		}
		final String format = servletContext.getMimeType(path)

		final byte[] fileContents
		if(request.getParameter('compile') == 'false') {
			fileContents = AssetPipeline.serveUncompiledAsset(fileUri, format, null, request.characterEncoding)
		} else {
			fileContents = AssetPipeline.serveAsset(fileUri, format, null, request.characterEncoding)
		}

		// An empty file, such as a robots.txt that allows everything, is served as well
		if(fileContents != null) {
			response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate"); // HTTP 1.1.
			response.setHeader("Pragma", "no-cache"); // HTTP 1.0.
			response.setDateHeader("Expires", 0); // Proxies.
			response.setContentType(format)
			try {
				response.outputStream << fileContents
				response.flushBuffer()
			} catch(final e) {
				log.fine("File Transfer Aborted (Probably by the user): ${e.getMessage()}")
			}
		}

		if(!response.committed) {
			filterChain.doFilter(request, response)
		}
	}
}
