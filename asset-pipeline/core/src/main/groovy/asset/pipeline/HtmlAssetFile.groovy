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
import java.util.regex.Pattern
import asset.pipeline.processors.HtmlProcessor
import groovy.transform.CompileStatic

/**
 * An {@link AssetFile} implementation for Html
 * This currently applies a {@link asset.pipeline.processors.HtmlProcessor} onto the Html that does relative url replacement with  digest named files
 *
 * @author David Estes
 * @author Graeme Rocher
 */
@CompileStatic
class HtmlAssetFile extends AbstractAssetFile {
	static List<Class<Processor>> processors = [HtmlProcessor] as List<Class<Processor>>
    static final List<String> contentType = ['text/html']
    static List<String> extensions = ['html']
    static String compiledExtension = 'html'
    public Pattern directivePattern = null
    
}
