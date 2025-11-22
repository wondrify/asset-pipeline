package asset.pipeline.grails

import asset.pipeline.AssetHelper
import asset.pipeline.AssetPipeline
import asset.pipeline.AssetPipelineConfigHolder
import grails.core.GrailsApplication
import org.grails.buffer.GrailsPrintWriter

class AssetsTagLib {

	static namespace = 'asset'
	static returnObjectForTags = ['assetPath']

	static final ASSET_REQUEST_MEMO = "asset-pipeline.memo"
	private static final LINE_BREAK = System.getProperty('line.separator') ?: '\n'

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
}
