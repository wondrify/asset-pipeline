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
package asset.pipeline.springboot

import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import spock.lang.Specification

/**
 * What serves the compiled assets in a Spring Boot application that adds this library.
 */
class AssetPipelineAutoConfigurationSpec extends Specification {

    void 'the auto-configuration is one Spring Boot will find'() {
        given: 'the tests above import the class themselves, so none of them would notice its absence here'
        String imports = getClass().getResourceAsStream(
                '/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports').text

        expect:
        imports.readLines()*.trim().contains(AssetPipelineAutoConfiguration.name)
    }

    private WebApplicationContextRunner contextRunner() {
        new WebApplicationContextRunner().withUserConfiguration(AssetPipelineAutoConfiguration)
    }

    void 'the filter that serves the assets is registered'() {
        expect:
        contextRunner().run { context ->
            FilterRegistrationBean registration = context.getBean(FilterRegistrationBean)
            assert registration.urlPatterns.toList() == ['/assets/*']
            // built without a manifest, so what serves an asset is what compiles it as it is asked for
            assert registration.filter instanceof AssetPipelineDevFilter
        }
    }

    void 'an application that does not want the filter says so'() {
        expect: 'a library on the class path is not the same as a library that was asked for'
        contextRunner().withPropertyValues('assets.enabled=false').run { context ->
            assert context.getBeanNamesForType(FilterRegistrationBean).length == 0
        }
    }

    void 'an application that scans the configuration itself can still decline the filter'() {
        given: 'the readme told applications to scan this package long before there was a switch'
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withUserConfiguration(AssetPipelineService)
                .withConfiguration(AutoConfigurations.of(AssetPipelineAutoConfiguration))
                .withPropertyValues('assets.enabled=false')

        expect: 'the switch reaches the configuration that defines the filter, not only the one that imports it'
        runner.run { context ->
            assert context.getBeanNamesForType(FilterRegistrationBean).length == 0
        }
    }

    void 'an application already scanning the configuration ends up with one filter, not two'() {
        given: 'what the readme has told applications to do since before there was an auto-configuration'
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withUserConfiguration(AssetPipelineService)
                .withConfiguration(AutoConfigurations.of(AssetPipelineAutoConfiguration))

        // Not a test of the backoff: a scanned configuration class is dropped from the
        // auto-configuration's import before any condition is asked, so this holds with the
        // condition removed. The specification below is the one that covers the condition.
        expect: 'one definition of the filter, whichever of the two paths registered it, and a context that started'
        runner.run { context ->
            assert !context.startupFailure
            assert context.getBeanNamesForType(FilterRegistrationBean).length == 1
        }
    }

    void 'an application that registers the filter itself keeps its own'() {
        given:
        FilterRegistrationBean ownRegistration = new FilterRegistrationBean()

        expect:
        contextRunner()
                .withBean('assetPipelineFilterBean', FilterRegistrationBean, () -> ownRegistration)
                .run { context ->
                    assert context.getBean(FilterRegistrationBean).is(ownRegistration)
                }
    }

}
