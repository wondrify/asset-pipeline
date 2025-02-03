/*
* Copyright 2014 the original author or authors.
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

package asset.pipeline.gradle

import asset.pipeline.AssetPipelineConfigHolder
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownDomainObjectException
import org.gradle.api.UnknownTaskException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.distribution.DistributionContainer
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources

/**
 * This is the Gradle Plugin implementation of asset-pipeline-core. It provides a set of tasks useful for working with your assets directly
 *
 * task: assetCompile Compiles your assets into your build directory
 * task: assetClean Cleans the build/assets directory
 *
 * @author David Estes
 * @author Graeme Rocher
 * @author Craig Burke 
 */
class AssetPipelinePlugin implements Plugin<Project> {

    static final String ASSET_CONFIGURATION_NAME = 'assets'
    static final String ASSET_DEVELOPMENT_CONFIGURATION_NAME = 'assetDevelopmentRuntime'

    void apply(Project project) {
        createGradleConfiguration(project)

        def defaultConfiguration = project.extensions.create('assets', AssetPipelineExtensionImpl)
        def config = AssetPipelineConfigHolder.config != null ? AssetPipelineConfigHolder.config : [:]
        config.cacheLocation = "${project.buildDir}/.assetcache"
        if (project.extensions.findByName('grails')) {
            
            defaultConfiguration.assetsPath = 'grails-app/assets'
        } else {
            defaultConfiguration.assetsPath = "${project.projectDir}/src/assets"
        }
        defaultConfiguration.compileDir = "${project.buildDir}/assets"

        project.tasks.create('assetCompile', AssetCompile)
        project.tasks.create('assetPluginPackage', AssetPluginPackage)


        def assetPrecompileTask = project.tasks.getByName('assetCompile')
        def assetPluginTask = project.tasks.getByName('assetPluginPackage')
        // assetPrecompileTask.dependsOn('classes')
        def assetCleanTask = project.tasks.create('assetClean', Delete)
        project.configurations.create("assetDevelopmentRuntime")
        // project.dependencies.add(ASSET_DEVELOPMENT_CONFIGURATION_NAME,"org.graalvm.sdk:graal-sdk:$graalvmVersion")
        // project.dependencies.add(ASSET_DEVELOPMENT_CONFIGURATION_NAME,"org.graalvm.js:js:$graalvmVersion")
        // project.dependencies.add(ASSET_DEVELOPMENT_CONFIGURATION_NAME,"org.graalvm.js:js-scriptengine:$graalvmVersion")

        project.afterEvaluate {
            def assetPipeline = project.extensions.getByType(AssetPipelineExtensionImpl)
            ProcessResources processResources
            DistributionContainer distributionContainer
            processResources = (ProcessResources) project.tasks.findByName('processResources')
            try {
                distributionContainer = project.extensions.getByType(DistributionContainer)
            } catch(UnknownDomainObjectException ex) {
                //we dont care this is just to see if it exists
            }

            assetCleanTask.configure {
                delete project.file(assetPipeline.compileDir)
            }
            def configDestinationDir = project.file(assetPipeline.compileDir)



            assetPluginTask.configure {
                assetsDir = project.file(assetPipeline.assetsPath)
                destinationDir = project.file("${processResources?.destinationDir}/META-INF")
            }

            assetPrecompileTask.configure {
                destinationDir = configDestinationDir
                assetsDir = project.file(assetPipeline.assetsPath)
                minifyJs = assetPipeline.minifyJs
                minifyCss = assetPipeline.minifyCss
                minifyOptions = assetPipeline.minifyOptions
                includes = assetPipeline.includes
                excludes = assetPipeline.excludes
                excludesGzip = assetPipeline.excludesGzip
                configOptions = assetPipeline.configOptions
                skipNonDigests = assetPipeline.skipNonDigests
                enableDigests = assetPipeline.enableDigests
                enableSourceMaps = assetPipeline.enableSourceMaps
                resolvers = assetPipeline.resolvers
                enableGzip = assetPipeline.enableGzip
                verbose = assetPipeline.verbose
                maxThreads = assetPipeline.maxThreads
            }

            configureBootRun(project)

            if(distributionContainer) {
                distributionContainer.getByName("main").contents.from(assetPipeline.compileDir) {
                    into "app/assets"
                }
            }

            if (assetPipeline.packagePlugin) { //this is just a lib we dont want to do assetCompile
                processResources.dependsOn(assetPluginTask)
            } else if(assetPipeline.developmentRuntime == false && processResources) {
                processResources.dependsOn(assetPrecompileTask)
                processResources.from assetPipeline.compileDir, {
                    into "assets"
                }
            } else {
                if (assetPipeline.jarTaskName) {
                    Jar jarTask = project.tasks.findByName(assetPipeline.jarTaskName)
                    if (jarTask) {
                        jarTask.dependsOn assetPrecompileTask
                        jarTask.from assetPipeline.compileDir, {
                            into "assets"
                        }
                    }
                } else { //no jar task name specified we need to try and infer
                    def assetTasks = ['war', 'shadowJar', 'jar', 'bootWar','bootJar']

                    assetTasks?.each { taskName ->
                        try {
                            Jar jarTask = project.tasks.findByName(taskName)
                            if (jarTask) {
                                jarTask.dependsOn assetPrecompileTask
                                jarTask.from assetPipeline.compileDir, {
                                    into "assets"
                                }
                            }
                        } catch(UnknownTaskException ex) {
                            // if task doesnt exist no worries
                        }

                    }
                }
            }
        }


    }

    private void configureBootRun(Project project) {
        project.getPlugins().withId("org.springframework.boot", plugin -> {
            project.getTasks().named("bootRun", Task.class, bootRun -> {
                String version = AssetPipelinePlugin.class.getPackage().getImplementationVersion()
                project.dependencies.add(ASSET_DEVELOPMENT_CONFIGURATION_NAME, "com.bertramlabs.plugins:asset-pipeline-gradle:${version}")
                project.getLogger().info("asset-pipeline: Adding ${ASSET_DEVELOPMENT_CONFIGURATION_NAME} configuration to bootRun classPath")
                bootRun.setClasspath(bootRun.getClasspath().plus(project.getConfigurations().maybeCreate(ASSET_DEVELOPMENT_CONFIGURATION_NAME)))
                project.getLogger().info("asset-pipeline: Adding ${ASSET_CONFIGURATION_NAME} configuration to bootRun classPath")
                bootRun.setClasspath(bootRun.getClasspath().plus(project.getConfigurations().maybeCreate(ASSET_CONFIGURATION_NAME)))
            })
        })
    }

    private void createGradleConfiguration(Project project) {
        Configuration configuration = project.configurations.create(ASSET_CONFIGURATION_NAME)
        JavaExec bootRunTask = (JavaExec)project.tasks.findByName('bootRun')

        project.plugins.withType(JavaPlugin) {
            if(bootRunTask) {
                Configuration runtimeConfiguration = project.configurations.getByName(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME)
                runtimeConfiguration.extendsFrom configuration        
            } else {
                Configuration runtimeConfiguration = project.configurations.getByName(JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME)
                runtimeConfiguration.extendsFrom configuration        
            }
            
        }
    }

}
