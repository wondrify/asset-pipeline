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
package asset.pipeline.grails;

import java.util.List;
import java.util.Map;

import asset.pipeline.AssetPipelineFilter;

import org.grails.web.config.http.GrailsFilters;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * The two bean definitions this plugin contributes that describe more than a class and a set of
 * qualifiers, which is all {@code BeanRegistry.Spec} and a {@code @Bean} method can express.
 *
 * <p>{@code assetResourceLocator} inherits its search locations from
 * {@code abstractGrailsResourceLocator}, a classless abstract definition that Grails registers for
 * the purpose; a parent has no equivalent in either. {@code assetPipelineFilter} holds the filter
 * as a nested bean definition rather than an instance, which a factory method cannot produce.
 *
 * <p>Reaching the registry directly expresses both, which is the shape Grails itself uses for
 * {@code abstractGrailsResourceLocator}.
 */
public class AssetPipelineBeanDefinitionRegistrar implements BeanDefinitionRegistryPostProcessor {

    static final String RESOURCE_LOCATOR_BEAN_NAME = "assetResourceLocator";

    static final String FILTER_BEAN_NAME = "assetPipelineFilter";

    private static final String ABSTRACT_GRAILS_RESOURCE_LOCATOR = "abstractGrailsResourceLocator";

    private static final String DEFAULT_MAPPING = "assets";

    private final String mapping;

    public AssetPipelineBeanDefinitionRegistrar(Map<String, Object> assetsConfig) {
        this.mapping = mappingOf(assetsConfig);
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        // An application that declares either itself keeps its own.
        if (!registry.containsBeanDefinition(RESOURCE_LOCATOR_BEAN_NAME)) {
            registry.registerBeanDefinition(RESOURCE_LOCATOR_BEAN_NAME, resourceLocatorDefinition());
        }
        if (!registry.containsBeanDefinition(FILTER_BEAN_NAME)) {
            registry.registerBeanDefinition(FILTER_BEAN_NAME, filterRegistrationDefinition());
        }
    }

    GenericBeanDefinition resourceLocatorDefinition() {
        GenericBeanDefinition definition = new GenericBeanDefinition();
        definition.setBeanClass(AssetResourceLocator.class);
        definition.setParentName(ABSTRACT_GRAILS_RESOURCE_LOCATOR);
        return definition;
    }

    GenericBeanDefinition filterRegistrationDefinition() {
        // Use a nested bean definition rather than a constructed instance. A bean definition is
        // metadata, and Spring AOT turns it into generated Java source; there is no way to generate
        // code that rebuilds an arbitrary pre-built object, so `new AssetPipelineFilter()` fails
        // ahead-of-time processing with UnsupportedTypeValueCodeGenerationException.
        GenericBeanDefinition filter = new GenericBeanDefinition();
        filter.setBeanClass(AssetPipelineFilter.class);

        GenericBeanDefinition registration = new GenericBeanDefinition();
        registration.setBeanClass(FilterRegistrationBean.class);
        registration.getPropertyValues().add("order", GrailsFilters.ASSET_PIPELINE_FILTER.getOrder());
        registration.getPropertyValues().add("filter", filter);
        registration.getPropertyValues().add("urlPatterns", urlPatterns());
        return registration;
    }

    private List<String> urlPatterns() {
        return List.of(this.mapping == null || this.mapping.isEmpty() ? "/*" : "/" + this.mapping + "/*");
    }

    private static String mappingOf(Map<String, Object> assetsConfig) {
        if (assetsConfig == null || !assetsConfig.containsKey("mapping")) {
            return DEFAULT_MAPPING;
        }
        Object configured = assetsConfig.get("mapping");
        return configured == null ? null : configured.toString();
    }

}
