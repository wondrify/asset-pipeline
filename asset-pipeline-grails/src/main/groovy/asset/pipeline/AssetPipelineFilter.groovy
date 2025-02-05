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

	static final ProductionAssetCache fileCache = new ProductionAssetCache()
	static final indexFile = 'index.html'

	ApplicationContext applicationContext
	ServletContext     servletContext


	@Override
	void initFilterBean() throws ServletException {
		final FilterConfig config = filterConfig
		applicationContext = WebApplicationContextUtils.getWebApplicationContext(config.servletContext)
		servletContext = config.servletContext
	}

	@Override
	void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain) throws IOException, ServletException {
		final boolean warDeployed = AssetPipelineConfigHolder.manifest ? true : false
		final boolean skipNotFound = AssetPipelineConfigHolder.config.skipNotFound || AssetPipelineConfigHolder.config.mapping == ''
		final String mapping = ((AssetProcessorService)(applicationContext.getBean('assetProcessorService', AssetProcessorService))).assetMapping

		String fileUri = new URI(request.requestURI).path

		final String baseAssetUrl = request.contextPath == "/" ? "/$mapping" : "${request.contextPath}/${mapping}"

		final String format       = servletContext.getMimeType(fileUri)
		final String encoding     = request.getParameter('encoding') ?: request.getCharacterEncoding()

		if(fileUri.startsWith(baseAssetUrl)) {
			fileUri = fileUri.substring(baseAssetUrl.length())
		}

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
					request.getHeader('If-None-Match'),
					request.getHeader('If-Modified-Since'),
					null
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
					final String acceptsEncoding = request.getHeader("Accept-Encoding")
					if(acceptsEncoding?.tokenize(",")?.contains("gzip")) {
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



			final AssetAttributes attributeCache = fileCache.get(fileUri)

			if(attributeCache) {
				if(attributeCache.exists()) {
					Resource file = attributeCache.resource
					final AssetPipelineResponseBuilder responseBuilder = new AssetPipelineResponseBuilder(
						manifestPath,
						request.getHeader('If-None-Match'),
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
						final String acceptsEncoding = request.getHeader("Accept-Encoding")
						if(acceptsEncoding?.tokenize(", ")?.contains("gzip") && attributeCache.gzipExists()) {
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
						request.getHeader('If-None-Match'),
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
					fileCache.put(fileUri, newCache)

					if(response.status != 304) {
						// Check for GZip
						final String acceptsEncoding = request.getHeader("Accept-Encoding")
						if(acceptsEncoding?.tokenize(",")?.contains("gzip")) {
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
					final AssetAttributes newCache = new AssetAttributes(false, false, false, null, null, null, null, null)
					fileCache.put(fileUri, newCache)
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
