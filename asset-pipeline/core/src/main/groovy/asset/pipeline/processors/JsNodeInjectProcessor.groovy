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

import asset.pipeline.AbstractProcessor
import asset.pipeline.AssetCompiler
import asset.pipeline.AssetFile
import asset.pipeline.AssetPipelineConfigHolder

/**
 * This Processor iterates over a js file looking for asset_path directive sand
 * replaces their path with absolute paths based on the configured.
 * In precompiler mode the URLs are also cache digested.
 *
 * @author David Estes
 */
class JsNodeInjectProcessor extends AbstractProcessor  {


	JsNodeInjectProcessor(final AssetCompiler precompiler) {
		super(precompiler)
	}


	String process(final String inputText, final AssetFile assetFile) {
		String nodeEnv = 'development'

		final Object configuredEnv = AssetPipelineConfigHolder.config?.get('nodeEnv')

		// `nodeEnv: false` opts out of the shim entirely. It exists so bundles that read
		// process.env.NODE_ENV do not blow up in the browser, but it is prepended to every
		// JS asset whether or not anything reads it — dead bytes for apps whose bundles
		// never mention `process`. Set it the same way as `commonJs`: `configOptions` in
		// the gradle `assets` block, or `grails.assets` config in a Grails application.
		if (configuredEnv instanceof Boolean && !configuredEnv) {
			return inputText
		}

		if (configuredEnv != null) {
			nodeEnv = configuredEnv
		}

		// if(!assetFile.baseFile) {
			return "var process = process || {env: {NODE_ENV: \"$nodeEnv\"}};\n${inputText}"	
		// } else {
		// 	return inputText
		// }		
	}
}
