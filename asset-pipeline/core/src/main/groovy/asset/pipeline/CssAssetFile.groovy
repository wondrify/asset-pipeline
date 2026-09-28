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

import asset.pipeline.processors.CssProcessor
import java.util.regex.Pattern
import groovy.transform.CompileStatic
/**
 * An {@link AssetFile} implementation for CSS
 * This currently applies a {@link asset.pipeline.processors.CssProcessor} onto the css that does relative url replacement with  digest named files
 *
 * @author David Estes
 * @author Graeme Rocher
 */
@CompileStatic
class CssAssetFile extends AbstractAssetFile {
	static final List<String> contentType = ['text/css']
	static List<String> extensions = ['css']
	static String compiledExtension = 'css'
	static List<Class<Processor>> processors = [CssProcessor] as List<Class<Processor>>
	Pattern directivePattern = ~/(?m)\*=(.*)/

}
