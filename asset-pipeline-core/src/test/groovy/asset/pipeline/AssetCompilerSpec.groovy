/*
 * Copyright 2014-2025 the original author or authors.
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

import spock.lang.Specification

class AssetCompilerSpec extends Specification {

    void "addVersionlessWebjarManifestEntries creates versionless manifest entries"() {
        given:
            def compiler = new AssetCompiler()
            compiler.manifestProperties = new Properties()
            compiler.manifestProperties.setProperty('webjars/jquery/3.7.1/dist/jquery.js', 'webjars/jquery/3.7.1/dist/jquery-abc123.js')
            compiler.manifestProperties.setProperty('webjars/bootstrap/5.3.0/dist/css/bootstrap.css', 'webjars/bootstrap/5.3.0/dist/css/bootstrap-def456.css')

        when:
            def method = AssetCompiler.getDeclaredMethod('addVersionlessWebjarManifestEntries')
            method.setAccessible(true)
            method.invoke(compiler)

        then:
            compiler.manifestProperties.getProperty('webjars/jquery/3.7.1/dist/jquery.js') == 'webjars/jquery/3.7.1/dist/jquery-abc123.js'
            compiler.manifestProperties.getProperty('webjars/bootstrap/5.3.0/dist/css/bootstrap.css') == 'webjars/bootstrap/5.3.0/dist/css/bootstrap-def456.css'
            compiler.manifestProperties.getProperty('webjars/dist/jquery.js') == 'webjars/jquery/3.7.1/dist/jquery-abc123.js'
            compiler.manifestProperties.getProperty('webjars/dist/css/bootstrap.css') == 'webjars/bootstrap/5.3.0/dist/css/bootstrap-def456.css'
    }

    void "addVersionlessWebjarManifestEntries handles beta versions"() {
        given:
            def compiler = new AssetCompiler()
            compiler.manifestProperties = new Properties()
            compiler.manifestProperties.setProperty('webjars/bootstrap/5.3.0-beta.2/dist/css/bootstrap.css', 'webjars/bootstrap/5.3.0-beta.2/dist/css/bootstrap-xyz789.css')

        when:
            def method = AssetCompiler.getDeclaredMethod('addVersionlessWebjarManifestEntries')
            method.setAccessible(true)
            method.invoke(compiler)

        then:
            compiler.manifestProperties.getProperty('webjars/dist/css/bootstrap.css') == 'webjars/bootstrap/5.3.0-beta.2/dist/css/bootstrap-xyz789.css'
    }

    void "addVersionlessWebjarManifestEntries ignores non-webjar entries"() {
        given:
            def compiler = new AssetCompiler()
            compiler.manifestProperties = new Properties()
            compiler.manifestProperties.setProperty('js/application.js', 'js/application-abc123.js')
            compiler.manifestProperties.setProperty('css/main.css', 'css/main-def456.css')

        when:
            def method = AssetCompiler.getDeclaredMethod('addVersionlessWebjarManifestEntries')
            method.setAccessible(true)
            method.invoke(compiler)

        then:
            compiler.manifestProperties.getProperty('js/application.js') == 'js/application-abc123.js'
            compiler.manifestProperties.getProperty('css/main.css') == 'css/main-def456.css'
            compiler.manifestProperties.size() == 2
    }

    void "addVersionlessWebjarManifestEntries ignores already-versionless webjar paths"() {
        given:
            def compiler = new AssetCompiler()
            compiler.manifestProperties = new Properties()
            compiler.manifestProperties.setProperty('webjars/somefile.js', 'webjars/somefile-abc123.js')

        when:
            def method = AssetCompiler.getDeclaredMethod('addVersionlessWebjarManifestEntries')
            method.setAccessible(true)
            method.invoke(compiler)

        then:
            compiler.manifestProperties.getProperty('webjars/somefile.js') == 'webjars/somefile-abc123.js'
            compiler.manifestProperties.size() == 1
    }

    void "addVersionlessWebjarManifestEntries handles multiple webjars with different paths"() {
        given:
            def compiler = new AssetCompiler()
            compiler.manifestProperties = new Properties()
            compiler.manifestProperties.setProperty('webjars/jquery/3.7.1/dist/jquery.js', 'webjars/jquery/3.7.1/dist/jquery-aaa111.js')
            compiler.manifestProperties.setProperty('webjars/jquery/3.7.1/dist/jquery.min.js', 'webjars/jquery/3.7.1/dist/jquery.min-aaa222.js')
            compiler.manifestProperties.setProperty('webjars/bootstrap/5.3.0/dist/js/bootstrap.js', 'webjars/bootstrap/5.3.0/dist/js/bootstrap-bbb111.js')
            compiler.manifestProperties.setProperty('webjars/bootstrap/5.3.0/dist/css/bootstrap.css', 'webjars/bootstrap/5.3.0/dist/css/bootstrap-bbb222.css')

        when:
            def method = AssetCompiler.getDeclaredMethod('addVersionlessWebjarManifestEntries')
            method.setAccessible(true)
            method.invoke(compiler)

        then:
            compiler.manifestProperties.getProperty('webjars/dist/jquery.js') == 'webjars/jquery/3.7.1/dist/jquery-aaa111.js'
            compiler.manifestProperties.getProperty('webjars/dist/jquery.min.js') == 'webjars/jquery/3.7.1/dist/jquery.min-aaa222.js'
            compiler.manifestProperties.getProperty('webjars/dist/js/bootstrap.js') == 'webjars/bootstrap/5.3.0/dist/js/bootstrap-bbb111.js'
            compiler.manifestProperties.getProperty('webjars/dist/css/bootstrap.css') == 'webjars/bootstrap/5.3.0/dist/css/bootstrap-bbb222.css'
            compiler.manifestProperties.size() == 8
    }

    void "addVersionlessWebjarManifestEntries handles path collisions from different webjar packages"() {
        given:
            def compiler = new AssetCompiler()
            compiler.manifestProperties = new Properties()
            compiler.manifestProperties.setProperty('webjars/jquery/3.7.1/dist/common.js', 'webjars/jquery/3.7.1/dist/common-aaa111.js')
            compiler.manifestProperties.setProperty('webjars/lodash/4.17.21/dist/common.js', 'webjars/lodash/4.17.21/dist/common-bbb222.js')

        when:
            def method = AssetCompiler.getDeclaredMethod('addVersionlessWebjarManifestEntries')
            method.setAccessible(true)
            method.invoke(compiler)

        then:
            def versionlessValue = compiler.manifestProperties.getProperty('webjars/dist/common.js')
            versionlessValue == 'webjars/jquery/3.7.1/dist/common-aaa111.js' ||
            versionlessValue == 'webjars/lodash/4.17.21/dist/common-bbb222.js'
            compiler.manifestProperties.getProperty('webjars/jquery/3.7.1/dist/common.js') == 'webjars/jquery/3.7.1/dist/common-aaa111.js'
            compiler.manifestProperties.getProperty('webjars/lodash/4.17.21/dist/common.js') == 'webjars/lodash/4.17.21/dist/common-bbb222.js'
    }

    void "addVersionlessWebjarManifestEntries handles nested paths"() {
        given:
            def compiler = new AssetCompiler()
            compiler.manifestProperties = new Properties()
            compiler.manifestProperties.setProperty('webjars/jquery-ui/1.13.2/ui/widgets/datepicker.js', 'webjars/jquery-ui/1.13.2/ui/widgets/datepicker-abc123.js')

        when:
            def method = AssetCompiler.getDeclaredMethod('addVersionlessWebjarManifestEntries')
            method.setAccessible(true)
            method.invoke(compiler)

        then:
            compiler.manifestProperties.getProperty('webjars/ui/widgets/datepicker.js') == 'webjars/jquery-ui/1.13.2/ui/widgets/datepicker-abc123.js'
    }

    void "addVersionlessWebjarManifestEntries handles empty manifest"() {
        given:
            def compiler = new AssetCompiler()
            compiler.manifestProperties = new Properties()

        when:
            def method = AssetCompiler.getDeclaredMethod('addVersionlessWebjarManifestEntries')
            method.setAccessible(true)
            method.invoke(compiler)

        then:
            compiler.manifestProperties.size() == 0
    }
}
