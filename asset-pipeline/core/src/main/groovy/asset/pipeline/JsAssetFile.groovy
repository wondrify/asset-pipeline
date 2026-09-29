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

package asset.pipeline

import asset.pipeline.processors.JsRequireProcessor

import java.util.regex.Pattern
import asset.pipeline.processors.JsProcessor
import asset.pipeline.processors.JsNodeInjectProcessor
import asset.pipeline.processors.BabelJsProcessor
import groovy.transform.CompileStatic
/**
 * An {@link AssetFile} implementation for Javascript
 *
 * @author David Estes
 * @author Graeme Rocher
 */
@CompileStatic
class JsAssetFile extends AbstractAssetFile {
    static final List<String> contentType = ['application/javascript', 'application/x-javascript','text/javascript']
    static List<String> extensions = ['js','cjs']
    static String compiledExtension = 'js'
    static processors = [JsProcessor,JsNodeInjectProcessor,BabelJsProcessor,JsRequireProcessor]
    Pattern directivePattern = ~/(?m)^\/\/=(.*)/

}
