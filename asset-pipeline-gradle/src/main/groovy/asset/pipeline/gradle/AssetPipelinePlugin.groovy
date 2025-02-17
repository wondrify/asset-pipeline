/*
* Copyright 2014-2025 the original author or authors.
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
        createAssetsGradleConfiguration(project)

        def defaultConfiguration = project.extensions.create('assets', AssetPipelineExtensionImpl)
        def config = AssetPipelineConfigHolder.config != null ? AssetPipelineConfigHolder.config : [:]
        config.cacheLocation = "${project.buildDir}/.assetcache"
        if (project.extensions.findByName('grails')) {
            defaultConfiguration.assetsPath = 'grails-app/assets'
        } else {
            defaultConfiguration.assetsPath = "${project.projectDir}/src/assets"
        }
        defaultConfiguration.compileDir = "${project.buildDir}/assets"

        project.tasks.register('assetCompile', AssetCompile)
        project.tasks.register('assetPluginPackage', AssetPluginPackage)

        def assetPrecompileTask = project.tasks.named('assetCompile', AssetCompile)
        def assetPluginTask = project.tasks.named('assetPluginPackage', AssetPluginPackage)
        def assetCleanTask = project.tasks.register('assetClean', Delete)
        project.configurations.create(ASSET_DEVELOPMENT_CONFIGURATION_NAME)

        project.afterEvaluate {
            def assetPipeline = project.extensions.getByType(AssetPipelineExtensionImpl)
            def distributionContainer = project.extensions.findByType(DistributionContainer)
            def processResources = project.tasks.named('processResources', ProcessResources)

            assetCleanTask.configure {
                delete project.file(assetPipeline.compileDir)
            }
            def configDestinationDir = project.file(assetPipeline.compileDir)

            assetPluginTask.configure {
                it.with {
                    assetsDir = project.file(assetPipeline.assetsPath)
                    destinationDir = project.file("${processResources.get().destinationDir}/META-INF")
                }
            }

            assetPrecompileTask.configure {
                it.with {
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
            }

            configureBootRun(project)

            if (distributionContainer) {
                distributionContainer.named('main').configure {
                    it.with {
                        contents.from(assetPipeline.compileDir) {
                            into('app/assets')
                        }
                    }
                }
            }

            if (assetPipeline.packagePlugin) { // If this is just a lib, we don't want to do assetCompile
                processResources.configure {
                    it.dependsOn(assetPluginTask)
                }
            } else if (!assetPipeline.developmentRuntime && processResources) {
                processResources.configure {
                    it.with {
                        dependsOn(assetPrecompileTask)
                        from(assetPipeline.compileDir) {
                            into('assets')
                        }
                    }
                }
            } else {
                def assetTasks = [assetPipeline.jarTaskName, 'war', 'shadowJar', 'jar', 'bootWar', 'bootJar']
                project.tasks.withType(Jar).matching { it.name in assetTasks }.configureEach {
                    it.with {
                        dependsOn(assetPrecompileTask)
                        from(assetPipeline.compileDir) {
                            into('assets')
                        }
                    }
                }
            }
        }
    }

    private void configureBootRun(Project project) {
        project.plugins.withId('org.springframework.boot') { Plugin plugin ->
            project.tasks.named('bootRun', JavaExec) { JavaExec bootRun ->
                String version = AssetPipelinePlugin.package.implementationVersion
                project.dependencies.add(ASSET_DEVELOPMENT_CONFIGURATION_NAME, "com.bertramlabs.plugins:asset-pipeline-gradle:$version")
                project.logger.info('asset-pipeline: Adding {} configuration to bootRun classPath', ASSET_DEVELOPMENT_CONFIGURATION_NAME)
                bootRun.classpath += project.configurations.maybeCreate(ASSET_DEVELOPMENT_CONFIGURATION_NAME)
                project.logger.info('asset-pipeline: Adding {} configuration to bootRun classPath', ASSET_CONFIGURATION_NAME)
                bootRun.classpath += project.configurations.maybeCreate(ASSET_CONFIGURATION_NAME)
            }
        }
    }

    private void createAssetsGradleConfiguration(Project project) {
        Configuration assetsConfiguration = project.configurations.create(ASSET_CONFIGURATION_NAME)
        project.plugins.withType(JavaPlugin).configureEach {
            project.configurations.named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME).configure {
                it.extendsFrom(assetsConfiguration)
            }
        }
    }
}
