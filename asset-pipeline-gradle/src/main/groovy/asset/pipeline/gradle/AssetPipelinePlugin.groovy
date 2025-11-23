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

        AssetPipelineExtension extension = project.extensions.create('assets', AssetPipelineExtension)

        if (!AssetPipelineConfigHolder.config) {
            AssetPipelineConfigHolder.config = [:]
        }
        def config = AssetPipelineConfigHolder.config
        config.cacheLocation = project.layout.buildDirectory.dir('.assetcache').get().asFile.absolutePath

        def assetCleanTask = project.tasks.register('assetClean', Delete)
        def assetPrecompileTask = project.tasks.register('assetCompile', AssetForkedCompileTask)
        def assetPackageTask = project.tasks.register('assetPluginPackage', AssetPluginPackage)

        project.configurations.create(ASSET_DEVELOPMENT_CONFIGURATION_NAME)
        project.dependencies.add(ASSET_DEVELOPMENT_CONFIGURATION_NAME, "${getClass().package.implementationVendor}:${getClass().package.implementationVersion}")

        project.afterEvaluate {
            assetCleanTask.configure {
                delete(assetPrecompileTask.get().destinationDirectory)
            }

            assetPackageTask.configure { AssetPluginPackage task ->
                def processResources = project.tasks.named('processResources', ProcessResources)
                task.destinationDirectory.set(project.file(new File(processResources.get().destinationDir, 'META-INF')))
            }

            assetPrecompileTask.configure { AssetForkedCompileTask task ->
                task.classpath = project.files(
                        project.configurations.named(ASSET_CONFIGURATION_NAME),
                        project.configurations.named(ASSET_DEVELOPMENT_CONFIGURATION_NAME),
                        project.configurations.named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME),
                        project.configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME),
                        project.provider {
                            ((URLClassLoader) AssetPipelinePlugin.classLoader).URLs.collect { new File(it.toURI()) }
                        }
                )
                task.assetClassPath.from(project.files(
                        project.configurations.named(ASSET_CONFIGURATION_NAME),
                        project.configurations.named(ASSET_DEVELOPMENT_CONFIGURATION_NAME),
                        project.configurations.named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME),
                        project.configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)
                ))
            }

            configureTestRuntimeClasspath(project)
            configureBootRun(project)

            def distributionContainer = project.extensions.findByType(DistributionContainer)
            if (distributionContainer) {
                distributionContainer.named('main').configure {
                    it.with {
                        contents.from(assetPrecompileTask.get().destinationDirectory) {
                            into('app/assets')
                        }
                    }
                }
            }

            if (extension.packagePlugin.getOrElse(false)) { // If this is just a lib, we don't want to do assetCompile
                def processResources = project.tasks.named('processResources', ProcessResources)
                processResources.configure {
                    it.dependsOn(assetPackageTask)
                }
            } else if (!extension.developmentRuntime.getOrElse(false) && project.tasks.names.contains('processResources')) {
                def processResources = project.tasks.named('processResources', ProcessResources)
                processResources.configure {
                    it.with {
                        dependsOn(assetPrecompileTask)
                        from(assetPrecompileTask.get().destinationDirectory) {
                            into('assets')
                        }
                    }
                }
            } else {
                def assetTasks = [extension.jarTaskName.getOrElse(null), 'war', 'shadowJar', 'jar', 'bootWar', 'bootJar']
                project.tasks.withType(Jar).matching { it.name in assetTasks }.configureEach {
                    it.with {
                        dependsOn(assetPrecompileTask)
                        from(assetPrecompileTask.get().destinationDirectory) {
                            into('assets')
                        }
                    }
                }
            }
        }
    }

    private void configureTestRuntimeClasspath(Project project) {
        // Add the asset-pipeline-gradle dependency to the testRuntime classpath
        // This is needed for asset compilers to work in tests without having to be explicitly declared
        project.plugins.withType(JavaPlugin).configureEach {
            project.configurations.named(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME).configure {
                it.extendsFrom(project.configurations.named(ASSET_DEVELOPMENT_CONFIGURATION_NAME).get())
            }
        }
    }

    private void configureBootRun(Project project) {
        // Add the asset-pipeline-gradle dependency to the bootRun classpath.
        // This is needed for asset compilers to work in development, but not be included in the production jars
        def configureBootRun = { JavaExec runTask ->
            [ASSET_DEVELOPMENT_CONFIGURATION_NAME, ASSET_CONFIGURATION_NAME].each { String configurationName ->
                project.configurations.named(configurationName).configure {
                    project.logger.info('asset-pipeline: Adding {} configuration to {} classpath', configurationName, runTask.name)
                    runTask.classpath += it
                }
            }
        }
        project.plugins.withId('org.springframework.boot') {
            ['bootRun', 'bootTestRun'].each { String taskName ->
                project.tasks.named(taskName, JavaExec, configureBootRun)
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
