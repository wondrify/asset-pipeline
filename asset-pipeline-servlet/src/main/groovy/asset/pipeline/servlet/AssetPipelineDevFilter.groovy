package asset.pipeline.servlet

import jakarta.servlet.*

class AssetPipelineDevFilter implements Filter {

	AssetPipelineDevFilterCore assetPipelineDevFilterCore = new AssetPipelineDevFilterCore()


	void setMapping(final String mapping) {
		assetPipelineDevFilterCore.mapping = mapping
	}

	/**
	 * The urls outside the mapping that name an asset, served at the root of the context. Each is checked and
	 * written as {@link asset.pipeline.AssetPaths#rootPaths} returns it, and registered as an exact url pattern.
	 */
	void setRootPaths(final Collection<String> rootPaths) {
		assetPipelineDevFilterCore.rootPaths = rootPaths
	}

	@Override
	void init(final FilterConfig filterConfig) throws ServletException {
		assetPipelineDevFilterCore.servletContext = filterConfig.getServletContext()
	}

	@Override
	void destroy() {
	}

	@Override
	void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {
		assetPipelineDevFilterCore.doFilter(request, response, chain)
	}
}
