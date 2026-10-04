package asset.pipeline.servlet

import asset.pipeline.AssetPipeline
import jakarta.servlet.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import java.util.logging.Logger

class AssetPipelineDevFilterCore {

	private final static Logger log = Logger.getLogger(getClass().getName())


	String mapping = "mapping"
	ServletContext servletContext


	void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {
		if(request instanceof HttpServletRequest) {
			doFilterHttp(request, response, chain)
		} else {
			chain.doFilter(request, response)
		}
	}

	private void doFilterHttp(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain) {
		String fileUri = request.requestURI
		final String baseAssetUrl = request.contextPath == "/" ? "/$mapping/" : "${request.contextPath}/${mapping}/"
		if(fileUri.startsWith(baseAssetUrl)) {
			fileUri = fileUri.substring(baseAssetUrl.length())
		} else if(fileUri.startsWith(request.contextPath + '/')) {
			// Outside the mapping, as Spring Boot's assets.rootPaths are, a url names the asset from the root of the context
			fileUri = fileUri.substring(request.contextPath.length() + 1)
		}
		final String format = servletContext.getMimeType(request.requestURI)

		final byte[] fileContents
		if(request.getParameter('compile') == 'false') {
			fileContents = AssetPipeline.serveUncompiledAsset(fileUri, format, null, request.characterEncoding)
		} else {
			fileContents = AssetPipeline.serveAsset(fileUri, format, null, request.characterEncoding)
		}

		if(fileContents) {
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
