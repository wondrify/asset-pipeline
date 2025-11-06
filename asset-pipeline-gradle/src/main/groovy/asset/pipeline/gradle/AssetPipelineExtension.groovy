package asset.pipeline.gradle

import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.GroovyForkOptions

import javax.inject.Inject

/**
 * Allows configuration of the Gradle plugin
 *
 * @author David Estes
 * @author Graeme Rocher
 */
abstract class AssetPipelineExtension implements Serializable {

    @Nested
    @Optional
    GroovyForkOptions forkOptions

    private static final long serialVersionUID = 0L

    @Input
    abstract final Property<Boolean> minifyJs

    @Input
    abstract final Property<Boolean> enableSourceMaps

    @Input
    abstract final Property<Boolean> minifyCss

    @Input
    abstract final Property<Boolean> enableDigests

    @Input
    abstract final Property<Boolean> skipNonDigests

    @Input
    abstract final Property<Boolean> enableGzip

    @Input
    abstract final Property<Boolean> packagePlugin

    @Input
    abstract final Property<Boolean> developmentRuntime

    @Input
    abstract final Property<Boolean> verbose

    @Input
    @Optional
    abstract final Property<Integer> maxThreads

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract final DirectoryProperty assetsPath

    @Input
    @Optional
    abstract final Property<String> jarTaskName

    @Input
    abstract final MapProperty<String, Object> minifyOptions

    @Input
    abstract final MapProperty<String, Object> configOptions

    @Input
    abstract final ListProperty<String> excludesGzip

    @Input
    abstract final ListProperty<String> excludes

    @Input
    abstract final ListProperty<String> includes

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract final ConfigurableFileCollection resolvers

    private final Project project

    @Inject
    AssetPipelineExtension(ObjectFactory objects, Project project) {
        this.project = project
        assetsPath = objects.directoryProperty().convention(
                project.layout.projectDirectory.dir(
                        project.extensions.findByName('grails') ?
                                'grails-app/assets' : 'src/assets'
                )
        )
        configOptions = objects.mapProperty(String, Object).convention([:])
        developmentRuntime = objects.property(Boolean).convention(true)
        enableDigests = objects.property(Boolean).convention(true)
        enableGzip = objects.property(Boolean).convention(true)
        enableSourceMaps = objects.property(Boolean).convention(true)
        excludes = objects.listProperty(String).convention([])
        excludesGzip = objects.listProperty(String).convention([])
        includes = objects.listProperty(String).convention([])
        jarTaskName = objects.property(String).convention(null)
        maxThreads = objects.property(Integer).convention(null)
        minifyCss = objects.property(Boolean).convention(true)
        minifyJs = objects.property(Boolean).convention(true)
        minifyOptions = objects.mapProperty(String, Object).convention([:])
        packagePlugin = objects.property(Boolean).convention(false)
        resolvers = objects.fileCollection()
        skipNonDigests = objects.property(Boolean).convention(true)
        verbose = objects.property(Boolean).convention(true)
    }

    /**
     * Legacy helper method to maintain behavior from previous asset pipeline versions
     * @param resolverPath the path to find the resolver
     */
    void from(String resolverPath) {
        resolvers.from(project.file(resolverPath))
    }
}
