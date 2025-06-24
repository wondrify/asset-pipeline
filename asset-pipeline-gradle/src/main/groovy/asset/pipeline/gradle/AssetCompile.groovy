/*
 * Copyright 2014-2025 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package asset.pipeline.gradle

import asset.pipeline.AssetCompiler
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.AssetSpecLoader
import asset.pipeline.fs.FileSystemAssetResolver
import asset.pipeline.fs.JarAssetResolver
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject
/**
 * A Gradle task for compiling assets
 *
 * @author Graeme Rocher
 */
@CompileStatic
@CacheableTask
abstract class AssetCompile extends DefaultTask {

    @Nested
    abstract final AssetPipelineExtension config

    @Input
    abstract final Property<Boolean> flattenResolvers

    @OutputDirectory
    abstract final DirectoryProperty destinationDirectory

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract final ConfigurableFileCollection assetConfigurationFiles

    @Classpath
    @Optional
    abstract final ConfigurableFileCollection classpath

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    FileTree getSource() {
        FileTree src = project.files(config.assetsPath).asFileTree
        config.resolvers.files.each { File resolverFile ->
            if (resolverFile.exists() && resolverFile.directory) {
                src += project.files(resolverFile).asFileTree
            }
        }
        return src
    }

    @Inject
    AssetCompile(ObjectFactory objects, Project project) {
        config = project.extensions.findByType(AssetPipelineExtension)
        flattenResolvers = objects.property(Boolean).convention(false)
        assetConfigurationFiles = objects.fileCollection()
                .convention(project.configurations.named(AssetPipelinePlugin.ASSET_CONFIGURATION_NAME))
        destinationDirectory = objects.directoryProperty()
                .convention(project.layout.buildDirectory.dir('assets'))
        classpath = objects.fileCollection().from(project.provider {
            try {
                def existingConfigurations = [
                        'assets',
                        'provided',
                        'runtimeClasspath',
                ].findResults {
                    project.configurations.names.contains(it) ? project.configurations.named(it) : null
                }
                project.files(existingConfigurations)
            } catch (ignored) {
                return null
            }
        })
    }

    @TaskAction
    @CompileDynamic
    void compile() {
        AssetPipelineConfigHolder.config = (AssetPipelineConfigHolder.config ?: [:]) + config.configOptions.get()
        AssetPipelineConfigHolder.resolvers = []
        registerResolvers()
        loadAssetSpecifications()

        def listener = config.verbose.get() ? new GradleEventListener() : null

        Map compilerArgs = [
                compileDir      : destinationDirectory.get().asFile.absolutePath,
                enableDigests   : config.enableDigests.get(),
                enableGzip      : config.enableGzip.get(),
                enableSourceMaps: config.enableSourceMaps.get(),
                excludesGzip    : config.excludesGzip.getOrElse([]),
                maxThreads      : config.maxThreads.orNull,
                minifyCss       : config.minifyCss.get(),
                minifyJs        : config.minifyJs.get(),
                minifyOptions   : config.minifyOptions.get(),
                skipNonDigests  : config.skipNonDigests.get(),
        ]
        def assetCompiler = new AssetCompiler(compilerArgs, listener)
        assetCompiler.excludeRules.default = config.excludes.get()
        assetCompiler.includeRules.default = config.includes.get()
        assetCompiler.compile()
    }

    void registerResolvers() {
        def mainFileResolver = new FileSystemAssetResolver('application', config.assetsPath.get().asFile.canonicalPath)
        AssetPipelineConfigHolder.registerResolver(mainFileResolver)

        config.resolvers.files.each { File resolverFile ->
            boolean isJarFile = resolverFile.exists() && resolverFile.file && resolverFile.name.endsWith('.jar')
            boolean isAssetFolder = resolverFile.exists() && resolverFile.directory
            if (isJarFile) {
                registerJarResolvers(resolverFile)
            } else if (isAssetFolder) {
                def fileResolver = new FileSystemAssetResolver(path, resolverFile.canonicalPath, flattenResolvers.get())
                AssetPipelineConfigHolder.registerResolver(fileResolver)
            }
        }

        this.classpath?.files?.each { registerJarResolvers(it) }
    }

    void registerJarResolvers(File jarFile) {
        def isJarFile = jarFile.name.endsWith('.jar') || jarFile.name.endsWith('.zip')
        if (jarFile.exists() && isJarFile) {
            AssetPipelineConfigHolder.registerResolver(new JarAssetResolver(jarFile.name, jarFile.canonicalPath, 'META-INF/assets'))
            AssetPipelineConfigHolder.registerResolver(new JarAssetResolver(jarFile.name, jarFile.canonicalPath, 'META-INF/static'))
            AssetPipelineConfigHolder.registerResolver(new JarAssetResolver(jarFile.name, jarFile.canonicalPath, 'META-INF/resources'))
        }
    }

    void loadAssetSpecifications() {
        Set<File> processorFiles = assetConfigurationFiles.files
        if (processorFiles) {
            URL[] urls = processorFiles.collect { it.toURI().toURL() }.toArray(new URL[0])
            ClassLoader classLoader = new URLClassLoader(urls as URL[], getClass().classLoader)
            AssetSpecLoader.loadSpecifications(classLoader)
        } else {
            AssetSpecLoader.loadSpecifications()
        }
    }
}
