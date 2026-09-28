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

import asset.pipeline.AssetFile
import asset.pipeline.AbstractProcessor
import asset.pipeline.AssetCompiler
import asset.pipeline.JsAssetFile
import asset.pipeline.AssetPipelineConfigHolder
import groovy.util.logging.Slf4j

import javax.script.Invocable
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.script.SimpleBindings
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import groovy.transform.CompileStatic

// CoffeeScript engine will attempt to use Node.JS coffee if it is available on
// the system path. If not, it uses Mozilla Rhino to compile the CoffeeScript
// template using the javascript in-browser compiler.
@Slf4j
@CompileStatic
class BabelJsProcessor extends AbstractProcessor {

	static Boolean SWC_SUPPORTED
	static Boolean BABEL_NATIVE_SUPPORTED
	ClassLoader classLoader

	static Context context
	static Value bindings
	private static final $LOCK = new Object[0]
	BabelJsProcessor(AssetCompiler precompiler) {
		super(precompiler)
	}

	protected void loadBabelJs() {
		if(!context) {
			synchronized($LOCK) {
				if(!context) {
					def babelJsResource = classLoader.getResource('asset/pipeline/babel.min.js')
					context = Context.newBuilder().allowExperimentalOptions(true).allowHostAccess(HostAccess.newBuilder().allowListAccess(true).allowMapAccess(true).allowArrayAccess(true).build()).build()

					context.eval("js",babelJsResource.getText('UTF-8'))
					def presets = "{ \"presets\": [\"es2015\",[\"stage-2\",{\"decoratorsLegacy\": true}],\"react\"], \"compact\": false }"
					String options = getOptions()
					if(options) {
						presets = options
					}
					bindings = context.getBindings("js")
					bindings.putMember("optionsJson", presets);
					context.eval("js","var options = JSON.parse(optionsJson);");

				}
			}
		}

	}


	/**
	* Processes an input string from a given AssetFile implementation of coffeescript and converts it to javascript
	* @param   input String input coffee script text to be converted to javascript
	* @param   AssetFile instance of the asset file from which this file came from. Not actually used currently for this implementation.
	* @return  String of compiled javascript
	*/
	String process(String input,AssetFile  assetFile) {
		if(!input) {
			return input
		}
		Boolean newEcmascriptKeywordsFound = false
		if(input.contains("export default")) {
			newEcmascriptKeywordsFound = true;
		}
		if(assetFile instanceof JsAssetFile) {
			if((!newEcmascriptKeywordsFound && !AssetPipelineConfigHolder.config?.enableES6 && !AssetPipelineConfigHolder.config?."enable-es6") || (newEcmascriptKeywordsFound && (AssetPipelineConfigHolder.config?.enableES6 == false || AssetPipelineConfigHolder.config?."enable-es6" == false))) {
				return input
			}
		}

		Boolean processed = false
		String result = null
		if(isSwcSupported()) {
			try {
				result = processWithSwcBinary(input, assetFile)
				processed = true	
			} catch(ex) {
				log.error("Error Processing from SWC...continuing with fallback option ", {})
			}
			
		}
		if(!processed && isBabelSupported()) {
			try {
				result = processWithBabelBinary(input, assetFile)
				processed = true	
			} catch(ex) {
				log.error("Error Processing from SWC...continuing with fallback option ", {})
			}
		}
		
		if(!processed) {
			try {
				classLoader = getClass().getClassLoader()
				//cx.setOptimizationLevel(-1)
				//globalScope = cx.initStandardObjects()
				loadBabelJs()
			} catch(Exception e) {
				throw new Exception("BabelJs Engine initialization failed.", e)
			} finally {
				try {
				} catch(IllegalStateException e) {
				}
			}
			try {
				

				synchronized($LOCK) {
					bindings.putMember("input", input);
					result = context.eval("js","Babel.transform(input, options).code") as String;
					
				}
			} catch(Exception e) {
				throw new Exception("""BabelJs Engine compilation of javascript failed.
				$e
				""",e)
			} finally {
			}
		}
		return result
		
		
	}


	protected String getOptions() {
		Map<String,Object> babelOptions = AssetPipelineConfigHolder.config?.babel as Map<String,Object>
		if(babelOptions) {
			return babelOptions.options as String
		} else {
			return null
		}
	}

	/**
	* Processes an input string of ecmascript 6+ using swc on node.js (Don't use directly)
	* @param   input String input ecmascript script text to be converted to ecmascript5
	* @param   AssetFile instance of the asset file from which this file came from. Not actually used currently for this implementation.
	* @return  String of compiled javascript
	 */
	def processWithBabelBinary(String input, AssetFile assetFile) {
		def nodeProcess
		def output = new StringBuilder()
		def err = new StringBuilder()
		def globalLocation = AssetPipelineConfigHolder.config?.globalModules

		try {
			if (!globalLocation) {
				synchronized($LOCK) {
					if (!globalLocation) {
						def npmProc
						def globalLibLoc = new StringBuilder()
						def npmCmd = "${ isWindows() ? 'cmd /c ' : '' }npm get prefix"
						npmProc = npmCmd.execute()
						npmProc.waitForProcessOutput(globalLibLoc, err)
						globalLocation = "${globalLibLoc.toString().trim()}/lib/node_modules/"	
					}
				}
				
			}
			def presets = "--presets=${globalLocation}@babel/preset-env"
			def command = "${ isWindows() ? 'cmd /c ' : '' }babel --no-babelrc ${presets}"
			nodeProcess = command.execute()
			nodeProcess.getOutputStream().write(input.bytes)
			nodeProcess.getOutputStream().flush()
			nodeProcess.getOutputStream().close()
			nodeProcess.waitForProcessOutput(output, err)
			if(err) {
				throw new Exception(err.toString())
			}
			return output.toString()
		} catch(Exception e) {
			throw new Exception("""
			Babel Engine compilation of es6 to es5 failed for ${assetFile.name}.
			$e
			""")
		}
	}

	/**
	* Processes an input string of ecmascript 6+ using swc on node.js (Don't use directly)
	* @param   input String input ecmascript script text to be converted to ecmascript5
	* @param   AssetFile instance of the asset file from which this file came from. Not actually used currently for this implementation.
	* @return  String of compiled javascript
	 */
	def processWithSwcBinary(String input, AssetFile assetFile) {
		def nodeProcess
		def output = new StringBuilder()
		def err = new StringBuilder()
		def swcrcLocation = AssetPipelineConfigHolder.config?.swcrc

		try {
			def config = "-C module.type=commonjs -C module.strict=true -C module.noInterop=true --env-name='production'"
			if (swcrcLocation) {
				config += " --config-file ${swcrcLocation}" 
			}
			def command = "${ isWindows() ? 'cmd /c ' : '' }swc --no-swcrc ${config}"
			nodeProcess = command.execute()
			nodeProcess.getOutputStream().write(input.bytes)
			nodeProcess.getOutputStream().flush()
			nodeProcess.getOutputStream().close()
			nodeProcess.waitForProcessOutput(output, err)
			if(err) {
				throw new Exception(err.toString())
			}
			return output.toString()
		} catch(Exception e) {
			throw new Exception("""
			SWC Engine compilation of es6 to es5 failed for ${assetFile.name}.
			$e
			""")
		}
	}

	/**
	 * Determins if NODE is supported on the System path
	 * @return Boolean true if NODE.js is supported on the system path
	 */
	Boolean isSwcSupported() {
		if(SWC_SUPPORTED == null) {
			def nodeProcess
			def output = new StringBuilder()
			def err = new StringBuilder()

			try {
				def command = "${ isWindows() ? 'cmd /c ' : '' }swc -V"
				nodeProcess = command.execute()
				nodeProcess.waitForProcessOutput(output, err)
				if(err) {
					SWC_SUPPORTED = false
				}
				else {
					SWC_SUPPORTED = true
				}
			} catch(Exception e) {
				SWC_SUPPORTED = false
			}
		}
			return SWC_SUPPORTED
	}

	/**
	 * Determins if NODE is supported on the System path
	 * @return Boolean true if NODE.js is supported on the system path
	 */
	Boolean isBabelSupported() {
		if(BABEL_NATIVE_SUPPORTED == null) {
			def nodeProcess
			def output = new StringBuilder()
			def err = new StringBuilder()

			try {
				def command = "${ isWindows() ? 'cmd /c ' : '' }babel -V"
				nodeProcess = command.execute()
				nodeProcess.waitForProcessOutput(output, err)
				if(err) {
					BABEL_NATIVE_SUPPORTED = false
				}
				else {
					BABEL_NATIVE_SUPPORTED = true
				}
			} catch(Exception e) {
				BABEL_NATIVE_SUPPORTED = false
			}
		}
			return BABEL_NATIVE_SUPPORTED
	}

	/**
	 * Determins if this is on a windows platform or not (used for node system path)
	 * @return Boolean true if this is a windows machine
	 */
	Boolean isWindows() {
		String osName = System.getProperty("os.name");
		return (osName != null && osName.contains("Windows"))
	}

}
