package asset.pipeline.gradle

import asset.pipeline.AssetFile
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.fs.FileSystemAssetResolver
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject

/**
 * Created by davydotcom on 4/21/16.
 */
@CompileStatic
@CacheableTask
abstract class AssetPluginPackage extends DefaultTask {

    @Nested
    abstract final AssetPipelineExtension config

    @OutputDirectory
    abstract final DirectoryProperty destinationDirectory

    @Inject
    AssetPluginPackage(ObjectFactory objects, Project project) {
        config = project.extensions.findByType(AssetPipelineExtension)
        destinationDirectory = objects.directoryProperty()
    }

    @TaskAction
    @CompileDynamic
    void compile() {
        AssetPipelineConfigHolder.config = config.configOptions.get()

        FileSystemAssetResolver fsResolver = new FileSystemAssetResolver('manifest', config.assetsPath.get().asFile.canonicalPath)

        Collection<AssetFile> fileList = fsResolver.scanForFiles([], [])

        File destination = destinationDirectory.get().asFile.canonicalFile
        File assetsDir = new File(destination, 'assets')
        if (assetsDir.exists()) {
            assetsDir.deleteDir()
        }
        assetsDir.mkdirs()

        List<String> manifestNames = []
        fileList.eachWithIndex { AssetFile assetFile, index ->
            "Packaging File ${index + 1} of ${fileList.size()} - ${assetFile.path}"
            manifestNames << assetFile.path
            File outputFile = new File(assetsDir, assetFile.path)
            if (!outputFile.exists()) {
                outputFile.parentFile.mkdirs()
                outputFile.createNewFile()
            }

            try (InputStream sourceStream = assetFile.inputStream) {
                try (OutputStream outputStream = outputFile.newOutputStream()) {
                    outputStream << sourceStream
                    outputStream.flush()
                }
            }
        }

        File assetList = new File(destination, 'assets.list')
        if (!assetList.exists()) {
            assetList.parentFile.mkdirs()
            assetList.createNewFile()
        }

        try (OutputStream assetListOs = assetList.newOutputStream()) {
            assetListOs << manifestNames.join('\n')
            assetListOs.flush()
        }
    }
}
