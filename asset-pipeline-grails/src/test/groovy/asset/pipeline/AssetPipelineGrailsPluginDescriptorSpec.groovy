/*
 * Copyright 2026 the original author or authors.
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
package asset.pipeline

import asset.pipeline.fs.FileSystemAssetResolver
import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import grails.plugins.GrailsPlugin
import grails.plugins.GrailsPluginManager
import grails.util.GrailsUtil
import org.apache.grails.core.plugins.ClasspathPluginFinder
import org.apache.grails.core.plugins.PluginInfo
import org.grails.plugins.CoreGrailsPlugin
import org.grails.plugins.DefaultGrailsPlugin
import org.springframework.context.support.GenericApplicationContext

import spock.lang.Specification

class AssetPipelineGrailsPluginDescriptorSpec extends Specification {

    GrailsApplication grailsApplication = new DefaultGrailsApplication()
    GenericApplicationContext applicationContext = new GenericApplicationContext()
    Collection resolvers

    void setup() {
        resolvers = AssetPipelineConfigHolder.resolvers
        AssetPipelineConfigHolder.resolvers = []
    }

    void cleanup() {
        applicationContext.close()
        AssetPipelineConfigHolder.resolvers = resolvers
    }

    void 'the compiled descriptor makes the plugin discoverable, loading after url mappings'() {
        when: 'the class path is searched the way an application searches it at startup'
        PluginInfo plugin = new ClasspathPluginFinder()
                .findClasspathPlugins(getClass().classLoader, GrailsUtil.grailsVersion)
                .find { it.pluginClass == AssetPipelineGrailsPlugin }

        then:
        plugin != null
        plugin.name == 'assetPipeline'
        plugin.loadAfterNames.toList() == ['urlMappings']
        plugin.dependsOnNames.length == 0
    }

    void 'the grails-app/assets directory of a plugin loaded from a project directory is resolved'() {
        given: 'this plugin, loaded from its compiled classes, and a core plugin, loaded from its jar'
        GrailsPlugin fromDirectory = new DefaultGrailsPlugin(AssetPipelineGrailsPlugin, grailsApplication)
        GrailsPlugin fromJar = new DefaultGrailsPlugin(CoreGrailsPlugin, grailsApplication)
        GrailsPluginManager pluginManager = Stub {
            getAllPlugins() >> ([fromDirectory, fromJar] as GrailsPlugin[])
        }
        applicationContext.beanFactory.registerSingleton('pluginManager', pluginManager)
        applicationContext.refresh()

        and:
        AssetPipelineGrailsPlugin plugin = new AssetPipelineGrailsPlugin()
        plugin.grailsApplication = grailsApplication
        plugin.applicationContext = applicationContext

        when:
        plugin.doWithApplicationContext()
        List<FileSystemAssetResolver> pluginResolvers = AssetPipelineConfigHolder.resolvers
                .findAll { it instanceof FileSystemAssetResolver && it.name != 'application' } as List<FileSystemAssetResolver>

        then: 'only the plugin with a project directory contributes its assets'
        fromDirectory.projectDirectory != null
        fromJar.projectDirectory == null
        pluginResolvers*.name == [fromDirectory.name]
        pluginResolvers[0].baseDirectory.canonicalPath ==
                new File(fromDirectory.projectDirectory, 'grails-app/assets').canonicalPath
    }
}
