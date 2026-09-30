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
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.DirectiveProcessor
import asset.pipeline.GenericAssetFile
import groovy.util.logging.Slf4j


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
 * left and passes through unchanged. Babel converts every {@code .mjs}, {@code .bjs} and {@code .js.es6} (and
 * {@code .es7}, {@code .es8}, {@code .es}) file, every {@code .js} file when {@code enableES6} is on, and a
 * {@code .js} file that contains {@code export default} unless {@code enableES6} is {@code false}; see
 * {@link BabelJsProcessor#converts}. So a module library is served as modules only as {@code .js} files, and
 * with {@code enableES6: false} when any of them has a default export.
 *
 * A module in an import cycle keeps its plain name wherever it is imported from. A digest names content, so a
 * module's name would depend on the content of a module whose content contains that name. A module's content is
 * its own source plus every file its {@code //= require} directives and {@code require()} calls bundle into it,
 * and it names the digest of every asset its {@code asset_url()} calls refer to, so an import of B is rewritten
 * only when nothing B imports, bundles or names that way leads back to B, directly or through other modules. An
 * import from outside the cycle keeps the plain name too: a browser identifies a module by its URL, so one
 * imported under both names would be loaded, and run, twice. That depends on the source files alone, so a module
 * compiles to the same content whichever module the compiler reaches first. Serving a plain name needs the
 * non-digested files ({@code skipNonDigests: false}) or an application that maps it through the manifest, so
 * each one is logged as a warning.
 *
 * Set {@code rewriteModuleImports: false} in the config options to leave every import as written.
 */
@Slf4j
class JsModuleImportProcessor extends AbstractUrlRewritingProcessor {

	// The lexer runs on GraalJS, which core only compiles against; once it cannot start, imports are left as written
	private static volatile boolean lexerUnavailable

	JsModuleImportProcessor(final AssetCompiler precompiler) {
		super(precompiler)
	}


	String process(final String inputText, final AssetFile assetFile) {
		if(!precompiler?.options?.enableDigests || AssetPipelineConfigHolder.config?.rewriteModuleImports == false) {
			return inputText
		}
		final List<JsModuleImports.Specifier> specifiers = findImports(inputText, assetFile)
		if(specifiers == null) {
			return inputText
		}
		final Map<String, String> replacements = [:]
		final StringBuilder output = new StringBuilder(inputText.length())
		int copied = 0
		for (JsModuleImports.Specifier specifier : specifiers) {
			if (!specifier.relative) {
				continue
			}
			String replacement = replacements[specifier.name]
			if (replacement == null) {
				replacement = replacementSpecifier(assetFile, specifier.name)
				replacements[specifier.name] = replacement
			}
			if (replacement != specifier.name) {
				output.append(inputText, copied, specifier.start)
				output.append(specifier.quoted(replacement))
				copied = specifier.end
			}
		}
		return output.append(inputText, copied, inputText.length()).toString()
	}


	private String replacementSpecifier(final AssetFile assetFile, final String specifier) {
		final AssetFile target = resolveRelativeAsset(assetFile, withoutQueryOrFragment(specifier))
		if(!target) {
			return specifier
		}
		// Every import of a module in a cycle keeps its plain name, from inside the cycle or out. A browser identifies a
		// module by its URL, so a digested import from outside would load a second instance beside the one the cycle
		// imports, running its code again. The importing file is in the graph too, so this also covers the import that
		// closes a cycle.
		if(reach(target).contains(target.path)) {
			log.warn("${assetFile.path} imports ${specifier}, which is part of an import cycle, so the import keeps its plain name; serve the non-digested file (skipNonDigests: false) or map it through the manifest")
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
	 * The paths of the assets the asset at {@code path} imports, bundles or names by digest, see
	 * {@link #referencedPaths}, read once per compile run. The asset is resolved only the first time, when
	 * {@code module} is not already at hand.
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


	/**
	 * What {@code module}'s processors put into its compiled content from other assets, whole or by digest: the
	 * modules it imports by relative specifier, or, when Babel converts it, every module it imports, since those
	 * become {@code require()} calls the require processor bundles; the files its directives and {@code require()}
	 * calls bundle; and the assets its {@code asset_url()} calls name.
	 */
	private List<String> referencedPaths(final AssetFile module) {
		final String source = module.inputStream.withStream { InputStream stream -> stream.getText(module.encoding ?: 'UTF-8') }
		final List<Class> processors = module.processors ?: []
		final List<AssetFile> referenced = []
		if (JsModuleImportProcessor in processors) {
			boolean bundled = BabelJsProcessor in processors && BabelJsProcessor.converts(source, module)
			for (JsModuleImports.Specifier specifier : findImports(source, module) ?: []) {
				if (bundled) {
					referenced << JsRequireProcessor.resolveRequiredAsset(module, specifier.name)
				} else if (specifier.relative) {
					referenced << resolveRelativeAsset(module, withoutQueryOrFragment(specifier.name))
				}
			}
		}
		referenced.addAll(new DirectiveProcessor(module.contentType[0], precompiler).getRequiredFiles(module))
		if(JsRequireProcessor in processors) {
			referenced.addAll(JsRequireProcessor.requiredAssets(module, source))
		}
		if(JsProcessor in processors) {
			referenced.addAll(JsProcessor.urlAssets(source))
		}
		// A generic asset's digest is its bytes', so nothing leads on from it
		return referenced.findAll { AssetFile asset -> asset && !(asset instanceof GenericAssetFile) }*.path.unique()
	}


	/**
	 * The import specifiers in {@code source}, or null when the lexer cannot read it. Such a file is left as it was
	 * before imports were rewritten: it compiled then, and a browser may not need it to be a module at all.
	 */
	private static List<JsModuleImports.Specifier> findImports(final String source, final AssetFile assetFile) {
		if(lexerUnavailable) {
			return null
		}
		try {
			return JsModuleImports.find(source, assetFile.path)
		} catch(IllegalArgumentException e) {
			log.warn("Cannot analyze the ES module imports in ${assetFile.path} (${e.cause?.message ?: e.message}), so they keep their plain names")
			return null
		} catch(IllegalStateException | LinkageError e) {
			lexerUnavailable = true
			log.warn("ES module imports keep their plain names: the lexer that finds them needs GraalJS (org.graalvm.js:js-community) on the compile classpath, which the Gradle plugin provides (${e.cause?.message ?: e.message})")
			return null
		}
	}


	private static String withoutQueryOrFragment(final String specifier) {
		return specifier.replaceFirst(/[?#].*/, '')
	}
}
