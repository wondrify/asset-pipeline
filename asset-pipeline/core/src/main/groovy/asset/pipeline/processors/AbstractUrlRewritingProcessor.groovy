/*
 * Copyright 2014 the original author or authors.
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


import asset.pipeline.AbstractProcessor
import asset.pipeline.AssetCompiler
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.AssetFile
import asset.pipeline.DirectiveProcessor
import asset.pipeline.GenericAssetFile
import asset.pipeline.AssetHelper
import static asset.pipeline.AssetHelper.DIRECTIVE_FILE_SEPARATOR
import static asset.pipeline.AssetHelper.extensionFromURI
import static asset.pipeline.AssetHelper.fileForUri
import static asset.pipeline.AssetHelper.getByteDigest
import static asset.pipeline.AssetHelper.nameWithoutExtension
import static asset.pipeline.AssetHelper.normalizePath
import static asset.pipeline.utils.net.Urls.getSchemeWithColon


/**
 * Subclasses iterate over relative asset URLs in a base asset file and
 * rewrites the URL relative to the base asset file.
 *
 * In precompiler mode, the asset URLs are also cache digested.
 *
 * @author David Estes
 * @author Ross Goldberg
 */
abstract class AbstractUrlRewritingProcessor extends AbstractProcessor {

    private static final Set<String> NO_CACHE_DIGEST_FOR_COMPILED_EXTENSION_SET = []


    protected static boolean doNotInsertCacheDigestIntoUrlForCompiledExtension(final String compiledExtension) {
        NO_CACHE_DIGEST_FOR_COMPILED_EXTENSION_SET.add(compiledExtension)
    }


    AbstractUrlRewritingProcessor(final AssetCompiler precompiler) {
        super(precompiler)
    }


    protected String replacementUrl(final AssetFile assetFile, final String url) {
        final String schemeWithColon = getSchemeWithColon(url)

        final String urlSansScheme =
            schemeWithColon \
                ? url.substring(schemeWithColon.length())
                : url

        final URL urlSplitter = new URL('http', 'hostname', urlSansScheme)

        final AssetFile baseFile = assetFile.baseFile ?: assetFile
        final String assetPath  = normalizePath(assetFile.parentPath ? assetFile.parentPath + DIRECTIVE_FILE_SEPARATOR + urlSplitter.path : urlSplitter.path)

        final List<String> contentType = AssetHelper.assetMimeTypeForURI(assetPath)
        
        AssetFile currFile = AssetHelper.fileForUri(assetPath,contentType ? contentType[0] : null)
        if(!currFile) {
            currFile = AssetHelper.fileForFullName(assetPath)
        }

        if (! currFile) {
            return null
        }

        final StringBuilder replacementPathSb = new StringBuilder()

        // scheme (aka protocol)
        if (schemeWithColon) {
            replacementPathSb << schemeWithColon
        }

        // relative parent path
        final String baseFileParentPath = baseFile.parentPath
        final String currFileParentPath = currFile.parentPath

        final List<String> baseRelativePath = baseFileParentPath ? baseFileParentPath.split(DIRECTIVE_FILE_SEPARATOR).findAll {it}.reverse() : []
        final List<String> currRelativePath = currFileParentPath ? currFileParentPath.split(DIRECTIVE_FILE_SEPARATOR).findAll {it}.reverse() : []

        int basePathIndex = baseRelativePath.size() - 1
        int currPathIndex = currRelativePath.size() - 1

        while (basePathIndex >= 0 && currPathIndex >= 0 && baseRelativePath[basePathIndex] == currRelativePath[currPathIndex]) {
            basePathIndex--
            currPathIndex--
        }

        // for each remaining level in the base path, add a ..
        for (; basePathIndex >= 0; basePathIndex--) {
            replacementPathSb << '..' << DIRECTIVE_FILE_SEPARATOR
        }

        for (; currPathIndex >= 0; currPathIndex--) {
            replacementPathSb << currRelativePath[currPathIndex] << DIRECTIVE_FILE_SEPARATOR
        }

        // file
        final String fileName = nameWithoutExtension(currFile.name)
        if(precompiler && precompiler.options.enableDigests) {
            if(currFile instanceof GenericAssetFile) {
                replacementPathSb << fileName << '-' << currFile.getByteDigest() << '.' << extensionFromURI(currFile.name)
            } else {
                final String compiledExtension = currFile.compiledExtension
                if (NO_CACHE_DIGEST_FOR_COMPILED_EXTENSION_SET.contains(compiledExtension)) {
                    replacementPathSb << fileName << '.' << compiledExtension
                } else {
                    replacementPathSb << fileName << '-' << getByteDigest(new DirectiveProcessor(currFile.contentType[0], precompiler).compile(currFile).bytes) << '.' << compiledExtension
                }
            }
        } else {
            if(currFile instanceof GenericAssetFile) {
                replacementPathSb << currFile.name
            } else {
                replacementPathSb << fileName << '.' << currFile.compiledExtension
            }
        }

        // query
        if (urlSplitter.query != null) {
            replacementPathSb << '?' << urlSplitter.query
        }

        // fragment (aka reference; aka anchor)
        if (urlSplitter.ref) {
            replacementPathSb << '#' << urlSplitter.ref
        }

        return replacementPathSb.toString()
    }


    protected String replacementAssetPath(final AssetFile assetFile, final AssetFile currFile, Boolean preferRelative=false) {

        final StringBuilder replacementPathSb = new StringBuilder()
        def urlConfig = AssetPipelineConfigHolder.config?.url
        String baseUrl
        if(urlConfig instanceof Closure) {
            baseUrl = urlConfig.call(null)
        }

//		println "Replacing Asset Path for ${currFile.path}"
        if(baseUrl) {
            replacementPathSb << baseUrl
        } else if(!preferRelative){
            replacementPathSb << '/' << (AssetPipelineConfigHolder.config?.mapping != null ? AssetPipelineConfigHolder.config?.mapping : 'assets')
			if(AssetPipelineConfigHolder.config?.mapping?.size() > 0) {
				replacementPathSb << '/'
			}
        }
//		println "FileName Check: ${replacementPathSb}"
        // file
        final String fileName = nameWithoutExtension(currFile.path)
        if(precompiler && precompiler.options.enableDigests) {
            if(currFile instanceof GenericAssetFile) {
                replacementPathSb << fileName << '-' << currFile.getByteDigest() << '.' << extensionFromURI(currFile.name)
            } else {
                final String compiledExtension = currFile.compiledExtension
                if (NO_CACHE_DIGEST_FOR_COMPILED_EXTENSION_SET.contains(compiledExtension)) {
                    replacementPathSb << fileName << '.' << compiledExtension
                } else {
                    replacementPathSb << fileName << '-' << getByteDigest(new DirectiveProcessor(currFile.contentType[0], precompiler).compile(currFile).bytes) << '.' << compiledExtension
                }
            }
        } else {
            if(currFile instanceof GenericAssetFile) {
                replacementPathSb << currFile.path
            } else {
                replacementPathSb << fileName << '.' << currFile.compiledExtension
            }
        }

        return replacementPathSb.toString()
    }
}
