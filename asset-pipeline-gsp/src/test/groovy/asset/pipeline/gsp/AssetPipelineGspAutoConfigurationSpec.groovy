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

import asset.pipeline.AssetPipelineAutoConfiguration
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.grails.AssetMethodTagLib
import asset.pipeline.grails.AssetProcessorService
import asset.pipeline.grails.AssetsTagLib
import asset.pipeline.grails.UrlBase

import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import grails.web.mapping.UrlMappingsHolder
import org.grails.config.NavigableMap
import org.grails.web.mapping.DefaultUrlMappingsHolder
import org.grails.web.pages.StandaloneTagLibraryLookup
import org.springframework.beans.BeanUtils
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

import spock.lang.PendingFeature
import spock.lang.Specification

/**
 * What this library offers an application that renders GSP views without being a Grails application:
 * the tag libraries as beans of its context, found by the tag library lookup GSP registers for it.
 *
 * <p>No plugin lifecycle runs here - no plugin manager, no artefact scanning - which is the whole
 * point. If a future change makes the tag libraries depend on any of that, this fails.
 */
class AssetPipelineGspAutoConfigurationSpec extends Specification {

    void cleanup() {
        AssetPipelineConfigHolder.config = [:]
    }

    void 'the auto-configuration is one Spring Boot will find'() {
        given: 'the tests below import the class themselves, so none of them would notice its absence here'
        String imports = getClass().getResourceAsStream(
                '/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports').text

        expect:
        imports.readLines()*.trim().contains(AssetPipelineGspAutoConfiguration.name)
    }

    /**
     * What a standalone GSP application has: GSP's own tag library lookup, a Grails application that
     * is not running a plugin lifecycle, and - from GspAutoConfiguration - a url mappings holder for
     * the plugin's link generator to autowire.
     */
    private WebApplicationContextRunner standaloneGsp() {
        new WebApplicationContextRunner()
                .withUserConfiguration(AssetPipelineAutoConfiguration, AssetPipelineGspAutoConfiguration)
                .withBean('gspTagLibraryLookup', StandaloneTagLibraryLookup,
                        () -> BeanUtils.instantiateClass(StandaloneTagLibraryLookup))
                .withBean('grailsApplication', GrailsApplication, () -> new DefaultGrailsApplication())
                .withBean('grailsUrlMappingsHolder', UrlMappingsHolder, () -> new DefaultUrlMappingsHolder([]))
    }

    @PendingFeature(reason = 'Grails 8.0.0-M6 finds tag library beans by @TagLib only; finding them by @Artefact("TagLib") arrives with apache/grails-core#16184')
    void 'the tag libraries are found by the lookup a standalone GSP application registers'() {
        expect:
        standaloneGsp().run { context ->
            StandaloneTagLibraryLookup lookup = context.getBean(StandaloneTagLibraryLookup)
            // what a page rendering <asset:stylesheet> needs to resolve
            assert lookup.lookupTagLibrary('asset', 'stylesheet') instanceof AssetsTagLib
            assert lookup.lookupTagLibrary('g', 'assetPath') instanceof AssetMethodTagLib
        }
    }

    void 'a url is built through the tag library'() {
        expect: 'resolving the tag library is not the same as it working - this is what renders'
        standaloneGsp().run { context ->
            AssetMethodTagLib tagLib = context.getBean(AssetMethodTagLib)

            // the plugin's link generator is what assetBaseUrl reads contextPath off; without it
            // this throws rather than returning, which resolving the tag library alone never showed
            assert tagLib.assetProcessorService.assetBaseUrl(null, UrlBase.CONTEXT_PATH, new NavigableMap()) ==
                    '/assets/'
        }
    }

    void 'the settings an application writes reach the pipeline itself'() {
        expect:
        standaloneGsp().withPropertyValues('grails.assets.mapping=static-assets').run { context ->
            context.getBean(AssetsTagLib)
            // read by the pipeline, rather than the default it would never have heard otherwise
            assert AssetPipelineConfigHolder.config['mapping'] == 'static-assets'
        }
    }

    void 'an application that declares its own tag libraries keeps them'() {
        given:
        AssetsTagLib own = new AssetsTagLib()

        expect:
        standaloneGsp()
                .withBean('assetsTagLib', AssetsTagLib, () -> own)
                .run { context -> assert context.getBean(AssetsTagLib).is(own) }
    }

    void 'the service the tag libraries read is the plugin auto-configuration\'s'() {
        expect: 'this library contributes the tag libraries and nothing the plugin already contributes'
        standaloneGsp().run { context ->
            assert context.getBean(AssetsTagLib).assetProcessorService
                    .is(context.getBean(AssetProcessorService))
        }
    }

}
