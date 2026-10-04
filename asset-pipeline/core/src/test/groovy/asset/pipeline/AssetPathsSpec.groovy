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

import spock.lang.Specification

class AssetPathsSpec extends Specification {

    void "rootPaths reads #configured as #paths"() {
        expect:
        AssetPaths.rootPaths(configured, 'assets') == paths
        where:
        configured                                          | paths
        null                                                | []
        []                                                  | []
        ''                                                  | []
        ['favicon.ico', 'robots.txt']                       | ['favicon.ico', 'robots.txt']
        ['/favicon.ico', ' robots.txt ']                    | ['favicon.ico', 'robots.txt']
        ['.well-known/security.txt']                        | ['.well-known/security.txt']
        'favicon.ico, apple-touch-icon.png'                 | ['favicon.ico', 'apple-touch-icon.png']
        ['favicon.ico', '/favicon.ico']                     | ['favicon.ico']
        new LinkedHashSet(['robots.txt', 'favicon.ico'])    | ['robots.txt', 'favicon.ico']
        ['assets-logo.png', 'assetsx/a.txt']                | ['assets-logo.png', 'assetsx/a.txt']
    }

    void "rootPaths skips the blank entries a stray comma leaves, in '#configured'"() {
        expect: 'the same whether the string is split here, as in Grails, or by the Spring Boot binder'
        AssetPaths.rootPaths(configured, 'assets') == ['favicon.ico', 'robots.txt']
        where:
        configured << ['favicon.ico,robots.txt,', 'favicon.ico,,robots.txt', 'favicon.ico, ,robots.txt', ['favicon.ico', 'robots.txt', ''], ['favicon.ico', null, 'robots.txt']]
    }

    void "rootPaths rejects '#entry', which does not name one asset"() {
        when:
        AssetPaths.rootPaths([entry], 'assets')
        then:
        IllegalArgumentException e = thrown()
        e.message.contains("'${entry}'")
        where:
        entry << ['/', 'images/', '*.ico', 'images/*', 'webjars/bootstrap/%/favicon.ico', 'images/%%/favicon.ico', 'a//b.txt', './favicon.ico', '../favicon.ico', 'images/../favicon.ico', 'images\\favicon.ico', 'favicon.ico?v=1', 'favicon.ico#x', 'favicon.ico;v=2']
    }

    void "rootPaths rejects '#entry', which is under the mapping #mapping and already served there"() {
        when:
        AssetPaths.rootPaths([entry], mapping)
        then:
        IllegalArgumentException e = thrown()
        e.message.contains("'${entry}'")
        where:
        entry               | mapping
        'assets'            | 'assets'
        'assets/app.js'     | 'assets'
        '/assets/app.js'    | 'assets'
        'static/a/logo.png' | 'static/a'
    }

    void "with no mapping, which serves every asset at the root, an entry is under nothing"() {
        expect:
        AssetPaths.rootPaths(['assets/app.js'], mapping) == ['assets/app.js']
        where:
        mapping << ['', null]
    }

    void "rootPaths rejects a setting that is not a list"() {
        when:
        AssetPaths.rootPaths([favicon: 'favicon.ico'], 'assets')
        then:
        thrown(IllegalArgumentException)
    }

    void "the filter is registered under #mapping and for each root path"() {
        expect:
        AssetPaths.urlPatterns(mapping, ['favicon.ico', '.well-known/security.txt']) == patterns
        where:
        mapping  | patterns
        'assets' | ['/assets/*', '/favicon.ico', '/.well-known/security.txt']
        'static' | ['/static/*', '/favicon.ico', '/.well-known/security.txt']
        ''       | ['/*']
        null     | ['/*']
    }

    void "#requestUri under context '#contextPath' is #path within the application"() {
        expect:
        AssetPaths.pathWithinContext(requestUri, contextPath) == path
        where:
        requestUri                          | contextPath  | path
        '/favicon.ico'                      | ''           | '/favicon.ico'
        '/favicon.ico'                      | '/'          | '/favicon.ico'
        '/app/favicon.ico'                  | '/app'       | '/favicon.ico'
        '/my%20app/favicon.ico'             | '/my%20app'  | '/favicon.ico'
        '/app'                              | '/app'       | '/'
        '/robots.txt;x=1'                   | ''           | '/robots.txt'
        '/app/assets;v=1/app.js;jsessionid=a' | '/app'     | '/assets/app.js'
    }

    void "#path is #asset under the mapping #mapping"() {
        expect:
        AssetPaths.pathUnderMapping(path, mapping) == asset
        where:
        path                 | mapping   | asset
        '/assets/app.js'     | 'assets'  | '/app.js'
        '/assets/'           | 'assets'  | '/'
        '/assets'            | 'assets'  | ''
        '/assets-logo.png'   | 'assets'  | null
        '/favicon.ico'       | 'assets'  | null
        '/static/a/logo.png' | 'static/a'| '/logo.png'
        '/favicon.ico'       | ''        | '/favicon.ico'
    }

    void "#path names #asset, with root paths #rootPaths"() {
        expect:
        AssetPaths.assetPath(path, 'assets', rootPaths) == asset
        where: 'only the listed urls outside the mapping name an asset, whatever the filter is registered for'
        path                 | rootPaths        | asset
        '/assets/app.js'     | []               | '/app.js'
        '/favicon.ico'       | ['favicon.ico']  | '/favicon.ico'
        '/favicon.ico'       | []               | null
        '/favicon.ico'       | null             | null
        '/app.js'            | ['favicon.ico']  | null
        '/'                  | ['favicon.ico']  | null
    }
}
