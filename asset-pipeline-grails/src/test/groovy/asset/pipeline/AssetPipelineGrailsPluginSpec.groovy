/*
 * Copyright 2025 the original author or authors.
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

import asset.pipeline.grails.AssetPipelineBeanDefinitionRegistrar
import asset.pipeline.grails.AssetResourceLocator
import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import jakarta.servlet.Filter
import org.grails.config.PropertySourcesConfig
import org.grails.web.config.http.GrailsFilters
import org.springframework.aot.test.generate.TestGenerationContext
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.NestedExceptionUtils
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.context.aot.ApplicationContextAotGenerator
import org.springframework.mock.web.MockFilterConfig
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.support.GenericWebApplicationContext
import java.util.function.Supplier

import spock.lang.Specification
import spock.util.environment.RestoreSystemProperties

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

    void 'the filter is contributed as a nested bean definition rather than a constructed instance'() {
        when: 'the plugin contributes its bean definitions'
        BeanDefinition registration = filterRegistrationDefinition()
        Object filterValue = registration.propertyValues.getPropertyValue('filter').value

        then: 'the filter is described by metadata, which Spring AOT is able to generate source for'
        filterValue instanceof BeanDefinition
        ((BeanDefinition) filterValue).beanClassName == AssetPipelineFilter.name

        and: 'no pre-built filter instance is embedded in the definition'
        !(filterValue instanceof Filter)

        and: 'the rest of the registration is unchanged'
        registration.propertyValues.getPropertyValue('order').value == GrailsFilters.ASSET_PIPELINE_FILTER.order
        registration.propertyValues.getPropertyValue('urlPatterns').value == ['/assets/*']
    }

    void 'the container instantiates the filter and it still initialises when the servlet container starts it'() {
        given: 'the filter registration contributed by the plugin'
        BeanDefinition registration = filterRegistrationDefinition()

        when: 'the container refreshes, running the full lifecycle of the nested filter bean'
        applicationContext.registerBeanDefinition('assetPipelineFilter', registration)
        applicationContext.refresh()
        FilterRegistrationBean registrationBean = applicationContext.getBean('assetPipelineFilter', FilterRegistrationBean)

        then: 'creating the bean succeeds, even though Spring calls initFilterBean() before any FilterConfig exists'
        noExceptionThrown()
        registrationBean.filter instanceof AssetPipelineFilter

        when: 'the servlet container initialises the filter'
        AssetPipelineFilter filter = registrationBean.filter as AssetPipelineFilter
        filter.init(new MockFilterConfig(servletContext, 'assetPipelineFilter'))

        then: 'it is wired to the servlet context and the web application context'
        filter.servletContext.is(servletContext)
        filter.applicationContext.is(applicationContext)
    }

    void 'the filter registration survives Spring ahead-of-time processing'() {
        given: 'the filter registration contributed by the plugin'
        applicationContext.registerBeanDefinition('assetPipelineFilter', filterRegistrationDefinition())

        when: 'the definitions are processed ahead of time, as they are when building a native image'
        new ApplicationContextAotGenerator().processAheadOfTime(applicationContext, new TestGenerationContext())

        then: 'no value in the definition defeats code generation'
        noExceptionThrown()
    }

    void 'the resource locator inherits its search locations from the abstract Grails definition'() {
        when: 'the plugin contributes its bean definitions'
        BeanDefinition locator = registrarDefinitions().getBeanDefinition('assetResourceLocator')

        then: 'it is a child of the definition Grails registers for the purpose'
        locator.parentName == 'abstractGrailsResourceLocator'
        locator.beanClassName == AssetResourceLocator.name
    }

    void 'a configured mapping reaches the filter url patterns'() {
        expect:
        registrarDefinitions(mapping: 'static')
                .getBeanDefinition('assetPipelineFilter')
                .propertyValues.getPropertyValue('urlPatterns').value == ['/static/*']
    }

    void 'what beanRegistrar() contributes survives Spring ahead-of-time processing'() {
        given: 'the abstract parent Grails registers for resource locators, which the registrar inherits from'
        GenericBeanDefinition abstractLocator = new GenericBeanDefinition()
        abstractLocator.abstract = true
        abstractLocator.propertyValues.add('searchLocations', [])
        applicationContext.registerBeanDefinition('abstractGrailsResourceLocator', abstractLocator)

        and: 'the plugin\'s own BeanRegistrar, registered the way a Grails application registers it'
        AssetPipelineGrailsPlugin plugin = new AssetPipelineGrailsPlugin()
        plugin.grailsApplication = grailsApplication
        plugin.applicationContext = applicationContext
        applicationContext.register(plugin.beanRegistrar())

        when: 'the definitions are processed ahead of time, as they are when building a native image'
        new ApplicationContextAotGenerator().processAheadOfTime(applicationContext, new TestGenerationContext())

        then: 'the registrar that carries the definitions #465 is about is itself generatable'
        noExceptionThrown()

        and: 'having actually contributed them, rather than passing over an empty context'
        applicationContext.containsBeanDefinition('assetPipelineFilter')
        applicationContext.containsBeanDefinition('assetResourceLocator')
    }

    void 'grails.assets.maxCacheSize sizes the filter cache as Spring creates the filter'() {
        given:
        configure('grails.assets.maxCacheSize': '250')

        when:
        startWithPlugin()

        then:
        startedFilter().cache.maximumSize == 250
    }

    @RestoreSystemProperties
    void 'grails.assets.maxCacheSize can be given as a system property'() {
        given: 'the property sources a Grails application reads, system properties among them'
        System.setProperty('grails.assets.maxCacheSize', '250')
        grailsApplication.config = new PropertySourcesConfig(new StandardEnvironment().propertySources)

        when:
        startWithPlugin()

        then:
        startedFilter().cache.maximumSize == 250
    }

    void 'an environment variable reaches grails.assets.maxCacheSize only through a placeholder'() {
        given: 'environment variables ahead of application.yml, as a Grails application orders them'
        MutablePropertySources sources = new MutablePropertySources()
        sources.addLast(new SystemEnvironmentPropertySource('systemEnvironment',
                [GRAILS_ASSETS_MAXCACHESIZE: '50', ASSET_CACHE_SIZE: '250'] as Map<String, Object>))
        sources.addLast(new MapPropertySource('application', ['grails.assets.maxCacheSize': '${ASSET_CACHE_SIZE:10000}'] as Map<String, Object>))
        grailsApplication.config = new PropertySourcesConfig(sources)

        when:
        startWithPlugin()

        then: 'the placeholder takes ASSET_CACHE_SIZE there, and GRAILS_ASSETS_MAXCACHESIZE, which would win if Grails mapped it, does not'
        startedFilter().cache.maximumSize == 250
    }

    void 'an invalid grails.assets.maxCacheSize stops the application starting'() {
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
        applicationContext.register(plugin.beanRegistrar())
        applicationContext.refresh()
    }

    private BeanDefinition filterRegistrationDefinition() {
        registrarDefinitions().getBeanDefinition('assetPipelineFilter')
    }

    /** What the plugin contributes through its BeanDefinitionRegistryPostProcessor. */
    private BeanDefinitionRegistry registrarDefinitions(Map<String, Object> assetsConfig = [:]) {
        BeanDefinitionRegistry registry = new SimpleBeanDefinitionRegistry()
        new AssetPipelineBeanDefinitionRegistrar(assetsConfig).postProcessBeanDefinitionRegistry(registry)
        registry
    }

}
