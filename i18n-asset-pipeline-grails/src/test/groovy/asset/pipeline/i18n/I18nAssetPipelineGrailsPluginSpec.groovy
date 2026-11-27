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

import grails.util.GrailsUtil
import org.apache.grails.core.plugins.ClasspathPluginFinder
import org.apache.grails.core.plugins.PluginInfo

import spock.lang.Specification

class I18nAssetPipelineGrailsPluginSpec extends Specification {

    void 'the compiled descriptor makes the plugin discoverable, watching the i18n message bundles'() {
        when: 'the class path is searched the way an application searches it at startup'
        PluginInfo plugin = new ClasspathPluginFinder()
                .findClasspathPlugins(getClass().classLoader, GrailsUtil.grailsVersion)
                .find { it.pluginClass == I18nAssetPipelineGrailsPlugin }

        then:
        plugin != null
        plugin.name == 'i18nAssetPipeline'
        plugin.definition.watchedResources.toList() == ['file:./grails-app/i18n/*.properties']
    }
}
