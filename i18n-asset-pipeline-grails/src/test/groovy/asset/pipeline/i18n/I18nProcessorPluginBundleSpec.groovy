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
package asset.pipeline.i18n

import asset.pipeline.GenericAssetFile
import grails.core.DefaultGrailsApplication
import grails.plugins.GrailsPlugin
import grails.plugins.GrailsPluginManager
import grails.util.Holders
import org.grails.plugins.DefaultGrailsPlugin
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader

import spock.lang.Specification

class I18nProcessorPluginBundleSpec extends Specification {

    void cleanup() {
        Holders.pluginManager = null
    }

    void 'the message bundle of a plugin loaded from a jar is found through the plugin class loader'() {
        given: 'a plugin loaded from a jar, which has no project directory'
        GrailsPlugin fromJar = new DefaultGrailsPlugin(org.grails.plugins.CoreGrailsPlugin, new DefaultGrailsApplication())
        GrailsPluginManager pluginManager = Stub {
            getAllPlugins() >> ([fromJar] as GrailsPlugin[])
        }
        Holders.pluginManager = pluginManager

        and: 'an application that has no bundle of that name itself'
        Resource missing = Stub {
            exists() >> false
        }
        def processor = new I18nProcessor(null)
        processor.resourceLoader = Stub(ResourceLoader) {
            getResource(_) >> missing
        }

        when:
        String script = processor.process('jar.greeting', new GenericAssetFile(path: '/js/jarmessages.i18n'))

        then: 'the bundle on the plugin class loader supplies the message'
        fromJar.projectDirectory == null
        script.contains('"jar.greeting": "Hello from a jar"')
    }
}
