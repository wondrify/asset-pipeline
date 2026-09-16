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
package asset.pipeline.grails

import asset.pipeline.AssetPipelineAutoConfiguration

import org.springframework.boot.autoconfigure.AutoConfiguration
import spock.lang.Specification

class AssetPipelineAutoConfigurationSpec extends Specification {

    private static final String URL_MAPPINGS_AUTO_CONFIGURATION =
            'org.grails.plugins.web.mapping.UrlMappingsAutoConfiguration'

    void 'the auto-configuration is read where the Grails url mappings are absent'() {
        given: 'a Spring Boot application using the tag libraries without the rest of Grails, which brings no grails-url-mappings'
        ClassLoader classLoader = withoutUrlMappings()
        Class<?> autoConfigurationClass = classLoader.loadClass(AssetPipelineAutoConfiguration.name)

        when: 'Spring reads the annotation to order the auto-configurations'
        def annotation = autoConfigurationClass.getAnnotation(classLoader.loadClass(AutoConfiguration.name))
        annotation.before()

        then: 'nothing has to be loaded to read it'
        noExceptionThrown()
    }

    void 'the Grails url mappings auto-configuration is still ordered against'() {
        expect:
        AssetPipelineAutoConfiguration.getAnnotation(AutoConfiguration).beforeName() ==
                [URL_MAPPINGS_AUTO_CONFIGURATION] as String[]
    }

    private static ClassLoader withoutUrlMappings() {
        URL[] classPath = System.getProperty('java.class.path')
                .split(File.pathSeparator)
                .collect { new File(it).toURI().toURL() } as URL[]
        new URLClassLoader(classPath, ClassLoader.platformClassLoader) {

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name == URL_MAPPINGS_AUTO_CONFIGURATION) {
                    throw new ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }
    }

}
