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


import asset.pipeline.AssetCompiler
import asset.pipeline.AssetFile
import java.util.regex.Pattern

import static asset.pipeline.utils.net.Urls.isRelative


/**
 * This Processor iterates over relative image paths in an HTML file and
 * recalculates their path relative to the base file. In precompiler mode
 * the image URLs are also cache digested.
 *
 * @author David Estes
 * @author Ross Goldberg
 */
class HtmlProcessor extends AbstractUrlRewritingProcessor {

    private static final Pattern QUOTED_ASSET_PATH_PATTERN = ~/"([a-zA-Z0-9\-_.:\/@#? $&+%=']++)"|'([a-zA-Z0-9\-_.:\/@#? $&+%="]++)'/

    static {
        doNotInsertCacheDigestIntoUrlForCompiledExtension('html')
    }


    HtmlProcessor(final AssetCompiler precompiler) {
        super(precompiler)
    }


    String process(final String inputText, final AssetFile assetFile) {
        final Map<String, String> cachedPaths = [:]
        return \
            inputText.replaceAll(QUOTED_ASSET_PATH_PATTERN) {
                final String quotedAssetPathWithQuotes,
                final String doubleQuotedAssetPath,
                final String singleQuotedAssetPath
            ->
                final String untrimmedAssetPath = doubleQuotedAssetPath ?: singleQuotedAssetPath
                final String assetPath          = untrimmedAssetPath.trim()

                String replacementPath
                if (cachedPaths.containsKey(assetPath)) {
                    // cachedPaths[assetPath] == null // means use the incoming untrimmedAssetPath to preserve trim spacing
                    replacementPath = cachedPaths[assetPath] ?: untrimmedAssetPath
                } else if (assetPath.size() > 0 && isRelative(assetPath)) {
                    replacementPath = replacementUrl(assetFile, assetPath)
                    if (replacementPath) {
                        cachedPaths[assetPath] = replacementPath
                    } else {
                        cachedPaths[assetPath] = null
                        return quotedAssetPathWithQuotes
                    }
                } else {
                    replacementPath = null
                    //TODO? cachedPaths[assetPath] = null
                    return quotedAssetPathWithQuotes
                }

                final String quote = doubleQuotedAssetPath ? '"' : "'"
                return "${quote}${replacementPath}${quote}"
            }
    }
}
