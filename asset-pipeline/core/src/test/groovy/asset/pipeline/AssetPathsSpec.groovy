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
        ['favicon.ico', 'apple-touch-icon.png']                       | ['favicon.ico', 'apple-touch-icon.png']
        ['/favicon.ico', ' apple-touch-icon.png ']                    | ['favicon.ico', 'apple-touch-icon.png']
        ['.well-known/favicon.ico']                         | ['.well-known/favicon.ico'] // a leading dot is not a . segment
        'favicon.ico, apple-touch-icon.png'                 | ['favicon.ico', 'apple-touch-icon.png']
        ['favicon.ico', '/favicon.ico']                     | ['favicon.ico']
        new LinkedHashSet(['apple-touch-icon.png', 'favicon.ico'])    | ['apple-touch-icon.png', 'favicon.ico']
        ['assets-logo.png', 'assetsx/a.txt']                | ['assets-logo.png', 'assetsx/a.txt']
        ['/favicon.ico', 'apple-touch-icon.png'] as String[] | ['favicon.ico', 'apple-touch-icon.png']
    }

    void "rootPaths skips the blank entries a stray comma leaves, in '#configured'"() {
        expect: 'the same whether the string is split here, as in Grails, or by the Spring Boot binder'
        AssetPaths.rootPaths(configured, 'assets') == ['favicon.ico', 'apple-touch-icon.png']
        where:
        configured << ['favicon.ico,apple-touch-icon.png,', 'favicon.ico,,apple-touch-icon.png', 'favicon.ico, ,apple-touch-icon.png', ['favicon.ico', 'apple-touch-icon.png', ''], ['favicon.ico', null, 'apple-touch-icon.png']]
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

    void "rootPaths rejects '#entry', which a servlet container never routes to a filter"() {
        when:
        AssetPaths.rootPaths([entry], 'assets')
        then:
        IllegalArgumentException e = thrown()
        e.message.contains("'${entry}' is under /")
        where:
        entry << ['WEB-INF/icon.png', 'META-INF/icon.png', 'web-inf/icon.png', '/META-INF/resources/favicon.ico']
    }

    void "rootPaths rejects '#entry', whose url would have to encode it"() {
        when:
        AssetPaths.rootPaths([entry], 'assets')
        then: 'Tomcat matches an exact pattern against the decoded path and Jetty against the encoded one, so it would be served on one and not the other'
        IllegalArgumentException e = thrown()
        e.message.contains("'${entry}' has a character a url must encode")
        where:
        entry << ['my icon.png', 'café.png', 'a"b.png', 'a<b>.png', 'a{b}.png', 'a|b.png', 'a^b.png', 'a`b.png', 'a[b].png']
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

    void "immutable #configured matches #path: #matches"() {
        expect:
        AssetPaths.matchesAny(path, AssetPaths.immutable(configured)) == matches
        where: 'patterns as includes takes them, from a list, the string a system property gives, or an array'
        configured                          | path                             | matches
        ['webjars/**']                      | 'webjars/jquery/3.7.1/jquery.js' | true
        ['webjars/**']                      | 'app.js'                         | false
        'vendor/*.js, webjars/**'           | 'vendor/lib.js'                  | true
        ['regex:.*-v\\d+\\.js'] as String[] | 'lib-v2.js'                     | true
        ['images/**/*.png']                 | 'images/logo.png'                | true
        ['', ' ']                           | 'app.js'                         | false
        null                                | 'app.js'                         | false
    }

    void "immutable rejects '#pattern', which cannot be read"() {
        when:
        AssetPaths.immutable([pattern])
        then:
        IllegalArgumentException e = thrown()
        e.message.contains("immutable pattern '${pattern}'")
        where:
        pattern << ['regex:[', 'images/{a']
    }

    void "rootPaths rejects a setting that is not a list"() {
        when:
        AssetPaths.rootPaths([favicon: 'favicon.ico'], 'assets')
        then:
        thrown(IllegalArgumentException)
    }

    void "the filter is registered under #mapping and for each root path"() {
        expect:
        AssetPaths.urlPatterns(mapping, ['favicon.ico', 'icons/apple-touch-icon.png']) == patterns
        where:
        mapping  | patterns
        'assets' | ['/assets/*', '/favicon.ico', '/icons/apple-touch-icon.png']
        'static' | ['/static/*', '/favicon.ico', '/icons/apple-touch-icon.png']
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
        '/favicon.ico;x=1'                  | ''           | '/favicon.ico'
        '/app/assets;v=1/app.js;jsessionid=a' | '/app'     | '/assets/app.js'
        '/app;jsessionid=a/assets/app.js'   | '/app;jsessionid=a' | '/assets/app.js'
        '/my%20icon.png'                    | ''           | '/my icon.png'
        '/caf%C3%A9.png'                    | ''           | '/café.png'
        '/a+b.png'                          | ''           | '/a+b.png'
        '//assets/app.js'                   | ''           | '/assets/app.js'
        '/app//assets///app.js'             | '/app'       | '/assets/app.js'
        '/bad%zz.png'                       | ''           | '/bad%zz.png'
        '/assets/a/../../favicon.ico'       | ''           | '/favicon.ico'
        '/assets/a/%2e%2e/%2e%2e/favicon.ico' | ''         | '/favicon.ico'
        '/assets/..%2F..%2Fsecret.js'       | ''           | '/secret.js'
        '/../favicon.ico'                   | ''           | '/favicon.ico'
        '/assets/./app.js'                  | ''           | '/assets/app.js'
        '/assets/js/..'                     | ''           | '/assets/'
        '/.well-known/favicon.ico'          | ''           | '/.well-known/favicon.ico'
        '/%61pp/favicon.ico'                | '/app'       | '/favicon.ico'
        '//app/favicon.ico'                 | '/app'       | '/favicon.ico'
        '/apple/favicon.ico'                | '/app'       | '/apple/favicon.ico'
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

    void "#method #path names #asset, with root paths #rootPaths"() {
        expect:
        AssetPaths.assetUrl(method, path, 'assets', rootPaths)?.with { AssetPaths.AssetUrl url -> [url.path, url.rootPath] } == asset
        where: 'only the listed urls outside the mapping name an asset, and those only for GET and HEAD, whatever the filter is registered for'
        method | path             | rootPaths       | asset
        'GET'  | '/assets/app.js' | []              | ['/app.js', false]
        'POST' | '/assets/app.js' | []              | ['/app.js', false]
        'GET'  | '/favicon.ico'   | ['favicon.ico'] | ['/favicon.ico', true]
        'HEAD' | '/favicon.ico'   | ['favicon.ico'] | ['/favicon.ico', true]
        'POST' | '/favicon.ico'   | ['favicon.ico'] | null
        'PUT'  | '/favicon.ico'   | ['favicon.ico'] | null
        'GET'  | '/favicon.ico'   | []              | null
        'GET'  | '/favicon.ico'   | null            | null
        'GET'  | '/app.js'        | ['favicon.ico'] | null
        'GET'  | '/'              | ['favicon.ico'] | null
    }
}
