package asset.pipeline.springboot

import asset.pipeline.servlet.AssetPipelineDevFilterCore
import groovy.util.logging.Slf4j
import jakarta.servlet.*

@Slf4j
class AssetPipelineDevFilter implements Filter {

	AssetPipelineDevFilterCore assetPipelineDevFilterCoreStandalone = new AssetPipelineDevFilterCore()


	/** The validated assets.rootPaths, the urls outside /assets that name an asset */
	void setRootPaths(final Collection<String> rootPaths) {
		assetPipelineDevFilterCoreStandalone.rootPaths = rootPaths
	}

	@Override
	void init(final FilterConfig config) throws ServletException {
		assetPipelineDevFilterCoreStandalone.servletContext = config.servletContext
		assetPipelineDevFilterCoreStandalone.mapping = AssetPipelineService.MAPPING
	}

	@Override
	void destroy() {
	}

	@Override
	void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {
		assetPipelineDevFilterCoreStandalone.doFilter(request, response, chain)
	}
}
