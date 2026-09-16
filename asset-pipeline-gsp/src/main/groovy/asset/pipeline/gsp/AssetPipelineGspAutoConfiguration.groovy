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
package asset.pipeline.gsp

import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.grails.AssetMethodTagLib
import asset.pipeline.grails.AssetProcessorService
import asset.pipeline.grails.AssetsTagLib

import grails.compiler.beans.GrailsBeans
import grails.core.GrailsApplication
import groovy.util.logging.Slf4j
import org.grails.config.NavigableMap
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication

/**
 * The asset pipeline's tag libraries, for an application that renders GSP views without being a
 * Grails application.
 *
 * <p>A Grails application finds them by scanning the artefacts of the plugin that carries them, and
 * everything they need is set up by its descriptor as the context is built. An application with no
 * plugins runs none of that: GSP is auto-configured for it instead, and the tag libraries have to be
 * beans of its context, which is what this contributes. Adding this library is what says so - there
 * is no condition here deciding which kind of application it landed in, which is what kept getting
 * decided wrongly while these beans lived in the plugin.
 *
 * <p>What serves the assets they point at - the resolvers, and the manifest that gives a built asset
 * its digest name - comes from {@code asset-pipeline-spring-boot}, which such an application adds
 * alongside this. With this module and not that one the tag libraries render development markup and
 * non-digest urls, because nothing has told the pipeline where the assets are.
 */
@Slf4j
@GrailsBeans
@AutoConfiguration(afterName = [
        'grails.gsp.boot.GspAutoConfiguration',
        'asset.pipeline.AssetPipelineAutoConfiguration'
])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AssetPipelineGspAutoConfiguration {

    def beans = {

        /**
         * The tag library the pages use. What it takes is given to it here rather than found by
         * name, as a Grails application would: the service it asks for asset urls, and the
         * application it reads its configuration from.
         */
        bean(AssetsTagLib)
                .conditionalOnMissingBean()
                { AssetProcessorService assetProcessorService, GrailsApplication grailsApplication ->
                    AssetsTagLib tagLib = new AssetsTagLib()
                    tagLib.assetProcessorService = assetProcessorService
                    tagLib.grailsApplication = grailsApplication
                    tagLib
                }

        /**
         * The second tag library the plugin carries, which puts {@code assetPath} in the default
         * namespace. The one above builds every url by calling it, so both are needed - as a Grails
         * application gets both, being artefacts of the same plugin.
         */
        bean(AssetMethodTagLib)
                .conditionalOnMissingBean()
                { AssetProcessorService assetProcessorService ->
                    AssetMethodTagLib tagLib = new AssetMethodTagLib()
                    tagLib.assetProcessorService = assetProcessorService
                    tagLib
                }

        /**
         * Hands the pipeline the {@code grails.assets} settings, which the plugin descriptor does
         * for a Grails application. Without this the settings an application writes are read by the
         * tag libraries but not by the pipeline itself, so a configured {@code mapping} is quietly
         * ignored while every other setting is honoured.
         */
        bean('assetPipelineConfiguration', InitializingBean)
                .conditionalOnMissingBeanName()
                { GrailsApplication grailsApplication ->
                    { ->
                        def configured = grailsApplication.config.getProperty('grails.assets', Map, [:])
                        Map settings = configured instanceof NavigableMap ?
                                configured.toFlatConfig() : configured
                        Map held = AssetPipelineConfigHolder.config
                        if (held && held != settings) {
                            // The pipeline keeps its settings on a static, which outlives an
                            // application context: a devtools reload builds a new context in the
                            // same JVM and finds the settings of the one before it. The application
                            // being built now is the one that asked, so its settings win - the
                            // alternative is a changed setting quietly ignored until the JVM restarts.
                            log.debug('Replacing the asset pipeline settings held from an earlier context: {} -> {}',
                                    held, settings)
                        }
                        AssetPipelineConfigHolder.config = settings
                    } as InitializingBean
                }
    }

}
