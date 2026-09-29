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
 * This Processor iterates over relative image paths in a CSS file and
 * recalculates their path relative to the base file. In precompiler mode
 * the image URLs are also cache digested.
 *
 * @author David Estes
 * @author Ross Goldberg
 */
class CssProcessor extends AbstractUrlRewritingProcessor {

    private static final Pattern URL_CALL_PATTERN = ~/url\((?:\s*)(['"]?)([a-zA-Z0-9\-_.:\/@#? &+%=$]++)\1?(?:\s*)\)/


    CssProcessor(final AssetCompiler precompiler) {
        super(precompiler)
    }


    String process(final String inputText, final AssetFile assetFile) {
        final Map<String, String> cachedPaths = [:]
        return \
            inputText.replaceAll(URL_CALL_PATTERN) { final String urlCall, final String quote, final String assetPath ->
                final String cachedPath = cachedPaths[assetPath]

                String replacementPath = null
                if (cachedPath != null) {
                    replacementPath = cachedPath
                } else if (assetPath.size() > 0 && isRelative(assetPath)) {
                    replacementPath = replacementUrl(assetFile, assetPath)
                    if (replacementPath) {
                        cachedPaths[assetPath] = replacementPath
                    } else {
                        cachedPaths[assetPath] = assetPath
                        return urlCall
                    }
                } else {
                    replacementPath = null
                    return urlCall
                }

                return "url(${quote}${replacementPath}${quote})"
            }
    }
}
