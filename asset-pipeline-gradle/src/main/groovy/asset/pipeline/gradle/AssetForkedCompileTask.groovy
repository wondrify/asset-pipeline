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
import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.JavaExecSpec
import javax.inject.Inject
import java.util.jar.JarFile

/**
 * Forked Execution task for compiling static assets using the Asset Pipeline AssetCompiler.
 * This runs it in a separate JVM process, allowing for better isolation and performance.
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

    private ExecOperations execOperations

    private File buildDir

    @Inject
    AssetForkedCompileTask(ExecOperations execOperations, ObjectFactory objectFactory) {
        config = project.extensions.findByType(AssetPipelineExtension)
        this.execOperations = execOperations
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
        ExecResult result = execOperations.javaexec(
                new Action<JavaExecSpec>() {
                    @Override
                    @CompileDynamic
                    void execute(JavaExecSpec javaExecSpec) {
                        javaExecSpec.mainClass.set(getCompilerName())
                        javaExecSpec.setClasspath(getClasspath())

                        def jvmArgs = config.forkOptions?.jvmArgs
                        if (jvmArgs) {
                            javaExecSpec.jvmArgs(jvmArgs)
                        }
                        if(config.forkOptions) {
                            javaExecSpec.setMaxHeapSize(config.forkOptions.memoryMaximumSize)
                            javaExecSpec.setMinHeapSize(config.forkOptions.memoryInitialSize)
                        }


                        List<String> arguments = [
                                "-i",
                                srcDir.get().asFile.canonicalPath,
                                "-o",
                                destinationDirectory.get().asFile.canonicalPath,
                        ]

                        prepareArguments(arguments)
                        javaExecSpec.args(arguments)
                    }

                }
        )
        result.assertNormalExitValue()

    }

    void prepareArguments(List<String> arguments) {
        // no-op
        registerResolvers(arguments)
        if(config) {
            LinkedHashMap<String,Object> configurationJson = new LinkedHashMap<>()
            configurationJson.put("configOptions", config.configOptions.get())
            configurationJson.put("enableDigests", config.enableDigests.get())
            configurationJson.put("enableGzip", config.enableGzip.get())
            configurationJson.put("enableSourceMaps", config.enableSourceMaps.get())
            configurationJson.put("excludesGzip", config.excludesGzip.getOrElse([]))
            configurationJson.put("maxThreads", config.maxThreads.orNull)
            configurationJson.put("minifyCss", config.minifyCss.get())
            configurationJson.put("minifyJs", config.minifyJs.get())
            configurationJson.put("skipNonDigests", config.skipNonDigests.get())
            configurationJson.put("minifyOptions", config.minifyOptions.get())
            configurationJson.put("excludes", config.effectiveExcludes)
            configurationJson.put("includes", config.includes.getOrElse([]))
            configurationJson.put("resolvers", config.resolvers.files.collect { it.canonicalPath })
            configurationJson.put("assetsPath", config.assetsPath.get().asFile.canonicalPath)
            configurationJson.put("cacheLocation",new File(buildDir, '.assetcache').canonicalPath)
            String json = JsonOutput.toJson(configurationJson);
            arguments.add("-B")
            //base64 encoding the JSON to avoid issues with special characters in the command line
            String base64Json = json.bytes.encodeBase64().toString()
            arguments.add(base64Json)

        }
    }

    @Input
    protected String getCompilerName() {
        'asset.pipeline.AssetCompiler'
    }


    void registerResolvers(List<String> arguments) {
        config.resolvers.files.each { File resolverFile ->
            boolean isJarFile = resolverFile.exists() && resolverFile.file && resolverFile.name.endsWith('.jar')
            boolean isAssetFolder = resolverFile.exists() && resolverFile.directory
            if (isJarFile) {
                registerJarResolvers(arguments,resolverFile)
            } else if (isAssetFolder) {
                arguments.add("-i")
                arguments.add(resolverFile.canonicalPath)
            }
        }

        this.assetClassPath?.files?.each { registerJarResolvers(arguments,it) }
    }

    void registerJarResolvers(List<String> arguments, File jarFile) {
        def isJarFile = jarFile.name.endsWith('.jar') || jarFile.name.endsWith('.zip')
        if (jarFile.exists() && isJarFile) {
            JarFile jar = null
            try {
                jar = new JarFile(jarFile)
                // Only add the jar if it has the expected asset folders used by
                // AssetCompile.registerJarResolvers() and AssetCompiler.registerJarResolvers()
                if (['META-INF/assets/', 'META-INF/static/', 'META-INF/resources/'].any { jar.getJarEntry(it) != null }) {
                    arguments.add("-j")
                    arguments.add(jarFile.canonicalPath)
                }
            } catch (Exception e) {
                // If we can't read the jar, skip it
                return
            } finally {
                jar?.close()
            }
        }
    }
}
