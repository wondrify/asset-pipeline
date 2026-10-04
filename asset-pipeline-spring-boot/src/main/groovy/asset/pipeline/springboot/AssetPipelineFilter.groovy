package asset.pipeline.springboot

import asset.pipeline.servlet.AssetPipelineFilterCore
import asset.pipeline.servlet.AssetPipelineServletResource
import asset.pipeline.servlet.AssetPipelineServletResourceRepository
import groovy.util.logging.Slf4j
import jakarta.servlet.*
import org.springframework.core.io.Resource
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.support.WebApplicationContextUtils

@Slf4j
class AssetPipelineFilter implements Filter {

	AssetPipelineFilterCore assetPipelineFilterCore = new AssetPipelineFilterCore()


	/** The validated assets.rootPaths, the urls outside /assets that name an asset */
	void setRootPaths(final Collection<String> rootPaths) {
		assetPipelineFilterCore.rootPaths = rootPaths
	}

	@Override
	void init(final FilterConfig config) throws ServletException {
		assetPipelineFilterCore.servletContext = config.servletContext
		assetPipelineFilterCore.mapping = AssetPipelineService.MAPPING

		final WebApplicationContext applicationContext = WebApplicationContextUtils.getWebApplicationContext(config.servletContext)
		assetPipelineFilterCore.assetPipelineServletResourceRepository = new SpringServletResourceRepository(applicationContext)
	}

	@Override
	void destroy() {
	}

	@Override
	void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {
		assetPipelineFilterCore.doFilter(request, response, chain)
	}

	private static final class SpringServletResourceRepository implements AssetPipelineServletResourceRepository {

		private final WebApplicationContext applicationContext


		SpringServletResourceRepository(final WebApplicationContext applicationContext) {
			this.applicationContext = applicationContext
		}


		@Override
		AssetPipelineServletResource getResource(final String path) {
			def resource = applicationContext.getResource("classpath:assets/${path}")
			if(!resource.exists()) {
				resource = applicationContext.getResource("assets/${path}")
			}
			return SpringServletResource.create(resource)
		}

		@Override
		AssetPipelineServletResource getGzippedResource(final String path) {
			def resource = applicationContext.getResource("classpath:assets/${path}.gz")
			if(!resource.exists()) {
				resource = applicationContext.getResource("assets/${path}.gz")
			}
			return SpringServletResource.create(resource)
		}
	}

	private static final class SpringServletResource implements AssetPipelineServletResource {

		private final Resource resource


		private SpringServletResource(final Resource resource) {
			this.resource = resource
		}


		static SpringServletResource create(final Resource resource) {
			if(!resource.exists()) {
				return null
			}

			new SpringServletResource(resource)
		}

		@Override
		Long getLastModified() {
			return resource.lastModified()
		}

		@Override
		InputStream getInputStream() {
			return resource.getInputStream()
		}
	}
}
