/*
 * Copyright 2016 the original author or authors.
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
package asset.pipeline.jsass

import asset.pipeline.AssetFile
import groovy.util.logging.Slf4j

import java.util.regex.Pattern
import asset.pipeline.AssetHelper
import asset.pipeline.CacheManager
import io.bit3.jsass.importer.Import
import io.bit3.jsass.importer.Importer

import java.nio.file.Path
import java.nio.file.Paths

@Slf4j
class SassAssetFileImporter implements Importer {
    AssetFile baseFile
    static String QUOTED_FILE_SEPARATOR = Pattern.quote(File.separator)
    static String DIRECTIVE_FILE_SEPARATOR = '/'
    SassAssetFileImporter(AssetFile assetFile) {
        super()
        this.baseFile = assetFile
    }

    /**
     * Find the real file name to be resolved to a AssetFile instance
     * This method tries to resolve path/to/imported.scss and path/to/_imported.scss
     * TODO: Make sure there are no other ways to include files in SASS.
     * @param url
     * @return
     */
    static AssetFile getAssetFromScssImport(String parent, String importUrl) {
        Path parentPath = Paths.get(parent)
        Path relativeRootPath = parentPath.parent ?: Paths.get('.')
        Path importUrlPath = Paths.get(importUrl)
        def possibleStylesheets = SassAssetFile.extensions.collectMany { extension ->
            [relativeRootPath.resolve("${importUrlPath}.${extension}").toString(),
            relativeRootPath.resolve("${importUrlPath.parent ? importUrlPath.parent.toString() + '/' : ''}_${importUrlPath.fileName}.${extension}").toString(),
            "${importUrlPath.fileName}.${extension}","_${importUrlPath.fileName}.${extension}"]
        }
        
        for (String stylesheetPath : possibleStylesheets) {
            String standardPathStyle = stylesheetPath?.replaceAll(QUOTED_FILE_SEPARATOR, DIRECTIVE_FILE_SEPARATOR)
            standardPathStyle = AssetHelper.resolveWebjarPath(standardPathStyle)
            def assetFile = AssetHelper.fileForFullName(standardPathStyle.toString())
            if (assetFile) {
                log.debug "$parent imported $assetFile.path"
                return assetFile
            }
        }

        log.error "Unable to find the asset for $importUrl imported by $parent"
        return null;
    }

    @Override
    public Collection<Import> apply(String url, Import previous) {
        def importedAssetFile = getAssetFromScssImport(previous.absoluteUri.toString(), url)

        if (baseFile && importedAssetFile) {
            CacheManager.addCacheDependency(baseFile.path, importedAssetFile)
            def results = Collections.singletonList(
                    new Import(importedAssetFile.name, importedAssetFile.path, importedAssetFile.inputStream.getText('UTF-8'))
                    )
            return results
        }

        // at this point, compilation will fail
        return null;
    }
}
