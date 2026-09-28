/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package asset.pipeline.processors

import asset.pipeline.AssetCompiler
import asset.pipeline.AssetFile
import asset.pipeline.AssetHelper
import asset.pipeline.DirectiveProcessor
import asset.pipeline.GenericAssetFile
import groovy.util.logging.Slf4j

import java.util.regex.Matcher
import java.util.regex.Pattern


/**
 * Points the relative specifiers of ES module imports at the files the compiler writes, the way
 * {@link CssProcessor} rewrites {@code url()}.
 *
 * The browser resolves {@code import './util.js'} next to the importing module. Once compiled, that module
 * is served as {@code main-<digest>.js}, but {@code ./util.js} still names the plain file, and a static copy
 * of the compiled assets (a CDN bucket, with {@code skipNonDigests} on) has no plain file to answer with.
 * Rewritten to {@code ./util-<digest>.js}, every module points at a file that exists, and each can be cached
 * for good.
 *
 * Covers static imports, re-exports ({@code export … from}), side-effect imports and {@code import()} of a
 * string literal. Only specifiers that start with {@code ./} or {@code ../} are rewritten: a bare specifier
 * belongs to an import map, and an absolute URL is the author's. An import whose target is not an asset is
 * left alone.
 *
 * Runs only when compiling with digests. In development every file is served under its own name. It also
 * runs after the CommonJS processors, so a file Babel has turned into {@code require()} calls has no imports
 * left and passes through unchanged.
 *
 * Imports that form a cycle keep their plain names. A digest names content, so a module's name would depend
 * on the content of a module whose content contains that name. A module's content is its own source plus
 * every file its {@code //= require} directives and {@code require()} calls bundle into it, so an import from
 * A to B is rewritten only when nothing B imports or bundles leads back to A, directly or through other
 * modules. That depends on the source files alone, so a module compiles to the same content whichever module
 * the compiler reaches first. Serving a plain name needs the non-digested files ({@code skipNonDigests: false})
 * or an application that maps it through the manifest, so each one is logged as a warning.
 */
@Slf4j
class JsModuleImportProcessor extends AbstractUrlRewritingProcessor {

	// 1: everything before the specifier's opening quote, 2: the quote, 3: the relative specifier.
	// The lookbehind keeps out member calls such as loader.import('./x.js') and identifiers ending in
	// "import" or "export" (\x24 is "$").
	private static final Pattern IMPORT_PATTERN = ~/((?<![\w\x24.])(?:(?:import|export)\b[^'"`;]*?\bfrom\s*|import\s*(?:\(\s*)?))(['"])(\.{1,2}\/[^'"`\s]+)\2/


	JsModuleImportProcessor(final AssetCompiler precompiler) {
		super(precompiler)
	}


	String process(final String inputText, final AssetFile assetFile) {
		if(!precompiler?.options?.enableDigests) {
			return inputText
		}
		final Map<String, String> replacements = [:]
		return inputText.replaceAll(IMPORT_PATTERN) { final String statement, final String prefix, final String quote, final String specifier ->
			String replacement = replacements[specifier]
			if(replacement == null) {
				replacement = replacementSpecifier(assetFile, specifier)
				replacements[specifier] = replacement
			}
			return prefix + quote + replacement + quote
		}
	}


	private String replacementSpecifier(final AssetFile assetFile, final String specifier) {
		final AssetFile target = resolveRelativeAsset(assetFile, withoutQueryOrFragment(specifier))
		if(!target) {
			return specifier
		}
		if(reach(target).contains(assetFile.path)) {
			log.warn("${assetFile.path} imports ${specifier}, which leads back to it, so the import keeps its plain name; serve the non-digested file (skipNonDigests: false) or map it through the manifest")
			return specifier
		}
		final String url = replacementUrl(assetFile, specifier)
		if(!url) {
			return specifier
		}
		// replacementUrl returns "name.js" for a file in the same directory, which an import would read as a
		// bare specifier
		return url.startsWith('../') ? url : './' + url
	}


	/**
	 * The paths of every asset {@code module} imports or bundles, directly or through the assets it reaches, kept
	 * for the compile run: every import of a module asks for it, and so does the module's own compile.
	 */
	private Set<String> reach(final AssetFile module) {
		Set<String> reached = precompiler.moduleReach.get(module.path)
		if(reached == null) {
			reached = new LinkedHashSet<String>()
			final Deque<String> pending = new ArrayDeque<String>(references(module.path, module))
			while(pending) {
				final String path = pending.pop()
				if(reached.add(path)) {
					pending.addAll(references(path, null))
				}
			}
			precompiler.moduleReach.put(module.path, reached)
		}
		return reached
	}


	/**
	 * The paths of the assets the asset at {@code path} imports by relative specifier or bundles through its
	 * directives and {@code require()} calls, read once per compile run. The asset is resolved only the first
	 * time, when {@code module} is not already at hand.
	 */
	private List<String> references(final String path, final AssetFile module) {
		List<String> paths = precompiler.moduleReferences.get(path)
		if(paths == null) {
			final AssetFile asset = module ?: AssetHelper.fileForFullName(path)
			paths = asset && !(asset instanceof GenericAssetFile) ? referencedPaths(asset) : []
			precompiler.moduleReferences.put(path, paths)
		}
		return paths
	}


	private List<String> referencedPaths(final AssetFile module) {
		final String source = module.inputStream.withStream { InputStream stream -> stream.getText(module.encoding ?: 'UTF-8') }
		final Set<String> paths = new LinkedHashSet<String>()
		final Matcher matcher = IMPORT_PATTERN.matcher(source)
		while(matcher.find()) {
			final AssetFile imported = resolveRelativeAsset(module, withoutQueryOrFragment(matcher.group(3)))
			if(imported) {
				paths << imported.path
			}
		}
		paths.addAll(new DirectiveProcessor(module.contentType[0], precompiler).getRequiredFiles(module)*.path)
		paths.addAll(JsRequireProcessor.requiredAssets(module, source)*.path)
		return paths as List<String>
	}


	private static String withoutQueryOrFragment(final String specifier) {
		return specifier.replaceFirst(/[?#].*/, '')
	}
}
