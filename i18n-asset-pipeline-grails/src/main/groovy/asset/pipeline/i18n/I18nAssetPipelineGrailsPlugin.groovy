/*
 * I18nAssetPipelineGrailsPlugin.groovy
 *
 * Copyright (c) 2014-2016, Daniel Ellermann
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

import grails.plugins.Plugin

class I18nAssetPipelineGrailsPlugin extends Plugin {

    //-- Fields ---------------------------------

    def grailsVersion = '8.0.0-SNAPSHOT > *'
    def profiles = ['web']
    def title = 'I18n Asset Pipeline Plugin'
    def author = 'Daniel Ellermann'
    def authorEmail = 'd.ellermann@amc-world.de'
    def description = 'An asset-pipeline plugin for client-side i18n.  It generates JavaScript files from i18n resources for use in client-side code.'
    def documentation = 'https://github.com/dellermann/i18n-asset-pipeline'
    def license = 'APACHE'
    def organization = [
        name: 'AMC World Technologies GmbH',
        url: 'http://www.amc-world.de/'
    ]
    def issueManagement = [
        system: 'GITHUB',
        url: 'https://github.com/dellermann/i18n-asset-pipeline/issues'
    ]
    def scm = [url: 'https://github.com/dellermann/i18n-asset-pipeline']
    def watchedResources = ['file:./grails-app/i18n/*.properties']

    //-- Public methods -------------------------

    Closure doWithSpring() {{ ->
            
    }}

    void doWithDynamicMethods() {
        // TODO Implement registering dynamic methods to classes (optional)
    }

    void doWithApplicationContext() {
        
    }

    void onChange(Map<String, Object> event) {
        // TODO Implement code that is executed when any artefact that this plugin is
    }

    void onConfigChange(Map<String, Object> event) {
        // TODO Implement code that is executed when the project configuration changes.
        // The event is the same as for 'onChange'.
    }

    void onShutdown(Map<String, Object> event) {
        // TODO Implement code that is executed when the application shuts down (optional)
    }
}
