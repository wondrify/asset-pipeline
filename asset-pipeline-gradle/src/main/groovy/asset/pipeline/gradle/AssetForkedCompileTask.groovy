/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package asset.pipeline.gradle

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Worker API based task for compiling static assets using the Asset Pipeline AssetCompiler.
 * This uses Gradle Worker API to avoid command line length issues on Windows.
 * @author David Estes
 * @since 5.0
 */
@CompileStatic
@CacheableTask
abstract class AssetForkedCompileTask extends AbstractCompile {

    @Nested
    AssetPipelineExtension getConfig() {
        return project.extensions.findByType(AssetPipelineExtension)
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getAssetClassPath()

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getInputDirectory()

    @Internal
    abstract ListProperty<String> getJarResolvers()

    @Internal
    abstract ListProperty<String> getAdditionalInputs()

    private WorkerExecutor workerExecutor

    @Inject
    AssetForkedCompileTask(WorkerExecutor workerExecutor, ObjectFactory objectFactory) {
        this.workerExecutor = workerExecutor

        // Configure lazy properties - config will be resolved when accessed
        def configExtension = project.extensions.findByType(AssetPipelineExtension)
        inputDirectory.convention(configExtension.assetsPath)
        this.destinationDirectory.set(objectFactory.directoryProperty().convention(project.layout.buildDirectory.dir('assets')))
    }

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

    @Override
    void setSource(Object source) {
        if(Directory.isAssignableFrom(source.class)) {
            this.inputDirectory.set(source as Directory)
        }
        else if(File.isAssignableFrom(source.class)) {
            this.inputDirectory.set(source as File)
        }
        else if(DirectoryProperty.isAssignableFrom(source.class)) {
            this.inputDirectory.set(source as DirectoryProperty)
        }
        else {
            throw new RuntimeException("Unsupported source type: ${source.class.name}")
        }
        super.setSource(source)
    }

    @TaskAction
    void execute() {
        compile()
    }

    protected void compile() {
        // Prepare worker parameters
        List<String> additionalInputsList = []
        List<String> jarResolversList = []

        // Collect additional input directories and jar resolvers
        config.resolvers.files.each { File resolverFile ->
            boolean isJarFile = resolverFile.exists() && resolverFile.file && resolverFile.name.endsWith('.jar')
            boolean isAssetFolder = resolverFile.exists() && resolverFile.directory
            if (isJarFile) {
                jarResolversList.add(resolverFile.canonicalPath)
            } else if (isAssetFolder) {
                additionalInputsList.add(resolverFile.canonicalPath)
            }
        }

        // Add classpath jars
        this.assetClassPath?.filter { File it -> it.name.endsWith('.jar') || it.name.endsWith('.zip') }?.each { File jarFile ->
            jarResolversList.add(jarFile.canonicalPath)
        }

        // Set the lazy properties
        jarResolvers.set(jarResolversList)
        additionalInputs.set(additionalInputsList)

        // Prepare configuration JSON
        String configurationJson = null
        if(config) {
            LinkedHashMap<String,Object> configurationMap = new LinkedHashMap<>()
            configurationMap.put("configOptions", config.configOptions.get())
            configurationMap.put("enableDigests", config.enableDigests.get())
            configurationMap.put("enableGzip", config.enableGzip.get())
            configurationMap.put("enableSourceMaps", config.enableSourceMaps.get())
            configurationMap.put("excludesGzip", config.excludesGzip.getOrElse([]))
            configurationMap.put("maxThreads", config.maxThreads.orNull)
            configurationMap.put("minifyCss", config.minifyCss.get())
            configurationMap.put("minifyJs", config.minifyJs.get())
            configurationMap.put("skipNonDigests", config.skipNonDigests.get())
            configurationMap.put("minifyOptions", config.minifyOptions.get())
            configurationMap.put("excludes", config.excludes.getOrElse([]))
            configurationMap.put("includes", config.includes.getOrElse([]))
            configurationMap.put("resolvers", config.resolvers.files.collect { it.canonicalPath })
            configurationMap.put("assetsPath", config.assetsPath.get().asFile.canonicalPath)
            configurationMap.put("cacheLocation", new File(project.layout.buildDirectory.asFile.get(), '.assetcache').canonicalPath)
            String json = JsonOutput.toJson(configurationMap)
            //base64 encoding the JSON to avoid issues with special characters in the command line
            configurationJson = json.bytes.encodeBase64().toString()
        }

        // Execute using Worker API
        if(config.forkOptions) {
            def jvmArgs = config.forkOptions?.jvmArgs
            if (jvmArgs) {
                workerExecutor.processIsolation { workerSpec ->
                    workerSpec.classpath.from(getClasspath())
                    workerSpec.forkOptions.jvmArgs(jvmArgs as List<String>)
                    workerSpec.forkOptions.maxHeapSize = config.forkOptions.memoryMaximumSize
                    workerSpec.forkOptions.minHeapSize = config.forkOptions.memoryInitialSize
                }.submit(AssetCompilerWorker) { parameters ->
                    parameters.inputDirectory = inputDirectory.get().asFile.canonicalPath
                    parameters.outputDirectory = destinationDirectory.get().asFile.canonicalPath
                    parameters.configurationJson = configurationJson
                    parameters.jarResolvers = jarResolvers.get()
                    parameters.additionalInputs = additionalInputs.get()
                }
            } else {
                workerExecutor.classLoaderIsolation { workerSpec ->
                    workerSpec.classpath.from(getClasspath())
                }.submit(AssetCompilerWorker) { parameters ->
                    parameters.inputDirectory = inputDirectory.get().asFile.canonicalPath
                    parameters.outputDirectory = destinationDirectory.get().asFile.canonicalPath
                    parameters.configurationJson = configurationJson
                    parameters.jarResolvers = jarResolvers.get()
                    parameters.additionalInputs = additionalInputs.get()
                }
            }
        } else {
            workerExecutor.classLoaderIsolation { workerSpec ->
                workerSpec.classpath.from(getClasspath())
            }.submit(AssetCompilerWorker) { parameters ->
                parameters.inputDirectory = inputDirectory.get().asFile.canonicalPath
                parameters.outputDirectory = destinationDirectory.get().asFile.canonicalPath
                parameters.configurationJson = configurationJson
                parameters.jarResolvers = jarResolvers.get()
                parameters.additionalInputs = additionalInputs.get()
            }
        }

        // Wait for worker to complete
        workerExecutor.await()
    }

    @Input
    protected String getCompilerName() {
        'asset.pipeline.AssetCompiler'
    }
}
