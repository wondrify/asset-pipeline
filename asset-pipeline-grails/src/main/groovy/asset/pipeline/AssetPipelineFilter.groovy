package asset.pipeline

import asset.pipeline.grails.AssetAttributes
import asset.pipeline.grails.AssetProcessorService
import asset.pipeline.grails.ProductionAssetCache
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.servlet.FilterChain
import jakarta.servlet.FilterConfig
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletException
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.ApplicationContext
import org.springframework.core.io.Resource
import org.springframework.web.context.support.WebApplicationContextUtils
import org.springframework.web.filter.OncePerRequestFilter

@Slf4j
@CompileStatic
class AssetPipelineFilter extends OncePerRequestFilter {

	static final indexFile = 'index.html'

	// What the cache answers for a url it recorded as matching no asset
	private static final AssetAttributes MISSING = new AssetAttributes(false, false, false, null, null, null, null, null)

	private static volatile ProductionAssetCache latestCache = new ProductionAssetCache()

	/**
	 * The cache of the filter created last, which in an application with one filter is that filter's.
	 *
	 * @deprecated each filter has its own cache; use {@link #getCache()}
	 */
	@Deprecated
	static ProductionAssetCache getFileCache() {
		latestCache
	}

	// This filter's own, so a context started again in the same JVM starts with an empty one.
	// Sized by initFilterBean() from grails.assets.
	final ProductionAssetCache cache = new ProductionAssetCache()

	ApplicationContext applicationContext
	ServletContext     servletContext
	/** The urls outside the mapping that name an asset, each without its leading slash, as AssetPaths.rootPaths reads them */
	Collection<String> rootPaths = []

	// Read as the registrar reads them, so that a filter an application sets up itself may write /favicon.ico
	void setRootPaths(final Collection<String> rootPaths) {
		this.rootPaths = new LinkedHashSet<String>(AssetPaths.rootPaths(rootPaths, null))
	}

	AssetPipelineFilter() {
		latestCache = cache
	}

	@Override
	void initFilterBean() throws ServletException {
		// The plugin fills the holder before Spring creates any bean, and sizing the cache again when
		// the servlet container starts the filter changes nothing
		cache.maximumSize = ProductionAssetCache.maximumSizeOf(AssetPipelineConfigHolder.config)
		// Read now, so a pattern that cannot be read stops the application rather than the first request
		AssetPaths.immutable(AssetPipelineConfigHolder.config.get('immutable'))
		if(!rootPaths) {
			// A filter the application registers itself has not been given them, as the registrar's has
			setRootPaths(AssetPaths.rootPaths(AssetPipelineConfigHolder.config.get('rootPaths'), null))
		}

		// GenericFilterBean implements InitializingBean, so when this filter is a container-managed
		// bean (a nested bean definition of the FilterRegistrationBean) Spring calls this from
		// afterPropertiesSet() - long before the servlet container supplies a FilterConfig.
		// Resolve the ServletContext from whichever source is available and simply do nothing when
		// neither is: init(FilterConfig) calls this method again once the container has started.
		final FilterConfig config = getFilterConfig()
		final ServletContext context = config != null ? config.servletContext : servletContext
		if (context == null) {
			return
		}
		servletContext = context
		final ApplicationContext webApplicationContext = WebApplicationContextUtils.getWebApplicationContext(context)
		if (webApplicationContext != null) {
			applicationContext = webApplicationContext
		}
	}

	@Override
	void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain) throws IOException, ServletException {
		final boolean warDeployed = AssetPipelineConfigHolder.manifest ? true : false
		boolean skipNotFound = AssetPipelineConfigHolder.config.skipNotFound || AssetPipelineConfigHolder.config.mapping == ''
		final String mapping = ((AssetProcessorService)(applicationContext.getBean('assetProcessorService', AssetProcessorService))).assetMapping

		final String path = AssetPaths.pathWithinContext(request.requestURI, request.contextPath)
		final AssetPaths.AssetUrl assetUrl = AssetPaths.assetUrl(request.method, path, mapping, rootPaths)
		if(assetUrl == null) {
			// Registered for more than the mapping and the root paths, the filter leaves every other url to the application
			chain.doFilter(request, response)
			return
		}
		String fileUri = assetUrl.path
		if(assetUrl.rootPath) {
			// One of the rootPaths, which name an asset from the root of the context. The application may answer
			// such a url itself, so a missing asset passes the request on rather than ending it.
			skipNotFound = true
		}

		final String format       = servletContext.getMimeType(path)
		final String encoding     = request.getParameter('encoding') ?: request.getCharacterEncoding()

		String classRegistryKey = AssetPipelineConfigHolder.classLoaderKeyForUri(fileUri)

		if(classRegistryKey) {
			AssetPipelineClassLoaderEntry classLoaderEntry = AssetPipelineConfigHolder.classLoaderRegistry[classRegistryKey]
			fileUri = fileUri.substring(classRegistryKey.length())
			final Properties manifest = classLoaderEntry.manifest
			String manifestPath = fileUri
			if(fileUri == '' || fileUri.endsWith('/')) {
				fileUri += indexFile
			}
			if(fileUri.startsWith('/')) {
				manifestPath = fileUri.substring(1) //Omit forward slash
			}
			manifestPath = AssetHelper.normalizePath(manifestPath) //JETTY Security bug, we MUST prevent reverse traversal
			fileUri = manifest?.getProperty(manifestPath, manifestPath)
			URL file = classLoaderEntry.classLoader.getResource("assets/${fileUri}")
			if(file) {
				final AssetPipelineResponseBuilder responseBuilder = new AssetPipelineResponseBuilder(
					manifestPath,
					AssetPipelineResponseBuilder.combineIfNoneMatchHeaders(request.getHeaders('If-None-Match')),
					request.getHeader('If-Modified-Since'),
					null,
					manifest
				)
				if(responseBuilder.statusCode) {
					response.status = responseBuilder.statusCode
				}
				responseBuilder.headers.each { final header ->
					response.setHeader(header.key, header.value)
				}
				URL gzipFile = classLoaderEntry.classLoader.getResource("assets/${fileUri}.gz")
				if(response.status != 304) {
					// Check for GZip
					if(AssetPipelineResponseBuilder.acceptsGzip(request.getHeaders('Accept-Encoding'))) {
						if(gzipFile) {
							file = gzipFile
							response.setHeader('Content-Encoding', 'gzip')
						}
					}
					if(encoding) {
						response.setCharacterEncoding(encoding)
					}
					response.setContentType(format)
					// response.setHeader('Content-Length', String.valueOf(file.contentLength()))
					final InputStream inputStream
					try {
						final byte[] buffer = new byte[102400]
						int len
						URLConnection fileConnection = file.openConnection()
						fileConnection.setUseCaches(false)
						inputStream = fileConnection.getInputStream()
						final ServletOutputStream out = response.outputStream
						while((len = inputStream.read(buffer)) != -1) {
							out.write(buffer, 0, len)
						}
						response.flushBuffer()
					} catch(final e) {
						log.debug("File Transfer Aborted (Probably by the user)", e)
					} finally {
						try { inputStream?.close() } catch(final ie) { /* silent fail */ }
					}
				} else {
					response.flushBuffer()
				}
			} else {
				if(!skipNotFound){
					response.status = 404
					response.flushBuffer()
				}
			}

		} else if(warDeployed) {
			final Properties manifest = AssetPipelineConfigHolder.manifest
			String manifestPath = fileUri
			if(fileUri == '' || fileUri.endsWith('/')) {
				fileUri += indexFile
			}
			if(fileUri.startsWith('/')) {
				manifestPath = fileUri.substring(1) //Omit forward slash
			}
			manifestPath = AssetHelper.normalizePath(manifestPath) //JETTY Security bug, we MUST prevent reverse traversal
			fileUri = manifest?.getProperty(manifestPath, manifestPath)



			final AssetAttributes attributeCache = cache.get(fileUri) ?: (cache.isMissing(fileUri) ? MISSING : null)

			if(attributeCache) {
				if(attributeCache.exists()) {
					Resource file = attributeCache.resource
					final AssetPipelineResponseBuilder responseBuilder = new AssetPipelineResponseBuilder(
						manifestPath,
						AssetPipelineResponseBuilder.combineIfNoneMatchHeaders(request.getHeaders('If-None-Match')),
						request.getHeader('If-Modified-Since'),
						attributeCache.getLastModified()
					)

					responseBuilder.headers.each { final header ->
						response.setHeader(header.key, header.value)
					}

					if(responseBuilder.statusCode) {
						response.status = responseBuilder.statusCode
					}

					if(response.status != 304) {
						if(AssetPipelineResponseBuilder.acceptsGzip(request.getHeaders('Accept-Encoding')) && attributeCache.gzipExists()) {
							file = attributeCache.getGzipResource()
							response.setHeader('Content-Encoding', 'gzip')
							response.setHeader('Content-Length', attributeCache.getGzipFileSize().toString())
						} else {
							response.setHeader('Content-Length', attributeCache.getFileSize().toString())
						}
						if(encoding) {
							response.setCharacterEncoding(encoding)
						}

						response.setContentType(format)
						final InputStream inputStream
						try {
							final byte[] buffer = new byte[102400]
							int len
							inputStream = file.inputStream
							final ServletOutputStream out = response.outputStream
							while((len = inputStream.read(buffer)) != -1) {
								out.write(buffer, 0, len)
							}
							response.flushBuffer()
						} catch(final e) {
							log.debug("File Transfer Aborted (Probably by the user)", e)
						} finally {
							try { inputStream?.close() } catch(final ie) { /* silent fail */ }
						}
					} else {
						response.flushBuffer()
					}
				} else {
					if(!skipNotFound){
						response.status = 404
						response.flushBuffer()
					}
				}
			} else {
				Resource file = applicationContext.getResource("assets/${fileUri}")
				if(!file.exists()) {
					file = applicationContext.getResource("classpath:assets/${fileUri}")
				}

				if(file.exists()) {
					final AssetPipelineResponseBuilder responseBuilder = new AssetPipelineResponseBuilder(
						manifestPath,
						AssetPipelineResponseBuilder.combineIfNoneMatchHeaders(request.getHeaders('If-None-Match')),
						request.getHeader('If-Modified-Since'),
						file.lastModified() ? new Date(file.lastModified()) : null
					)

					if(responseBuilder.statusCode) {
						response.status = responseBuilder.statusCode
					}
					responseBuilder.headers.each { final header ->
						response.setHeader(header.key, header.value)
					}

					Resource gzipFile = applicationContext.getResource("assets/${fileUri}.gz")
					if(!gzipFile.exists()) {
						gzipFile = applicationContext.getResource("classpath:assets/${fileUri}.gz")
					}
					final Date lastModifiedDate = file.lastModified() ? new Date(file.lastModified()) : null

					final AssetAttributes newCache = new AssetAttributes(
						true,
						gzipFile.exists(),
						false,
						file.contentLength(),
						gzipFile.exists() ? gzipFile.contentLength() : null,
						lastModifiedDate,
						file,
						gzipFile
					)
					cache.put(fileUri, newCache)

					if(response.status != 304) {
						// Check for GZip
						if(AssetPipelineResponseBuilder.acceptsGzip(request.getHeaders('Accept-Encoding'))) {
							if(gzipFile.exists()) {
								file = gzipFile
								response.setHeader('Content-Encoding', 'gzip')
							}
						}
						if(encoding) {
							response.setCharacterEncoding(encoding)
						}
						response.setContentType(format)
						response.setHeader('Content-Length', String.valueOf(file.contentLength()))
						final InputStream inputStream
						try {
							final byte[] buffer = new byte[102400]
							int len
							inputStream = file.inputStream
							final ServletOutputStream out = response.outputStream
							while((len = inputStream.read(buffer)) != -1) {
								out.write(buffer, 0, len)
							}
							response.flushBuffer()
						} catch(final e) {
							log.debug("File Transfer Aborted (Probably by the user)", e)
						} finally {
							try { inputStream?.close() } catch(final ie) { /* silent fail */ }
						}
					} else {
						response.flushBuffer()
					}
				} else {
					cache.putMissing(fileUri)
					if(!skipNotFound){
						response.status = 404
						response.flushBuffer()
					}
				}
			}
		} else {
			if(fileUri == '' || fileUri.endsWith('/')) {
				fileUri += indexFile
			}
			final byte[] fileContents
			if(request.getParameter('compile') == 'false') {
				fileContents = AssetPipeline.serveUncompiledAsset(fileUri, format, null, encoding)
			} else {
				fileContents = AssetPipeline.serveAsset(fileUri, format, null, encoding)
			}

			if(fileContents != null) {
				response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate") // HTTP 1.1.
				response.setHeader("Pragma", "no-cache") // HTTP 1.0.
				response.setDateHeader("Expires", 0) // Proxies.
				response.setHeader('Content-Length', String.valueOf(fileContents.size()))

				response.setContentType(format)
				try {
					response.outputStream << fileContents
					response.flushBuffer()
				} catch(final e) {
					log.debug("File Transfer Aborted (Probably by the user)", e)
				}
			} else {
				if(!skipNotFound) {
					response.status = 404
					response.flushBuffer()
				}				
			}
		}

		if(!response.committed) {
			chain.doFilter(request, response)
		}
	}
}
