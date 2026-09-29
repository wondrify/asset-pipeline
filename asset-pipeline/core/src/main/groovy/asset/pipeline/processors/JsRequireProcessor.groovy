package asset.pipeline.processors

import asset.pipeline.AbstractProcessor
import asset.pipeline.AssetCompiler
import asset.pipeline.AssetFile
import asset.pipeline.GenericAssetFile
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.AssetHelper
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import asset.pipeline.CacheManager
import java.util.regex.Pattern
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
class JsRequireProcessor extends AbstractUrlRewritingProcessor {

	private static final Pattern URL_CALL_PATTERN = ~/[^\.a-zA-Z_\-0-9]require\((?:\s*)(['"]?)([a-zA-Z0-9\-_.:\/@#?$ &+%=]++)\1?(?:\s*)\)/
	public static ThreadLocal<Map<String,String>> commonJsModules = new ThreadLocal<Map<String,String>>()
	// Map<String,String> currentCommonJsModules
	public static ThreadLocal<String> baseModule = new ThreadLocal<String>()
	public static ThreadLocal<Boolean> withinDirectiveTree = new ThreadLocal<Boolean>()

	static {
        doNotInsertCacheDigestIntoUrlForCompiledExtension('html')
    }


	JsRequireProcessor(final AssetCompiler precompiler) {
		super(precompiler)
	}


	String process(final String inputText, final AssetFile assetFile) {
		Date now = new Date()
		if(AssetPipelineConfigHolder.config != null && AssetPipelineConfigHolder.config.commonJs == false) {
			return inputText
		}
		final Map<String, String> cachedPaths = [:]
		Boolean originator = false

		if(!baseModule.get() && !withinDirectiveTree.get()) {			
			commonJsModules.set([:] as Map<String,String>)
		}
		if(!baseModule.get()) {
			baseModule.set(assetFile.path)
			originator = true
		}
		try {
			String result =	inputText.replaceAll(URL_CALL_PATTERN) { final String urlCall, final String quote, final String assetPath ->
				final Boolean cacheFound = cachedPaths.containsKey(assetPath)
				final String cachedPath = cachedPaths[assetPath]
				Integer requirePosition = urlCall.indexOf('require')
				String resultPrefix = ''
				if(requirePosition > 0) {
					resultPrefix = urlCall.substring(0,requirePosition)
				}
				String replacementPath
				if (cacheFound) {
					if(cachedPath == null) {
						return resultPrefix+"require(${quote}${assetPath}${quote})"
					} else {
						return resultPrefix+"_asset_pipeline_require(${quote}${cachedPath}${quote})"
					}
				} else if(assetPath.size() > 0) {
					AssetFile currFile
					if(!assetPath.startsWith('/') && assetFile.parentPath != null) {
						def relativeFileName = [ assetFile.parentPath, assetPath ].join( AssetHelper.DIRECTIVE_FILE_SEPARATOR )
						relativeFileName = AssetHelper.normalizePath(relativeFileName)
						currFile = AssetHelper.fileForUri(relativeFileName,'application/javascript')

					}
					
					if(!currFile) {
						currFile = AssetHelper.fileForUri(assetPath,'application/javascript')
					}
					
					if(!currFile) {
						currFile = AssetHelper.fileForUri(assetPath + '/' + assetPath,'application/javascript')
					}

					// look for a node module
					if(!currFile){
						if(!assetPath.startsWith('/')) {
							def packageFileName = [  assetPath, 'package.json' ].join( AssetHelper.DIRECTIVE_FILE_SEPARATOR )
							packageFileName = AssetHelper.normalizePath(packageFileName)
							AssetFile packageJsonFile = AssetHelper.fileForUri(packageFileName)
							if (packageJsonFile) {
								JsonSlurper slurper = new JsonSlurper()
								def packageJson = slurper.parse(packageJsonFile.getInputStream()) as Map
								def realAssetFileName = [assetPath, packageJson.get("main")].join(AssetHelper.DIRECTIVE_FILE_SEPARATOR)
								realAssetFileName = AssetHelper.normalizePath(realAssetFileName)
								currFile = AssetHelper.fileForUri(realAssetFileName, 'application/javascript')
							}
						}
					}
					
					//look for index.js
					if(!currFile) {
						if(!assetPath.startsWith('/') && assetFile.parentPath != null) {
							def relativeFileName = [ assetFile.parentPath, assetPath, 'index.js' ].join( AssetHelper.DIRECTIVE_FILE_SEPARATOR )	
							relativeFileName = AssetHelper.normalizePath(relativeFileName)
							currFile = AssetHelper.fileForUri(relativeFileName,'application/javascript')
						}
						
						if(!currFile) {
							currFile = AssetHelper.fileForUri(assetPath + '/index.js','application/javascript')
						}
						
						if(!currFile) {
							currFile = AssetHelper.fileForUri(assetPath + '/' + assetPath.tokenize('/')[-1] + '/index.js','application/javascript')
						}
					}

					

					//look for non js file
					if(!currFile) {
						if(!assetPath.startsWith('/')) {
							def relativeFileName = [ assetFile.parentPath, assetPath].join( AssetHelper.DIRECTIVE_FILE_SEPARATOR )	
							relativeFileName = AssetHelper.normalizePath(relativeFileName)

							currFile = AssetHelper.fileForUri(relativeFileName)
						}
						
						if(!currFile) {
							currFile = AssetHelper.fileForUri(assetPath)
						}
						
						if(!currFile) {
							currFile = AssetHelper.fileForUri(assetPath + '/' + assetPath.tokenize('/')[-1])
						}

					}
					if(!currFile) {
						cachedPaths[assetPath] = null as String
						return resultPrefix+"require(${quote}${assetPath}${quote})"
					} else if(currFile instanceof GenericAssetFile) {

						appendUrlModule(currFile as AssetFile,replacementAssetPath(assetFile, currFile as AssetFile))
						
						cachedPaths[assetPath] = currFile.path
						return resultPrefix+"_asset_pipeline_require(${quote}${currFile.path}${quote})"
					} else {
						currFile.baseFile = assetFile.baseFile ?: assetFile
						appendModule(currFile)
						cachedPaths[assetPath] = currFile.path
						return resultPrefix+"_asset_pipeline_require(${quote}${currFile.path}${quote})"
					}
				} else {
					return resultPrefix+"require(${quote}${assetPath}${quote})"
				}
			}


			if(baseModule.get() == assetFile.path && commonJsModules.get() && !withinDirectiveTree.get()) {
				result = requireMethod + modulesJs() + result
			}
			return result
		} finally {
			// log.info("Processed JsRequires for ${assetFile.name} in ${new Date().time - now.time}ms")
			if(originator) {
				if(!withinDirectiveTree.get()) {
					commonJsModules.set([:] as Map<String,String>)	
				}
				baseModule.set(null)
			}
		}
	}

	private appendModule(AssetFile assetFile) {
		Map<String,String> moduleMap = commonJsModules.get()
		if(!moduleMap) {
			moduleMap = [:] as Map<String,String>
			commonJsModules.set(moduleMap)
		}
		CacheManager.addCacheDependency(baseModule.get(), assetFile)
		if(moduleMap[assetFile.path]) {
			CacheManager.addCacheModule(baseModule.get(),assetFile.path,moduleMap[assetFile.path])
			return
		}
		//this is here to prevent circular dependencies
		String placeHolderModule = """
		(function() {
		  var module = {exports: {}};
		  var exports = module.exports;
		  return module;
		})
		"""
		moduleMap[assetFile.path] = placeHolderModule
		moduleMap[assetFile.path] = encapsulateModule(assetFile)
		CacheManager.addCacheModule(baseModule.get(),assetFile.path,moduleMap[assetFile.path])
		
	}

	private appendUrlModule(AssetFile assetFile, String url) {
		Map<String,String> moduleMap = commonJsModules.get()
		if(!moduleMap) {
			moduleMap = [:] as Map<String,String>
			commonJsModules.set(moduleMap)
		}

		if(moduleMap[assetFile.path]) {
			CacheManager.addCacheModule(baseModule.get(),assetFile.path,moduleMap[assetFile.path])
			return
		}
		//this is here to prevent circular dependencies
		String placeHolderModule = """
		(function() {
		  var module = {exports: "${url}"};
		  return module;
		})
		"""
		moduleMap[assetFile.path] = placeHolderModule
		CacheManager.addCacheModule(baseModule.get(),assetFile.path,moduleMap[assetFile.path])
		
	}



	private String encapsulateModule(AssetFile assetFile) {
		String encapsulation = """
(function() {
  var module = {exports: {}};
  var exports = module.exports;

  ${assetFile.processedStream(precompiler,true)}

  return module;
})
"""

		return encapsulation
	}



	public static String modulesJs() {
		String output = "var _asset_pipeline_modules = _asset_pipeline_modules || {};\n"
		output += commonJsModules.get()?.collect { path, encapsulation ->
			"_asset_pipeline_modules['${path}'] = ${encapsulation};"
		}.join('\n')

		return output
	}



	static final String requireMethod = """
var _asset_pipeline_loaded_modules = _asset_pipeline_loaded_modules || {};
var _asset_pipeline_require = function(path) {
	var loadedModule = _asset_pipeline_loaded_modules[path];
	if(loadedModule != undefined) {
		return loadedModule.exports;
	}
	var module = _asset_pipeline_modules[path];
	if(module != undefined) {
		_asset_pipeline_loaded_modules[path] = module();
		return _asset_pipeline_loaded_modules[path].exports;
	}
	return null;
};

"""
}
