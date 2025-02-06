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
package asset.pipeline

import asset.pipeline.fs.ClasspathAssetResolver
import asset.pipeline.fs.FileSystemAssetResolver
import asset.pipeline.grails.AssetResourceLocator
import grails.plugins.Plugin
import grails.util.BuildSettings
import groovy.util.logging.Slf4j
import org.grails.plugins.BinaryGrailsPlugin
import org.grails.web.config.http.GrailsFilters
import org.springframework.util.ClassUtils

@Slf4j
class AssetPipelineGrailsPlugin extends Plugin {
    def grailsVersion   = "7.0 > *"
    def title           = "Asset Pipeline Plugin"
    def author          = "David Estes"
    def authorEmail     = "destes@bcap.com"
    def description     = 'The Asset-Pipeline is a plugin used for managing and processing static assets in Grails applications. Asset-Pipeline functions include processing and minification of both CSS and JavaScript files. It is also capable of being extended to compile custom static assets, such as CoffeeScript.'
    def documentation   = "http://www.asset-pipeline.com"
    def license         = "APACHE"
    def organization    = [ name: "Bertram Capital", url: "http://www.bertramcapital.com/" ]
    def issueManagement = [ system: "GITHUB", url: "http://github.com/bertramdev/grails-asset-pipeline/issues" ]
    def scm             = [ url: "http://github.com/bertramdev/grails-asset-pipeline" ]
    def pluginExcludes  = [
        "grails-app/assets/**",
        "test/dummy/**"
    ]
    def developers = [[name: 'Brian Wheeler']]
    def loadAfter = ['url-mappings']

    void doWithApplicationContext() {
        //Register Plugin Paths
        def ctx = applicationContext
        AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver('application',"${BuildSettings.BASE_DIR}/grails-app/assets"))

        try {
            ctx.pluginManager.getAllPlugins()?.each { plugin ->
                if(plugin instanceof BinaryGrailsPlugin) {
                    def projectDirectory = plugin.getProjectDirectory()
                    if(projectDirectory) {
                        String assetPath = new File(plugin.getProjectDirectory(),"grails-app/assets").canonicalPath
                        AssetPipelineConfigHolder.registerResolver(new FileSystemAssetResolver(plugin.name,assetPath))
                    }
                }
            }
        } catch(ex) {
            log.warn("Error loading exploded plugins ${ex}",ex)
        }
        AssetPipelineConfigHolder.registerResolver(new ClasspathAssetResolver('classpath', 'META-INF/assets','META-INF/assets.list'))
        AssetPipelineConfigHolder.registerResolver(new ClasspathAssetResolver('classpath', 'META-INF/static'))
        AssetPipelineConfigHolder.registerResolver(new ClasspathAssetResolver('classpath', 'META-INF/resources'))
    }

    Closure doWithSpring() {{->
        def application = grailsApplication
        def config = application.config
        def assetsConfig = config.getProperty('grails.assets', Map, [:])

        def manifestProps = new Properties()
        def manifestFile


        try {
            manifestFile = applicationContext.getResource("assets/manifest.properties")
            if(!manifestFile.exists()) {
                manifestFile = applicationContext.getResource("classpath:assets/manifest.properties")
            }
        } catch(e) {
            if(application.warDeployed) {
                log.warn "Unable to find asset-pipeline manifest, etags will not be properly generated"
            }
        }

        def useManifest = assetsConfig.useManifest ?: true

        if(useManifest && manifestFile?.exists()) {
            try {
                manifestProps.load(manifestFile.inputStream)
                assetsConfig.manifest = manifestProps
                AssetPipelineConfigHolder.manifest = manifestProps
            } catch(e) {
                log.warn "Failed to load Manifest"
            }
        }

        if(assetsConfig instanceof org.grails.config.NavigableMap) {
            AssetPipelineConfigHolder.config = assetsConfig.toFlatConfig()
        } else {
            AssetPipelineConfigHolder.config = assetsConfig
        }

        if (BuildSettings.TARGET_DIR) {
            AssetPipelineConfigHolder.config.cacheLocation = new File((File) BuildSettings.TARGET_DIR, ".assetcache").canonicalPath
        }

        assetResourceLocator(AssetResourceLocator) { bean ->
            bean.parent = "abstractGrailsResourceLocator"
        }

        def mapping = assetsConfig.containsKey('mapping') ? assetsConfig.mapping?.toString() : 'assets'

        ClassLoader classLoader = application.classLoader
        Class registrationBean = ClassUtils.isPresent("org.springframework.boot.web.servlet.FilterRegistrationBean", classLoader ) ?
                                    ClassUtils.forName("org.springframework.boot.web.servlet.FilterRegistrationBean", classLoader) :
                                    ClassUtils.forName("org.springframework.boot.context.embedded.FilterRegistrationBean", classLoader)
        assetPipelineFilter(registrationBean) {
            order = GrailsFilters.ASSET_PIPELINE_FILTER.order
            filter = new asset.pipeline.AssetPipelineFilter()
            if(!mapping) {
                urlPatterns = ["/*".toString()]
            } else {
                urlPatterns = ["/${mapping}/*".toString()]
            }
            
        }
    }}
}
