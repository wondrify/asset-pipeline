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
package asset.pipeline.processors

import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.Executors

class JsModuleImportsSpec extends Specification {
    void "analysis reads source as data and never executes it"() {
        given:
        String source = "throw new Error('must not execute'); import './dep.js';"

        expect:
        JsModuleImports.find(source, 'example.js')*.name == ['./dep.js']
    }

    void "member calls regexes and template text are not module imports"() {
        given:
        String source = '''
const re = /import ['"].*[.]js['"]/;
const template = `import './fake.js'`;
const text = "export * from './fake.js'";
loader?.import('./fake.js');
const object = { import(value) { return value; } };
const url = import.meta.url;
'''

        expect:
        JsModuleImports.find(source, 'example.js').empty
    }

    void "literal imports inside computed expressions are still found"() {
        given:
        String source = "import(pick(import('./inner.js')));"

        expect:
        JsModuleImports.find(source, 'example.js')*.name == ['./inner.js']
    }

    void "parallel workers cannot overwrite another source's import offsets"() {
        given:
        def executor = Executors.newFixedThreadPool(4)
        List<Callable<Boolean>> calls = (1..32).collect { int index ->
            { ->
                String name = "./module-${index}.js"
                String source = (' ' * index) + "import '${name}';"
                def imports = JsModuleImports.find(source, "${index}.js")
                imports.size() == 1 && imports[0].name == name &&
                    source.substring(imports[0].start, imports[0].end) == "'${name}'"
            } as Callable<Boolean>
        }

        expect:
        executor.invokeAll(calls).every { it.get() }

        cleanup:
        executor.shutdownNow()
    }

    void "lexical errors identify the source and do not poison the shared lexer"() {
        when:
        JsModuleImports.find("import './unterminated", 'broken-module.js')

        then:
        IllegalArgumentException failure = thrown()
        failure.message.contains('broken-module.js')
        failure.cause != null

        and:
        JsModuleImports.find("import './valid.js';", 'valid.js')*.name == ['./valid.js']
    }
}
