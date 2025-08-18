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

package asset.pipeline.coffee

import asset.pipeline.AssetFile
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import asset.pipeline.AbstractProcessor
import asset.pipeline.AssetCompiler

// CoffeeScript engine will attempt to use Node.JS coffee if it is available on
// the system path. If not, it uses Mozilla Rhino to compile the CoffeeScript
// template using the javascript in-browser compiler.
class CoffeeScriptProcessor extends AbstractProcessor {

	static Boolean NODE_SUPPORTED
	Scriptable globalScope
	ClassLoader classLoader

	static org.mozilla.javascript.Script compilerScript
	static org.mozilla.javascript.Script processScript
	private static final $LOCK = new Object[0]
	CoffeeScriptProcessor(AssetCompiler precompiler) {
		super(precompiler)
		
			try {
				classLoader = getClass().getClassLoader()
				Context cx = Context.enter()
				cx.setOptimizationLevel(-1)
				globalScope = cx.initStandardObjects()
				loadCoffee(cx)
			} catch(Exception e) {
				throw new Exception("CoffeeScript Engine initialization failed.", e)
			} finally {
				try {
					Context.exit()
				} catch(IllegalStateException e) {
				}
			}
		
	}

	protected void loadCoffee(Context cx) {
		if(!compilerScript) {
			synchronized($LOCK) {
				if(!compilerScript) {
					def coffeeScriptJsResource = classLoader.getResource('asset/pipeline/coffee/coffee-script-2.7.0.js')
					compilerScript = cx.compileString(coffeeScriptJsResource.getText('UTF-8'),coffeeScriptJsResource.file,1,null)
				}
			}
		}
		compilerScript.exec(cx,globalScope)

		if(!processScript) {
			synchronized($LOCK) {
				if(!processScript) {
					processScript = cx.compileString("CoffeeScript.compile(coffeeScriptSrc)", "CoffeeScript compile command", 0, null)
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
	
		try {
			def cx = Context.enter()
			def compileScope = cx.newObject(globalScope)
			compileScope.setParentScope(globalScope)
			compileScope.put("coffeeScriptSrc", compileScope, input)
			def result = processScript.exec(cx,compileScope)
			return result
		} catch(Exception e) {
			throw new Exception("""
			CoffeeScript Engine compilation of coffeescript to javascript failed.
			$e
			""")
		} finally {
			Context.exit()
		}
	
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
