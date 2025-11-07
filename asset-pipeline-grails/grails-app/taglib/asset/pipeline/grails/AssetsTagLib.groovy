package asset.pipeline.grails

import asset.pipeline.AssetHelper
import asset.pipeline.AssetPipeline
import asset.pipeline.AssetPipelineConfigHolder
import grails.core.GrailsApplication
import org.grails.buffer.GrailsPrintWriter
import groovy.util.logging.Slf4j

@Slf4j
class AssetsTagLib {

	static namespace = 'asset'
	static returnObjectForTags = ['assetPath']

	static final ASSET_REQUEST_MEMO = "asset-pipeline.memo"
	private static final LINE_BREAK = System.getProperty('line.separator') ?: '\n'

	// WebJar support - lazy initialization and caching
	@Lazy
	private Object webJarLocator = initializeWebJarLocator()
	private static final Map<String, String> WEBJAR_CACHE = [:].asSynchronized()

	GrailsApplication grailsApplication
	def assetProcessorService


	/**
	 * @attr src REQUIRED
	 * @attr asset-defer OPTIONAL ensure script blocks are deferred to when the deferrred-scripts is used
	 * @attr uniq OPTIONAL Output the script tag for the given resource only once per request, note that uniq mode cannot be bundled
	 */
	def javascript = {final attrs ->
		final GrailsPrintWriter outPw = out
		attrs.remove('href')
		element(attrs, 'js', 'application/javascript', null) {final String src, final String queryString, final outputAttrs, final String endOfLine, final boolean useManifest ->
			if(attrs.containsKey('asset-defer')) {
				script(outputAttrs + [type: attrs.type ?: "text/javascript", src: assetPath(src: src, useManifest: useManifest) + queryString],'')
			} else {
				outPw << '<script type="' << (attrs.type ? attrs.type : 'text/javascript') << '" src="' << assetPath(src: src, useManifest: useManifest) << queryString << '" ' << paramsToHtmlAttr(outputAttrs) << '></script>' << endOfLine
			}

		}
	}

	/**
	 * At least one of {@code href} and {@code src} must be supplied
	 *
	 * @attr href OPTIONAL standard URL attribute
	 * @attr src  OPTIONAL alternate URL attribute, only used if {@code href} isn't supplied, or if {@code href} is Groovy false
	 * @attr uniq OPTIONAL Output the stylesheet tag for the resource only once per request, note that uniq mode cannot be bundled
	 */
	def stylesheet = {final attrs ->
		final GrailsPrintWriter outPw = out
		element(attrs, 'css', 'text/css', Objects.toString(attrs.remove('href'), null)) {final String src, final String queryString, final outputAttrs, final String endOfLine, final boolean useManifest ->
			outPw << '<link rel="stylesheet" href="' << assetPath(src: src, useManifest: useManifest) << queryString << '" ' << paramsToHtmlAttr(outputAttrs) << '/>'
			if (endOfLine) {
				outPw << endOfLine
			}
		}
	}

	private boolean isIncluded(def path) {
		HashSet<String> memo = request."$ASSET_REQUEST_MEMO"
		if (memo == null) {
			memo = new HashSet<String>()
			request."$ASSET_REQUEST_MEMO" = memo
		}
		!memo.add(path)
	}

	private static def nameAndExtension(String src, String ext) {
		int lastDotIndex = src.lastIndexOf('.')
		if (lastDotIndex >= 0) {
			[uri: src.substring(0, lastDotIndex), extension: src.substring(lastDotIndex + 1)]
		} else {
			[uri: src, extension: ext]
		}
	}

	private void element(final attrs, final String ext, final String contentType, final String srcOverride, final Closure<GrailsPrintWriter> output) {
		def src = attrs.remove('src')
		if (srcOverride) {
			src = srcOverride
		}

		// Resolve webjar version if needed
		src = resolveWebjarPath(src)

		def uniqMode = attrs.remove('uniq') != null

		src = "${AssetHelper.nameWithoutExtension(src)}.${ext}"
		def conf = grailsApplication.config.getProperty('grails.assets', Map,[:])
		Boolean bundle = grailsApplication.config.getProperty('grails.assets.bundle',Boolean,false)
		final def nonBundledMode = uniqMode || (!AssetPipelineConfigHolder.manifest && bundle != true && attrs.remove('bundle') != 'true')
		
		if (! nonBundledMode) {
			output(src, '', attrs, '', true)
		}
		else {
			def name = nameAndExtension(src, ext)
			final String uri = name.uri
			final String extension = name.extension

			final String queryString =
				attrs.charset \
					? "?compile=false&encoding=${attrs.charset}"
					: '?compile=false'
			if (uniqMode && isIncluded(name)) {
				return
			}
			def useManifest = !nonBundledMode

			AssetPipeline.getDependencyList(uri, contentType, extension)?.each {
				if (uniqMode) {
					def path = nameAndExtension(it.path, ext)
					if (path.uri == uri || !isIncluded(path)) {
						output(it.path, queryString, attrs, LINE_BREAK, useManifest)
					}
				} else {
					output(it.path, queryString, attrs, LINE_BREAK, useManifest)
				}
			}
		}
	}

	def image = {attrs ->
		def src = attrs.remove('src')
		def absolute = attrs.remove('absolute')
		out << "<img src=\"${assetPath(src:src, absolute: absolute)}\" ${paramsToHtmlAttr(attrs)}/>"
	}


	/**
	 * @attr href REQUIRED
	 * @attr rel REQUIRED
	 * @attr type OPTIONAL
	 */
	def link = {attrs ->
		def href = attrs.remove('href')
		out << "<link ${paramsToHtmlAttr(attrs)} href=\"${assetPath(src:href)}\"/>"
	}


	def script = {attrs, body ->
		def assetBlocks = request.getAttribute('assetScriptBlocks')
		if (!assetBlocks) {
			assetBlocks = []
		}
		assetBlocks << [attrs: attrs, body: body()]
		request.setAttribute('assetScriptBlocks', assetBlocks)
	}

	def deferredScripts = {attrs ->
		def assetBlocks = request.getAttribute('assetScriptBlocks')
		if (!assetBlocks) {
			return
		}
		assetBlocks.each {assetBlock ->
			out << "<script ${paramsToHtmlAttr(assetBlock.attrs)}>${assetBlock.body}</script>"
		}
	}


	def assetPath = {attrs ->
		g.assetPath(attrs)
	}

	def assetPathExists = {attrs, body ->
		if (isAssetPath(attrs.remove('src'))) {
			out << (body() ?: true)
		}
		else {
			out << ''
		}
	}

	boolean isAssetPath(src) {
		assetProcessorService.isAssetPath(src)
	}

	private paramsToHtmlAttr(attrs) {
		attrs.collect {key, value -> "${key}=\"${value.toString().replace('"', '\\"')}\""}?.join(' ')
	}

	/**
	 * Initializes WebJarAssetLocator if available on classpath.
	 * Returns null if webjars-locator-core is not present.
	 */
	private Object initializeWebJarLocator() {
		try {
			Class<?> locatorClass = Class.forName('org.webjars.WebJarAssetLocator')
			return locatorClass.getDeclaredConstructor().newInstance()
		} catch (ClassNotFoundException | NoClassDefFoundError e) {
			log.debug("WebJar locator not available - version resolution disabled")
			return null
		}
	}

	/**
	 * Resolves a webjar path by automatically detecting the version.
	 *
	 * IMPORTANT: Do NOT include the package name in the path. The locator searches
	 * across all webjars for the matching file path.
	 *
	 * Examples:
	 *   Input:  webjars/dist/jquery.min.js
	 *   Output: webjars/jquery/3.7.1/dist/jquery.min.js
	 *
	 *   Input:  webjars/dist/css/bootstrap.css
	 *   Output: webjars/bootstrap/5.3.0/dist/css/bootstrap.css
	 *
	 * Non-webjar paths are returned unchanged:
	 *   Input:  js/application.js
	 *   Output: js/application.js
	 *
	 * Paths that already have versions are returned unchanged:
	 *   Input:  webjars/jquery/3.7.1/dist/jquery.min.js
	 *   Output: webjars/jquery/3.7.1/dist/jquery.min.js
	 *
	 * @param path Original path (may or may not include version)
	 * @return Resolved path with version, or original path if not a webjar or already versioned
	 */
	private String resolveWebjarPath(String path) {
		if (!path) {
			return path
		}

		// Only process webjar paths
		if (!path.startsWith('webjars/')) {
			return path
		}

		// Check if already has version (pattern: webjars/package/1.2.3/...)
		// Match version like: 1.2.3, 1.0.0-alpha1, 5.3.0-beta.2, etc.
		if (path =~ /webjars\/[^\/]+\/\d+\.\d+[^\/]*\//) {
			log.debug("Path already contains version: ${path}")
			return path
		}

		// Check cache first
		if (WEBJAR_CACHE.containsKey(path)) {
			return WEBJAR_CACHE[path]
		}

		// Resolve version using WebJarAssetLocator (if available)
		if (webJarLocator) {
			try {
				// Remove 'webjars/' prefix - locator expects path without it
				def partialPath = path.substring(8)

				// WebJarAssetLocator.getFullPath() returns: META-INF/resources/webjars/jquery/3.7.1/dist/jquery.js
				// Need to strip META-INF/resources/ prefix
				def resolvedPath = webJarLocator.getFullPath(partialPath)
				if (resolvedPath.startsWith("META-INF/resources/")) {
					resolvedPath = resolvedPath.substring(19) // Remove "META-INF/resources/"
				}
				def fullPath = resolvedPath

				// Cache the resolved path
				WEBJAR_CACHE[path] = fullPath

				log.debug("Resolved webjar path: ${path} -> ${fullPath}")
				return fullPath

			} catch (Exception e) {
				log.debug("Could not resolve webjar path: ${path}. ${e.message}")
			}
		}

		return path
	}

	/**
	 * Utility method to clear the webjar resolution cache.
	 * Useful for development when switching webjar versions.
	 */
	void clearWebJarCache() {
		WEBJAR_CACHE.clear()
		log.info("Cleared webjar path resolution cache")
	}
}
