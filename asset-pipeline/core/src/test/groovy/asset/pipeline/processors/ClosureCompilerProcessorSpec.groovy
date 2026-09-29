/*
 * Copyright 2014 the original author or authors.
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

import asset.pipeline.AssetCompiler
import spock.lang.Specification

/**
 * Tests for {@link ClosureCompilerProcessor}, covering dynamic import support
 * added in commit b365aec (allowDynamicImport and dynamicImportAlias options).
 */
class ClosureCompilerProcessorSpec extends Specification {

    // Input with a variable specifier — closure compiler cannot statically resolve it as a module,
    // so module-load errors are avoided. Used to isolate allowDynamicImport / alias option wiring.
    static final String DYNAMIC_IMPORT_VAR =  \
         "function loadModule(a) { return import(a).then(function(m) { return m.default; }); }"

    // Input with a literal specifier — closure compiler tries to resolve './mod.js' as a module.
    static final String DYNAMIC_IMPORT_LIT =  \
         "function loadModule() { return import('./mod.js').then(function(m) { return m.default; }); }"

    // Declares a real __import__ function so the alias resolves to an existing symbol rather than
    // a bare undeclared name. Used to debug whether the NPE is caused by the missing declaration.
    static final String DYNAMIC_IMPORT_WITH_ALIAS_IMPL = """
        function __import__(path) { return Promise.resolve(path); }
        function loadModule(a) { return import(a).then(function(m) { return m.default; }); }
    """.stripIndent().trim()

    // ES2020 is required for dynamic import syntax; both languageIn and languageOut must be set.
    static final Map ES2020_OPTS = [languageMode: 'ES2020', targetLanguage: 'ES2020'].asImmutable()

    AssetCompiler compiler

    def setup() {
        compiler = new AssetCompiler([:])
    }

    void "minifies plain JS without dynamic imports by default"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        def input = "function add(a, b) { return a + b; }"
        when:
        def result = processor.process("test.js", input, [:])
        then:
        result.contains("function add")
        !result.contains("  ") // whitespace collapsed
    }

    void "dynamic import syntax fails by default (allowDynamicImport not set)"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        when:
        // The closure compiler rejects import() expressions unless allowDynamicImport is enabled.
        processor.process("test.js", DYNAMIC_IMPORT_VAR, ES2020_OPTS)
        then:
        def ex = thrown(MinifyException)
        ex.message.contains("JSC_DYNAMIC_IMPORT_USAGE")
    }

    void "allowDynamicImport: true without dynamicImportAlias is rejected early with a clear error"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        when:
        processor.process("test.js", DYNAMIC_IMPORT_VAR, ES2020_OPTS + [allowDynamicImport: true])
        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("dynamicImportAlias")
    }

    void "dynamicImportAlias alone (without allowDynamicImport) still rejects dynamic import syntax"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        when:
        // dynamicImportAlias has no effect unless allowDynamicImport is also true.
        processor.process("test.js", DYNAMIC_IMPORT_VAR, ES2020_OPTS + [dynamicImportAlias: "__import__"])
        then:
        def ex = thrown(MinifyException)
        ex.message.contains("JSC_DYNAMIC_IMPORT_USAGE")
    }

    void "allowDynamicImport + dynamicImportAlias rewrites import() call to the alias function"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        when:
        // __import__ is declared as a real function in the input so the alias resolves to an
        // existing symbol. The compiler rewrites every import(x) call to __import__(x).
        def result = processor.process("test.js", DYNAMIC_IMPORT_WITH_ALIAS_IMPL, ES2020_OPTS + [
                allowDynamicImport: true,
                dynamicImportAlias: "__import__"
        ])
        then:
        result.contains("__import__")
        !result.contains("import(")
    }

    void "allowDynamicImport + dynamicImportAlias with a literal specifier fails module resolution"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        when:
        // When a string literal is used the closure compiler attempts to load the referenced
        // module from the classpath before applying the alias rewrite, resulting in an error.
        processor.process("test.js", DYNAMIC_IMPORT_LIT, ES2020_OPTS + [
                allowDynamicImport: true,
                dynamicImportAlias: "__import__"
        ])
        then:
        def ex = thrown(MinifyException)
        ex.message.contains("JSC_JS_MODULE_LOAD_WARNING")
    }

    // ES2022 public class fields are common in modern production bundles
    // (marked's and Chart.js's UMD builds both use them). Before ES2022 was a
    // recognised languageMode the only ways to accept them were ECMASCRIPT_NEXT
    // and UNSTABLE, so apps excluded such files from minification entirely.
    static final String PUBLIC_CLASS_FIELD = "class TallyWidget { tally = 0; bump() { return ++this.tally; } }"

    void "minifies ES2022 public class fields with no languageMode configured"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        when:
        // Regression guard: the default used to be ECMASCRIPT_2020, which rejected this
        // outright and made every app exclude its vendor UMD bundles from minification.
        def result = processor.process("test.js", PUBLIC_CLASS_FIELD, [:])
        then:
        noExceptionThrown()
        result.contains("class")
    }

    void "explicit ES2020 still rejects ES2022 syntax"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        when:
        processor.process("test.js", PUBLIC_CLASS_FIELD, [languageMode: 'ES2020'])
        then:
        def ex = thrown(MinifyException)
        ex.message.contains("JSC_LANGUAGE_FEATURE")
    }

    void "minifies ES2022 public class fields when languageMode is #mode"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        when:
        def result = processor.process("test.js", PUBLIC_CLASS_FIELD, [languageMode: mode])
        then:
        noExceptionThrown()
        result.contains("class")
        where:
        mode << ['STABLE', 'UNSTABLE', 'ECMASCRIPT_NEXT']
    }

    // Raising the default languageIn only widens what can be PARSED — languageOut stays
    // NO_TRANSPILE — so emitted output, and therefore browser compatibility, must be
    // untouched for every source the previous ECMASCRIPT_2020 default already accepted.
    static final Map PRE_ES2022_SOURCES = [
        'es5'             : "function add(a,b){ return a+b; } var x = add(1,2);",
        'es6 class/arrow' : "class A { constructor(){ this.v=1; } } const f = (x) => x*2; let g = `t${1}`;",
        'es6 destructure' : "const {a,b} = obj; const [c,...d] = arr; function h(p=1,...r){ return p; }",
        'es2017 async'    : "async function go(){ const r = await fetch('/x'); return r.json(); }",
        'es2018 spread'   : "const merged = {...a, ...b}; async function w(it){ for await (const x of it) { use(x); } }",
        'es2020 optional' : "const v = a?.b?.c ?? 'default'; const p = obj?.fn?.();",
        'es2020 bigint'   : "const big = 9007199254740993n;",
    ].asImmutable()

    void "new default emits byte-identical output to the old ES2020 default: #name"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        expect:
        processor.process("test.js", src, [languageMode: 'ES2020']) == processor.process("test.js", src, [:])
        where:
        name << PRE_ES2022_SOURCES.keySet()
        src  << PRE_ES2022_SOURCES.values()
    }

    void "explicit targetLanguage still transpiles down to ES5"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        when:
        def result = processor.process("test.js", PRE_ES2022_SOURCES['es6 class/arrow'], [targetLanguage: 'ES5'])
        then:
        noExceptionThrown()
        !result.contains('=>')
        !result.contains('class ')
    }

    void "maps ES2015 and its ES6 alias"() {
        given:
        def processor = new ClosureCompilerProcessor(compiler)
        when:
        def result = processor.process("test.js", "const f = (a) => a * 2;", [languageMode: mode])
        then:
        noExceptionThrown()
        result
        where:
        mode << ['ES2015', 'ES6']
    }
}
