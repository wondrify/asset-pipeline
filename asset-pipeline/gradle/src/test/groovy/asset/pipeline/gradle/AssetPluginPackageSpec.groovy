package asset.pipeline.gradle

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

class AssetPluginPackageSpec extends Specification {

    @TempDir
    File temporaryDirectory

    void "packages assets.list in path order"() {
        given:
        def assetsDirectory = new File(temporaryDirectory, 'assets')
        def destinationDirectory = new File(temporaryDirectory, 'output')
        def laterPath = new File(assetsDirectory, 'stylesheets/z-last.css').tap { it.parentFile.mkdirs() }
        def earlierPath = new File(assetsDirectory, 'javascripts/a-first.js').tap { it.parentFile.mkdirs() }
        laterPath.text = 'body {}'
        earlierPath.text = 'const first = true;'

        def project = ProjectBuilder.builder().build()
        project.extensions.create('assets', AssetPipelineExtension)
        def task = project.tasks.register('assetPluginPackage', AssetPluginPackage) {
            it.config.assetsPath.set(assetsDirectory)
            it.destinationDirectory.set(destinationDirectory)
        }

        when:
        task.get().compile()

        then:
        new File(destinationDirectory, 'assets.list').text == 'a-first.js\nz-last.css'
        new File(destinationDirectory, 'assets/a-first.js').text == earlierPath.text
        new File(destinationDirectory, 'assets/z-last.css').text == laterPath.text
    }
}
