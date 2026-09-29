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
import com.google.javascript.jscomp.*
import com.google.javascript.jscomp.CompilerOptions.LanguageMode
import groovy.transform.CompileStatic

import java.util.logging.Level

/**
 * A PostProcessor designed to minify javascript utilizing the Google Closure javascript compiler
 * Under the hood this leverages Rhino AST Trees to process the file (but not the Rhino runtime) which makes this
 * significantly faster than the UglifyJs or UglifyJs2 library.
 *
 * @author David Estes
 */
@CompileStatic
class ClosureCompilerProcessor {
	static contentTypes = ['application/javascript']

	AssetCompiler assetCompiler
	ClosureCompilerProcessor(AssetCompiler compiler) {
		this.assetCompiler = compiler
		com.google.javascript.jscomp.Compiler.setLoggingLevel(Level.INFO);
	}


	public String process(String fileName, String inputText, Map minifyOptions = [:]) {
		def compiler = new Compiler()
		CompilerOptions options = new CompilerOptions()
		options.trustedStrings = true
		options.setEmitUseStrict(false)

		translateMinifyOptions(options,minifyOptions)

		if(assetCompiler.options.enableSourceMaps) {
			setSourceMapOptions(options,minifyOptions, fileName)
		}
		java.util.logging.Logger.getLogger(com.google.javascript.jscomp.PhaseOptimizer.class.getName()).setLevel(java.util.logging.Level.SEVERE);
        String baseFileName = new File(fileName).name
		WarningLevel.QUIET.setOptionsForWarningLevel(options);
		SourceFile sourceFile = SourceFile.fromCode(baseFileName + ".unminified.js", inputText)
		// def sourceFile = new SourceFile.Preloaded(fileName + ".unminified.js",fileName, inputText)
		// sourceFile.setCode(inputText)
		def result = compiler.compile(CommandLineRunner.getBuiltinExterns(CompilerOptions.Environment.BROWSER),[sourceFile] as List<SourceFile>,options)
		def output
		if(result.success) {
			output = compiler.toSource()
			if(compiler.sourceMap) {
				File mapFile = new File(assetCompiler.options.compileDir as String,fileName + ".js.map")

				if(!mapFile.exists()) {
					mapFile.parentFile.mkdirs()
				}
				File unminifiedFile = new File(assetCompiler.options.compileDir as String,fileName + ".unminified.js")
				unminifiedFile.text = inputText
				mapFile.createNewFile()
				FileWriter outputWriter = new FileWriter(mapFile)
				compiler.sourceMap.setWrapperPrefix("//# sourceMappingURL=${baseFileName + '.js.map'}\n")
				compiler.sourceMap.appendTo(outputWriter,baseFileName + ".js")
				outputWriter.close();
				output = "//# sourceMappingURL=${baseFileName + '.js.map'}\n" + output
			}
		} else {
			throw new MinifyException(result.errors.toString())
		}
		
		
		if(!output) {
			return inputText
		}
		return output
	}

	public void translateMinifyOptions(CompilerOptions compilerOptions, Map minifyOptions) {
		def defaultOptions = [
			// languageMode: 'ECMASCRIPT_NEXT',
			optimizationLevel: 'SIMPLE' //WHITESPACE , ADVANCED
		]

		minifyOptions = defaultOptions + minifyOptions
		LanguageMode languageIn = evaluateLanguageMode(minifyOptions.get('languageMode') as String)
		if(minifyOptions.targetLanguage) {
			LanguageMode languageOut = evaluateLanguageMode(minifyOptions.get('targetLanguage') as String)
			compilerOptions.setLanguageIn(languageIn)
			compilerOptions.setLanguageOut(languageOut)
		} else {
			compilerOptions.setLanguageIn(languageIn)
			compilerOptions.setLanguageOut(LanguageMode.NO_TRANSPILE)
		}
		setCompilationLevelOptions(compilerOptions, minifyOptions.get('optimizationLevel') as String)
		if (minifyOptions.angularPass) {
			compilerOptions.setAngularPass(true)
		}
		if (minifyOptions.containsKey('warningLevel')) {
			final Map<String, String> warningLevel = minifyOptions.get('warningLevel') as Map<String, String>
			if (warningLevel) {
				for(Map.Entry<String, String> entry: warningLevel.entrySet()) {
					final DiagnosticGroup diagnosticGroup = DiagnosticGroups.forName(entry.getKey())
					final CheckLevel checkLevel = CheckLevel.valueOf(entry.getValue())
					if (diagnosticGroup && checkLevel) {
						compilerOptions.setWarningLevel(diagnosticGroup, checkLevel)
					}
				}
			}
		}
		if(minifyOptions.containsKey('allowDynamicImport')) {
			boolean allowDynamicImport = minifyOptions.get('allowDynamicImport') as boolean
			if(allowDynamicImport && !minifyOptions.containsKey('dynamicImportAlias')) {
				throw new IllegalArgumentException(
					"minifyOptions.allowDynamicImport requires minifyOptions.dynamicImportAlias to also be set. " +
					"The closure compiler cannot emit dynamic import expressions without an alias function to rewrite them to."
				)
			}
			compilerOptions.allowDynamicImport = allowDynamicImport
		}

		if(minifyOptions.containsKey('dynamicImportAlias')) {
			compilerOptions.dynamicImportAlias = minifyOptions.get('dynamicImportAlias') as String
		}
	}


	private LanguageMode evaluateLanguageMode(String mode) {
		switch(mode?.toUpperCase()) {
			case 'ECMASCRIPT_NEXT':
				return LanguageMode.ECMASCRIPT_NEXT
			case 'ES5_SCRIPT':
				return LanguageMode.ECMASCRIPT5_STRICT
			case 'ES5':
				return LanguageMode.ECMASCRIPT5
			case 'ES3':
				return LanguageMode.ECMASCRIPT3
			case 'ES2015':
			case 'ES6':
				return LanguageMode.ECMASCRIPT_2015
			case 'ES2016':
				return LanguageMode.ECMASCRIPT_2016
			case 'ES2017':
				return LanguageMode.ECMASCRIPT_2017
			case 'ES2018':
				return LanguageMode.ECMASCRIPT_2018	
			case 'ES2019':
				return LanguageMode.ECMASCRIPT_2019
			case 'ES2020':
				return LanguageMode.ECMASCRIPT_2020	
			case 'ES2021':
				return LanguageMode.ECMASCRIPT_2021
			case 'STABLE':
				return LanguageMode.STABLE
			case 'UNSTABLE':
				return LanguageMode.UNSTABLE
			default:
				// STABLE tracks the newest language level this compiler fully supports.
				// The old ECMASCRIPT_2020 default rejected syntax that has shipped in
				// every browser for years — notably ES2022 public class fields, used by
				// the stock UMD bundles of Chart.js, marked and friends. That forced
				// every consuming app to keep a growing minifyOptions.excludes list just
				// to get its vendor bundles through the pipeline. Parsing newer syntax is
				// safe here: languageOut defaults to NO_TRANSPILE, so nothing that
				// compiled before compiles differently now.
				return LanguageMode.STABLE
		}
	}

	private void setCompilationLevelOptions(CompilerOptions options ,String mode) {
		switch(mode?.toUpperCase()) {
			case 'WHITESPACE':
			case 'WHITESPACE_ONLY':
				CompilationLevel.WHITESPACE_ONLY.setOptionsForCompilationLevel(options);
				break;
			case 'ADVANCED':
				CompilationLevel.ADVANCED_OPTIMIZATIONS.setOptionsForCompilationLevel(options);
				break;
			case 'SIMPLE':
			default:
				CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options);
		}
	}

	private void setSourceMapOptions(CompilerOptions compilerOptions, Map minifyOptions, String filename) {
		compilerOptions.sourceMapDetailLevel = SourceMap.DetailLevel.ALL;
		compilerOptions.sourceMapFormat = SourceMap.Format.DEFAULT;
		//compilerOptions.sourceMapLocationMappings =
		compilerOptions.sourceMapOutputPath = "${filename}.js.map"
	}



}
