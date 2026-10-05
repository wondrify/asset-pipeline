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

import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import grails.spring.BeanBuilder
import org.grails.config.PropertySourcesConfig
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.NestedExceptionUtils
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.StandardEnvironment
import org.springframework.mock.web.MockFilterConfig
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.support.GenericWebApplicationContext
import spock.lang.Specification
import spock.util.environment.RestoreSystemProperties

/**
 * grails.assets.maxCacheSize from the application's configuration to the filter's cache, as this line
 * gets it there: doWithSpring fills AssetPipelineConfigHolder and registers a filter instance, and the
 * servlet container's init(FilterConfig) sizes the cache from the holder.
 */
class AssetPipelineGrailsPluginSpec extends Specification {

    MockServletContext servletContext
    GenericWebApplicationContext applicationContext
    GrailsApplication grailsApplication

    void setup() {
        servletContext = new MockServletContext()
        applicationContext = new GenericWebApplicationContext(servletContext)
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, applicationContext)
        grailsApplication = new DefaultGrailsApplication()
    }

    void cleanup() {
        applicationContext.close()
        AssetPipelineConfigHolder.manifest = null
        AssetPipelineConfigHolder.config = [:]
    }

    void 'grails.assets.maxCacheSize sizes the filter cache as the servlet container starts the filter'() {
        given:
        configure('grails.assets.maxCacheSize': '250')

        when:
        startWithPlugin()

        then:
        startedFilter().cache.getMaximumSize() == 250
    }

    @RestoreSystemProperties
    void 'grails.assets.maxCacheSize can be given as a system property'() {
        given: 'the property sources a Grails application reads, system properties among them'
        System.setProperty('grails.assets.maxCacheSize', '250')
        grailsApplication.config = new PropertySourcesConfig(new StandardEnvironment().propertySources)

        when:
        startWithPlugin()

        then:
        startedFilter().cache.getMaximumSize() == 250
    }

    void 'an invalid grails.assets.maxCacheSize fails as the servlet container starts the filter'() {
        given:
        configure('grails.assets.maxCacheSize': '1.5')

        when:
        startWithPlugin()

        then:
        Exception e = thrown()
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e)
        cause instanceof IllegalArgumentException
        cause.message.contains('grails.assets.maxCacheSize')
    }

    private void configure(Map<String, Object> properties) {
        MutablePropertySources sources = new MutablePropertySources()
        sources.addFirst(new MapPropertySource('test', properties))
        grailsApplication.config = new PropertySourcesConfig(sources)
    }

    private AssetPipelineFilter startedFilter() {
        applicationContext.getBean('assetPipelineFilter', FilterRegistrationBean).filter as AssetPipelineFilter
    }

    private void startWithPlugin() {
        GenericBeanDefinition abstractLocator = new GenericBeanDefinition()
        abstractLocator.abstract = true
        abstractLocator.propertyValues.add('searchLocations', [])
        applicationContext.registerBeanDefinition('abstractGrailsResourceLocator', abstractLocator)

        AssetPipelineGrailsPlugin plugin = new AssetPipelineGrailsPlugin()
        plugin.grailsApplication = grailsApplication
        plugin.applicationContext = applicationContext
        BeanBuilder beans = new BeanBuilder(null, getClass().classLoader)
        beans.beans(plugin.doWithSpring())
        beans.registerBeans(applicationContext)
        applicationContext.refresh()

        // As the servlet container does once the application has started
        startedFilter().init(new MockFilterConfig(servletContext, 'assetPipelineFilter'))
    }
}
