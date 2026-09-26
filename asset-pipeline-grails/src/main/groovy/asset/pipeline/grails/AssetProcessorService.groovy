package asset.pipeline.grails

import asset.pipeline.AssetHelper
import asset.pipeline.AssetPipelineClassLoaderEntry
import asset.pipeline.AssetPipelineConfigHolder
import grails.core.GrailsApplication
import grails.core.support.GrailsApplicationAware
import grails.util.Environment
import grails.web.mapping.LinkGenerator
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.grails.config.NavigableMap
import org.grails.web.mapping.DefaultLinkGenerator
import org.grails.web.servlet.mvc.GrailsWebRequest

import java.util.concurrent.ConcurrentHashMap

import static asset.pipeline.AssetPipelineConfigHolder.manifest
import static asset.pipeline.grails.UrlBase.*
import static asset.pipeline.grails.utils.net.HttpServletRequests.getBaseUrlWithScheme
import static asset.pipeline.grails.utils.text.StringBuilders.ensureEndsWith
import static asset.pipeline.utils.net.Urls.hasAuthority
import static org.grails.web.servlet.mvc.GrailsWebRequest.lookup

@Slf4j
class AssetProcessorService implements GrailsApplicationAware {

	GrailsApplication grailsApplication
	LinkGenerator grailsLinkGenerator

	/**
	 * Retrieves the asset path from the property [grails.assets.mapping] which is used by the url mapping and the
	 * taglib.  The property cannot contain <code>/</code>, and must be one level deep
	 *
	 * @return the path
	 * @throws IllegalArgumentException if the path contains <code>/</code>
	 */
	String getAssetMapping() {
		final String mapping = AssetPipelineConfigHolder.config.containsKey('mapping') ? AssetPipelineConfigHolder.config['mapping'] : 'assets'
		// final String mapping = grailsApplication.config?.grails?.assets?.mapping ?: 'assets'
		if (mapping.contains('/')) {
			throw new IllegalArgumentException(
				'The property [grails.assets.mapping] can only be one level deep.  ' +
				"For example, 'foo' and 'bar' would be acceptable values, but 'foo/bar' is not"
			)
		}
		return mapping
	}

	String getAssetPath(final String path, final boolean useManifest) {
		getAssetPath(path, grailsApplication.config.getProperty('grails.assets',Map,[:]), useManifest)
	}

	String getAssetPath(final String path, final Map conf = grailsApplication.config.getProperty('grails.assets',Map,[:]), final boolean useManifest = true) {
		final String relativePath = trimLeadingSlash(path)
		if (!manifest) {
			return resolveWildcardPath(relativePath)
		}
		if (useManifest) {
			return resolveManifestProperty(relativePath) ?: relativePath
		} else {
			return relativePath
		}
	}


	/**
	 * Without a manifest, as in development, a path with a wildcard such as
	 * {@code webjars/marked/%/lib/marked.umd.js} names the file the resolvers find, which is what
	 * {@code <asset:javascript>} already does through its dependency list and what the manifest does in
	 * production. Left as written, the wildcard reached the browser, and a {@code %} that does not start a
	 * percent-escape makes the request invalid (400).
	 */
	private static String resolveWildcardPath(final String relativePath) {
		if (!relativePath || !AssetHelper.isWildcardPath(relativePath)) {
			return relativePath
		}
		return AssetHelper.fileForFullName(relativePath)?.path ?: relativePath
	}


	String getResolvedAssetPath(final String path, final Map conf = grailsApplication.config.getProperty('grails.assets',Map,[:])) {
		final String relativePath = trimLeadingSlash(path)
		if(manifest) {
			if(relativePath) {
				return resolveManifestProperty(relativePath)
			} else {
				return path
			}
		} else {
			return AssetHelper.fileForFullName(relativePath) != null ? relativePath : null
		}
	}


	boolean isAssetPath(final String path) {
		final String relativePath = trimLeadingSlash(path)
		if(!relativePath) {
			return false
		}

		String classRegistryKey = AssetPipelineConfigHolder.classLoaderKeyForUri(path)
		if(classRegistryKey) {
			AssetPipelineClassLoaderEntry classLoaderEntry = AssetPipelineConfigHolder.classLoaderRegistry[classRegistryKey]
			String registryRelativePath = path.substring(classRegistryKey.length())
			final Properties manifest = classLoaderEntry.manifest
			if(registryRelativePath.startsWith('/')) {
				registryRelativePath = registryRelativePath.substring(1) //Omit forward slash
			}
			return manifest?.getProperty(registryRelativePath) ? true : false
		} else {
			return (manifest ? manifest.getProperty(relativePath) : AssetHelper.fileForFullName(relativePath) != null)
		}	

		
	}


	String asset(final Map attrs, final DefaultLinkGenerator linkGenerator) {
		String url = getResolvedAssetPath(attrs.file ?: attrs.src)

		if (! url) {
			return null
		}

		url = assetBaseUrl(null, NONE) + url
		if (! hasAuthority(url)) {
			String absolutePath = linkGenerator.handleAbsolute(attrs)

			if (absolutePath == null) {
				final String contextPath = attrs.contextPath?.toString() ?: linkGenerator.contextPath
				absolutePath = contextPath ?: linkGenerator.handleAbsolute(attrs) ?: ''
			}
			url = absolutePath + url
		}

		return url
	}


	String getConfigBaseUrl(final HttpServletRequest req, final Map conf = grailsApplication.config.getProperty('grails.assets',Map,[:])) {
		final def url = conf.url
		if(url instanceof Closure) {
			return url(req)
		}
		return url ?: null
	}

	static String trimToEmpty(final String str) {
		return str == null ? "" : str.trim()
	}

	String assetBaseUrl(final HttpServletRequest req, final UrlBase urlBase, final NavigableMap conf = grailsApplication.config.getProperty('grails.assets',Map,[:])) {
		Map map = conf.toFlatConfig()
		final String url = getConfigBaseUrl(req, map)
		if (url) {
			return url
		}

		final String mapping = assetMapping

		String baseUrl
		switch (urlBase) {
			case SERVER_BASE_URL:
				baseUrl = grailsLinkGenerator.serverBaseURL ?: ''
				break
			case CONTEXT_PATH:
				baseUrl = trimToEmpty(grailsLinkGenerator.contextPath)
				break
			case NONE:
				baseUrl = ''
				break
		}

		final String finalUrl = ensureEndsWith(new StringBuilder(baseUrl.length() + mapping.length() + 2).append(baseUrl), '/' as char)
			.append(mapping)
			.append('/' as char)
			.toString()

		return finalUrl
	}


	String makeServerURL(final DefaultLinkGenerator linkGenerator) {
		String serverUrl = linkGenerator.configuredServerBaseURL
		if (! serverUrl) {
			final GrailsWebRequest req = lookup()
			if (req) {
				serverUrl = getBaseUrlWithScheme(req.currentRequest).toString()
				if (!serverUrl && !Environment.isWarDeployed()) {
					serverUrl = "http://localhost:${System.getProperty('server.port') ?: '8080'}${linkGenerator.contextPath ?: ''}"
				}
			}
		}
		return serverUrl
	}


	private static String trimLeadingSlash(final String s) {
		if(!s || s[0] != '/') {
			return s
		}
		return s.substring(1)
	}

	private ConcurrentHashMap<String,String> manifestWildcardCache = new ConcurrentHashMap<>()

	private resolveManifestProperty(String path) {
		if(manifest) {
			String result = manifest.getProperty(path)
			if(result == null && (AssetHelper.isWildcardPath(path))) {
				result = manifestWildcardCache.get(path)
				if(result == null) {
					// The file a resolver picks for the same path in development. A miss is kept as the path itself,
					// which is what it returns, so a tag in a layout does not search the manifest on every page.
					String entryKey = AssetHelper.resolveWildcardPath(path, manifest.stringPropertyNames())
					result = entryKey != null ? manifest.getProperty(entryKey) : path
					manifestWildcardCache.put(path, result)
				}
				return result ?: path
			}
			return result
		}
		return null
	}
}
