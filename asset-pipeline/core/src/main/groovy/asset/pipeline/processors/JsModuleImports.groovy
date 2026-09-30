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

import groovy.transform.CompileStatic
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value

/**
 * Finds actual ES module specifiers without executing the input or regenerating its JavaScript.
 * The same lexical analysis drives URL rewriting and the dependency graph, so comments, strings,
 * regular expressions and computed imports cannot accidentally add dependency edges.
 */
@CompileStatic
class JsModuleImports {
    private static final Object LOCK = new Object()
    private static Context context
    private static Value parse

    static List<Specifier> find(String input, String path) {
        if (!input.contains('import') && !input.contains('export')) {
            return []
        }
        // Graal contexts and the lexer's working buffer are not thread safe. Copy out Java values
        // under the lock, then release it before callers recursively compile any dependencies.
        synchronized (LOCK) {
            if (parse == null) {
                initialize()
            }
            try {
                Value imports = parse.execute(input, path).getArrayElement(0)
                List<Specifier> result = []
                for (long i = 0; i < imports.arraySize; i++) {
                    Value entry = imports.getArrayElement(i)
                    Value name = entry.getMember('n')
                    int dynamic = entry.getMember('d').asInt()
                    if (name.isNull() || dynamic == -2) {
                        continue // computed import() or import.meta
                    }
                    int start = entry.getMember('s').asInt()
                    int end = entry.getMember('e').asInt()
                    // Static offsets exclude quotes; dynamic offsets include the whole expression.
                    if (dynamic == -1) {
                        start--
                        end++
                    }
                    char quote = input.charAt(start)
                    if (quote != ('\'' as char) && quote != ('"' as char)) {
                        continue // only string literals, not template/computed expressions
                    }
                    result.add(new Specifier(name.asString(), start, end, quote))
                }
                return result
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot analyze ES module imports in ${path}", e)
            }
        }
    }

    private static void initialize() {
        Context candidate = Context.newBuilder('js')
            .allowExperimentalOptions(true)
            .option('js.esm-eval-returns-exports', 'true')
            .build()
        try {
            URL resource = JsModuleImports.getResource('/asset/pipeline/es-module-lexer/lexer.minimal.asm.js')
            Source source = Source.newBuilder('js', resource)
                .mimeType('application/javascript+module').build()
            Value exports = candidate.eval(source)
            parse = exports.getMember('parse')
            context = candidate // kept with its function for this classloader's lifetime, like Babel
        } catch (Exception e) {
            candidate.close()
            throw new IllegalStateException('Cannot initialize the ES module lexer; asset compilation requires GraalJS', e)
        }
    }

    @CompileStatic
    static class Specifier {
        final String name
        final int start
        final int end
        final char quote

        Specifier(String name, int start, int end, char quote) {
            this.name = name
            this.start = start
            this.end = end
            this.quote = quote
        }

        boolean isRelative() {
            name.startsWith('./') || name.startsWith('../')
        }

        String quoted(String replacement) {
            String delimiter = String.valueOf(quote)
            delimiter + replacement.replace('\\', '\\\\').replace(delimiter, '\\' + delimiter)
                .replace('\r', '\\r').replace('\n', '\\n').replace('\t', '\\t')
                .replace('\u2028', '\\u2028').replace('\u2029', '\\u2029') + delimiter
        }
    }
}
