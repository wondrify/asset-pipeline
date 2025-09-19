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
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.model.ObjectFactory
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
    abstract final AssetPipelineExtension config

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    final ConfigurableFileCollection assetClassPath

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    final DirectoryProperty srcDir

    private WorkerExecutor workerExecutor

    private File buildDir

    @Inject
    AssetForkedCompileTask(WorkerExecutor workerExecutor, ObjectFactory objectFactory) {
        config = project.extensions.findByType(AssetPipelineExtension)
        this.workerExecutor = workerExecutor
        srcDir = config.assetsPath
        assetClassPath = objectFactory.fileCollection()
        this.destinationDirectory.set(objectFactory.directoryProperty().convention(project.layout.buildDirectory.dir('assets')))
        buildDir = project.layout.buildDirectory.asFile.get()
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
            this.srcDir.set(source as Directory)
        }
        else if(File.isAssignableFrom(source.class)) {
            this.srcDir.set(source as File)
        }
        else if(DirectoryProperty.isAssignableFrom(source.class)) {
            this.srcDir.set(source as DirectoryProperty)
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
        def additionalInputs = []
        def jarResolvers = []

        // Collect additional input directories and jar resolvers
        config.resolvers.files.each { File resolverFile ->
            boolean isJarFile = resolverFile.exists() && resolverFile.file && resolverFile.name.endsWith('.jar')
            boolean isAssetFolder = resolverFile.exists() && resolverFile.directory
            if (isJarFile) {
                jarResolvers.add(resolverFile.canonicalPath)
            } else if (isAssetFolder) {
                additionalInputs.add(resolverFile.canonicalPath)
            }
        }

        // Add classpath jars
        this.assetClassPath?.files?.each { File jarFile ->
            def isJarFile = jarFile.name.endsWith('.jar') || jarFile.name.endsWith('.zip')
            if (jarFile.exists() && isJarFile) {
                jarResolvers.add(jarFile.canonicalPath)
            }
        }

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
            configurationMap.put("cacheLocation",new File(buildDir, '.assetcache').canonicalPath)
            String json = JsonOutput.toJson(configurationMap);
            //base64 encoding the JSON to avoid issues with special characters in the command line
            configurationJson = json.bytes.encodeBase64().toString()
        }

        // Execute using Worker API
        if(config.forkOptions) {
            def jvmArgs = config.forkOptions?.jvmArgs
            if (jvmArgs) {
                workerExecutor.processIsolation { workerSpec ->
                    workerSpec.classpath.from(getClasspath())
                    workerSpec.forkOptions { forkOptions ->
                        forkOptions.jvmArgs(jvmArgs)
                        forkOptions.maxHeapSize = config.forkOptions.memoryMaximumSize
                        forkOptions.minHeapSize = config.forkOptions.memoryInitialSize
                    }
                }.submit(AssetCompilerWorker) { parameters ->
                    parameters.inputDirectory = srcDir.get().asFile.canonicalPath
                    parameters.outputDirectory = destinationDirectory.get().asFile.canonicalPath
                    parameters.configurationJson = configurationJson
                    parameters.jarResolvers = jarResolvers as List<String>
                    parameters.additionalInputs = additionalInputs as List<String>
                }
            } else {
                workerExecutor.classLoaderIsolation { workerSpec ->
                    workerSpec.classpath.from(getClasspath())
                }.submit(AssetCompilerWorker) { parameters ->
                    parameters.inputDirectory = srcDir.get().asFile.canonicalPath
                    parameters.outputDirectory = destinationDirectory.get().asFile.canonicalPath
                    parameters.configurationJson = configurationJson
                    parameters.jarResolvers = jarResolvers as List<String>
                    parameters.additionalInputs = additionalInputs as List<String>
                }
            }
        } else {
            workerExecutor.classLoaderIsolation { workerSpec ->
                workerSpec.classpath.from(getClasspath())
            }.submit(AssetCompilerWorker) { parameters ->
                parameters.inputDirectory = srcDir.get().asFile.canonicalPath
                parameters.outputDirectory = destinationDirectory.get().asFile.canonicalPath
                parameters.configurationJson = configurationJson
                parameters.jarResolvers = jarResolvers as List<String>
                parameters.additionalInputs = additionalInputs as List<String>
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
