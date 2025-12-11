package asset.pipeline.dart

import asset.pipeline.AssetFile
import asset.pipeline.AssetHelper
import asset.pipeline.CacheManager
import com.caoccao.javet.annotations.V8Function
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

@Slf4j
@CompileStatic
class SassAssetFileLoader {
    static String QUOTED_FILE_SEPARATOR = Pattern.quote(File.separator)
    static String DIRECTIVE_FILE_SEPARATOR = '/'

    AssetFile baseFile

    Map<String, String> importMap = [:]

    SassAssetFileLoader(AssetFile assetFile) {
        this.baseFile = assetFile
    }

    /**
     * Java callback function for the dart-sass Importer API
     * https://sass-lang.com/documentation/js-api/interfaces/LegacySharedOptions#importer
     *
     * @param url the import it appears in the source file
     * @prev either 'stdin' for the first level imports or the original url from the parent for nested
     * @return https://sass-lang.com/documentation/js-api/modules#LegacyImporterResult
     */
    @V8Function
    @SuppressWarnings('unused')
    Map resolveImport(String url, String prev) {
        log.debug("Importing for url [{}], prev [{}], base file [{}]", url, prev, baseFile?.path)

        // The initial import has a path of stdin, but we need to convert that to the proper base path
        // Otherwise, if we have a parent, append that to form the correct URL as the importer syntax doesn't send what's expected
        if (prev == 'stdin') {
            prev = baseFile.path
        }
        else {
            // Resolve the real base path for this import if it's not an absolute path
            String priorParent = importMap[prev]
            if (priorParent && !prev.startsWith('/')) {
                Path priorParentPath = Paths.get(priorParent)
                if (priorParentPath.parent) {
                    prev = "${priorParentPath.parent.toString()}/${prev}"
                }
            }
        }

        // For each URL remember the last prev, this allows us to resolve nested imports since dart doesn't
        // give us the full path when using stdin
        importMap[url] = prev

        AssetFile imported = getAssetFromScssImport(prev, url)
        CacheManager.addCacheDependency(baseFile.path, imported)

        return [contents: imported.inputStream.text]
    }

    /**
     * Find the real file name to be resolved to a AssetFile instance
     * This method tries to resolve path/to/imported.scss and path/to/_imported.scss
     *
     * @param url
     * @return
     */
    AssetFile getAssetFromScssImport(String parent, String importUrl) {
        Path parentPath = importMap[parent] ? Paths.get(getAssetFromScssImport(importMap[parent], parent).path) : Paths.get(parent)

        Path relativeRootPath = parentPath.parent ?: Paths.get('.')
        Path importUrlPath = Paths.get(importUrl)

        List<String> possibleStylesheets = SassAssetFile.extensions.collectMany { String extension ->
            [
                relativeRootPath.resolve("${importUrlPath}.${extension}").toString(),
                relativeRootPath.resolve("${importUrlPath.parent ? importUrlPath.parent.toString() + '/' : ''}_${importUrlPath.fileName}.${extension}").toString(),
                "${importUrlPath.fileName}.${extension}",
                "_${importUrlPath.fileName}.${extension}"
            ] as List<String>
        }

        for (String stylesheetPath : possibleStylesheets) {
            String standardPathStyle = stylesheetPath?.replaceAll(QUOTED_FILE_SEPARATOR, DIRECTIVE_FILE_SEPARATOR)
            standardPathStyle = AssetHelper.resolveWebjarPath(standardPathStyle)
            AssetFile assetFile = AssetHelper.fileForFullName(standardPathStyle.toString())
            if (assetFile) {
                log.debug "$parent imported $assetFile.path"
                return assetFile
            }
        }

        log.error "Unable to find the asset for $importUrl imported by $parent"
        return null
    }
}
