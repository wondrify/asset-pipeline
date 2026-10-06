package asset.pipeline.gradle

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.HasMultipleValues
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.GroovyForkOptions
import org.gradle.api.tasks.Internal

import javax.inject.Inject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Allows configuration of the Gradle plugin
 *
 * @author David Estes
 * @author Graeme Rocher
 */
abstract class AssetPipelineExtension implements Serializable {

    private static final long serialVersionUID = 0L

    /**
     * The settings the plugin reads from the {@code assets} extension to wire the asset tasks into the build. They
     * apply to the whole project, including when set through a task's configuration.
     */
    @PackageScope
    static final List<String> PLUGIN_SETTINGS = ['packagePlugin', 'developmentRuntime', 'jarTaskName'].asImmutable()

    private GroovyForkOptions forkOptions

    // Copies the extension's fork options for a task that forks the compiler, see getForkOptions()
    private Provider<GroovyForkOptions> forkOptionsOfExtension

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

    /**
     * Whether the project is a plugin whose assets are packaged into its jar rather than compiled. Read from the
     * {@code assets} extension; setting it through a task's configuration also changes the extension.
     */
    @Input
    abstract final Property<Boolean> packagePlugin

    /**
     * Whether the compiled assets go into the archives rather than the processed resources. Read from the
     * {@code assets} extension; setting it through a task's configuration also changes the extension.
     */
    @Input
    abstract final Property<Boolean> developmentRuntime

    @Input
    abstract final Property<Boolean> verbose

    @Input
    abstract final Property<Boolean> excludeWebjarsByDefault

    @Input
    @Optional
    abstract final Property<Integer> maxThreads

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract final DirectoryProperty assetsPath

    /**
     * The archive task that receives the compiled assets, besides the standard ones. Read from the {@code assets}
     * extension; setting it through a task's configuration also changes the extension.
     */
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

    /**
     * @param objects the object factory of the project
     * @param project the project, only read here: the extension keeps no reference to it, since the configuration
     * cache stores the asset tasks' copies of the extension
     */
    @Inject
    AssetPipelineExtension(ObjectFactory objects, Project project) {
        this(objects, project, null)
    }

    protected AssetPipelineExtension(ObjectFactory objects, Project project, AssetPipelineExtension extension) {
        assetsPath = objects.directoryProperty().convention(
                project.layout.projectDirectory.dir(
                        project.extensions.findByName('grails') ? 'grails-app/assets' : 'src/assets'
                )
        )
        configOptions = objects.mapProperty(String, Object).convention([:])
        developmentRuntime = extension == null ? objects.property(Boolean).convention(true) : extension.developmentRuntime
        enableDigests = objects.property(Boolean).convention(true)
        enableGzip = objects.property(Boolean).convention(true)
        enableSourceMaps = objects.property(Boolean).convention(true)
        excludeWebjarsByDefault = objects.property(Boolean).convention(false)
        excludes = objects.listProperty(String).convention([])
        excludesGzip = objects.listProperty(String).convention([])
        includes = objects.listProperty(String).convention([])
        jarTaskName = extension == null ? objects.property(String).convention(null) : extension.jarTaskName
        maxThreads = objects.property(Integer).convention(null)
        minifyCss = objects.property(Boolean).convention(true)
        minifyJs = objects.property(Boolean).convention(true)
        minifyOptions = objects.mapProperty(String, Object).convention([:])
        packagePlugin = extension == null ? objects.property(Boolean).convention(false) : extension.packagePlugin
        resolvers = objects.fileCollection()
        skipNonDigests = objects.property(Boolean).convention(true)
        verbose = objects.property(Boolean).convention(true)
    }

    /**
     * Legacy helper method to maintain behavior from previous asset pipeline versions
     * @param resolverPath the path to find the resolver
     */
    void from(String resolverPath) {
        // A file collection made by the project's object factory resolves a relative path against the project directory
        resolvers.from(resolverPath)
    }

    /**
     * The options of the JVM the {@code assetCompile} task forks to compile the assets. A task that forks takes a copy
     * of the {@code assets} extension's options when they are first read, which Gradle does once the build is
     * configured. Changing a task's options only changes that task.
     */
    @Nested
    @Optional
    @CompileStatic
    GroovyForkOptions getForkOptions() {
        if (forkOptions == null && forkOptionsOfExtension != null) {
            forkOptions = forkOptionsOfExtension.getOrNull()
        }
        forkOptions
    }

    @CompileStatic
    void setForkOptions(GroovyForkOptions forkOptions) {
        this.forkOptions = forkOptions
    }

    @PackageScope
    @CompileStatic
    void takeForkOptionsFrom(Provider<GroovyForkOptions> forkOptions) {
        forkOptionsOfExtension = forkOptions
    }

    /**
     * Creates the configuration an asset task nests. Each of its values is the project's {@code assets} extension's
     * until the task changes it: adding to a list or map of the task adds to the extension's values, setting one
     * replaces them for that task only. The {@link #PLUGIN_SETTINGS settings the plugin wires the tasks with} are
     * shared with the extension, preserving project-wide settings made through a task's configuration.
     *
     * <p>A task does not nest the extension itself: Gradle makes an object nested in a task part of that task, and the
     * configuration cache cannot store an object that is part of more than one task.</p>
     *
     * @param project the project of the task
     * @param objects the object factory of the task
     * @param forksCompiler whether the task forks a JVM, and so takes the extension's fork options
     * @return the configuration for the task to nest
     */
    @PackageScope
    static AssetPipelineExtension forTask(Project project, ObjectFactory objects, boolean forksCompiler) {
        AssetPipelineExtension taskConfiguration = objects.newInstance(TaskConfiguration)
        AssetPipelineExtension extension = project.extensions.findByType(AssetPipelineExtension)
        if (extension) {
            LAZY_PROPERTIES.each { Method getter ->
                Object extensionValue = getter.invoke(extension)
                Object taskValue = getter.invoke(taskConfiguration)
                if (!taskValue.is(extensionValue)) {
                    link(extensionValue, taskValue)
                }
            }
            if (forksCompiler) {
                // The fork options are not lazy, so the copy is made when the task's options are first read
                taskConfiguration.takeForkOptionsFrom(project.providers.provider {
                    copyOf(extension.forkOptions, objects)
                })
            }
        }
        return taskConfiguration
    }

    /**
     * A task owns its nested configuration, but shares the three project-wide wiring properties with the extension.
     */
    static abstract class TaskConfiguration extends AssetPipelineExtension {

        @Inject
        TaskConfiguration(ObjectFactory objects, Project project) {
            super(objects, project, project.extensions.findByType(AssetPipelineExtension))
        }
    }

    /**
     * The getters of the extension's lazy values, which {@link #forTask} links to the extension.
     */
    @PackageScope
    static final List<Method> LAZY_PROPERTIES = AssetPipelineExtension.methods.findAll { Method method ->
        method.name.startsWith('get') && method.parameterCount == 0 && !Modifier.isStatic(method.modifiers) &&
                [Property, HasMultipleValues, MapProperty, ConfigurableFileCollection].any { Class type ->
                    type.isAssignableFrom(method.returnType)
                }
    }.sort { Method method -> method.name }.asImmutable()

    private static void link(Object extensionValue, Object taskValue) {
        if (taskValue instanceof ConfigurableFileCollection) {
            ((ConfigurableFileCollection) taskValue).from(extensionValue)
        } else if (taskValue instanceof Property) {
            ((Property) taskValue).set((Provider) extensionValue)
        } else if (taskValue instanceof HasMultipleValues) {
            ((HasMultipleValues) taskValue).set((Provider) extensionValue)
        } else if (taskValue instanceof MapProperty) {
            ((MapProperty) taskValue).set((Provider) extensionValue)
        } else {
            throw new IllegalStateException("Cannot link a value of type ${taskValue.getClass().name}")
        }
    }

    private static GroovyForkOptions copyOf(GroovyForkOptions options, ObjectFactory objects) {
        if (options == null) {
            return null
        }
        GroovyForkOptions copy = objects.newInstance(GroovyForkOptions)
        copy.memoryInitialSize = options.memoryInitialSize
        copy.memoryMaximumSize = options.memoryMaximumSize
        copy.jvmArgs = options.jvmArgs == null ? null : new ArrayList<String>(options.jvmArgs)
        copy.jvmArgumentProviders.addAll(options.jvmArgumentProviders)
        return copy
    }

    /**
     * The files an asset task compiles: the assets directory and the resolver directories.
     */
    @PackageScope
    FileTree sourceTree(ObjectFactory objects) {
        objects.fileCollection().from(assetsPath, resolvers.filter { File file -> file.directory }).asFileTree
    }

    /**
     * Where the asset compiler keeps its cache.
     */
    @PackageScope
    static Provider<Directory> cacheDirectory(ProjectLayout layout) {
        layout.buildDirectory.dir('.assetcache')
    }

    /**
     * Returns the effective excludes list, automatically adding 'webjars/**' if
     * excludeWebjarsByDefault is enabled.
     *
     * @return The complete list of exclusion patterns
     */
    @Internal
    List<String> getEffectiveExcludes() {
        List<String> effectiveExcludes = []

        // Add automatic webjar exclusion if enabled
        if (excludeWebjarsByDefault.get()) {
            effectiveExcludes.add('webjars/**')
        }

        // Add user-defined excludes
        effectiveExcludes.addAll(excludes.getOrElse([]))

        return effectiveExcludes
    }
}
