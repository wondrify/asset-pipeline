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

import asset.pipeline.processors.ClosureCompilerProcessor
import asset.pipeline.utils.MultiOutputStream
import asset.pipeline.processors.CssMinifyPostProcessor
import asset.pipeline.fs.JarAssetResolver
import asset.pipeline.fs.FileSystemAssetResolver
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.zip.GZIPOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.CompletionService

/**
 * Build time compiler for assets. This does a differential comparison of the source directory
 * and the destination directory currently utilizing the manifest.properties file. This is primarily used
 * during compilation. The gradle plugin uses this class to compile assets as does the grails gant plugin.
 *
 * @author David Estes
 * @author Graeme Rocher
 */
@Slf4j
public class AssetCompiler {
	def includeRules = [:]
	def excludeRules = [:]

	Map options = [:]
	def eventListener
	def filesToProcess = []
	Properties manifestProperties
	def threadPool

	/**
	 * Creates an instance of the compiler given passed input options
	 * @param options A Map of options that can be passed to the library
	 * <ul>
	 *  <li>compileDir - String Location of where assets should be compiled into</li>
	 *  <li>excludesGzip - List of extensions of files that should be excluded from gzip compression. (Most image types included by default)</li>
	 *  <li>enableGzip - Whether or not we should generate gzip files (default true)</li>
	 *  <li>enableDigests - Turns on generation of digest named assets (default true)</li>
	 *  <li>skipNonDigests - If turned on will not generate non digest named files (default false)</li>
	 *  <li>maxThreads - Compiler can concurrently compile assets now and defaults to a max thread count of 4</li>
	 * </ul>
	 * @param eventListener
	 */
	AssetCompiler(options = [:], eventListener = null) {
		this.eventListener = eventListener
		this.options = options
		if(!options.compileDir) {
			options.compileDir = "target/assets"
		}
		if(!options.excludesGzip) {
			options.excludesGzip = ['png', 'jpg', 'jpeg', 'gif', 'zip', 'gz']
		} else {
			options.excludesGzip += ['png', 'jpg', 'jpeg', 'gif', 'zip', 'gz']
		}

		if(!options.containsKey('enableGzip')) {
			options.enableGzip = true
		}

		if(!options.containsKey('enableDigests')) {
			options.enableDigests = true
		}
		if(!options.containsKey('skipNonDigests')) {
			options.skipNonDigests = false
		}

		// Load in additional assetSpecs
		options.specs?.each { spec ->
			def specClass = this.class.classLoader.loadClass(spec)
			if(specClass) {
				AssetHelper.assetSpecs << (Class<AssetFile>) specClass
			}
		}
		manifestProperties = new Properties()

	}

	/**
	 * Main Target Endpoint for Launching The AssetCompile in a Forked Execution Environment
	 * Arguments
	 * <ul>
	 * <li>-o compileDir</li>
	 * <li>-i sourceDir (List of SourceDirs pass multiple for more than one with -i)</li>
	 * <li>-j List of Source Jars (pass multiple -j arguments for more than one)</li>
	 * <li>-d Digests</li>
	 * <li>-z Compression</li>
	 * <li>-m SourceMaps</li>
	 * <li>-n Skip Non Digests</li>
	 * <li>-J Additional Compiler options input as JSON (e.g. -J '{"minifyJs":true, "minifyCss":true, "minifyOptions":{"excludes":["*.js"]}}')</li>

	 * </ul>
	 *
	 */
	static void main(String[] args) {

		Boolean sourceSpecified = false;
		Boolean flattenResolvers = false
		// def properties = System.getProperties()
		Map compilerArgs = [
						compileDir      : null,
						enableDigests   : false,
						enableGzip      : false,
						enableSourceMaps: false,
						excludesGzip    : [],
						maxThreads      : null,
						minifyCss       : false,
						minifyJs        : false,
						minifyOptions   : null,
						skipNonDigests  : false,
		]

		for(int x = 0 ; x < args.length; x++) {
			def arg = args[x]
			if(arg.startsWith('-')) {
				def option = arg.substring(1)
				switch(option) {
					case 'o':
						compilerArgs.compileDir = args[++x]
						break
					case 'i':
						if(!sourceSpecified) {
							def mainFileResolver = new FileSystemAssetResolver('application', args[++x])
							AssetPipelineConfigHolder.registerResolver(mainFileResolver)
							sourceSpecified = true
						} else {
							String path = args[++x]
							def fileResolver = new FileSystemAssetResolver(path, path)
							AssetPipelineConfigHolder.registerResolver(fileResolver)
						}
						break
					case 'j':
						registerJarResolvers(new File(args[++x]))

						break
					case 'd':
						compilerArgs.enableDigests = true
						break
					case 'z':
						compilerArgs.enableGzip = true
						break
					case 'm':
						compilerArgs.enableSourceMaps = true
						break
					case 'n':
						compilerArgs.skipNonDigests = true
						break
					case 'J':
						def jsonString = args[++x]
						try {
							def json = new JsonSlurper().parseText(jsonString)
							compilerArgs.putAll(json)
						} catch(Exception e) {
							log.error("Error parsing JSON options: ${jsonString}", e)
							System.exit(1)
						}
						break

				}
			}
		}
		def assetCompiler = new AssetCompiler(compilerArgs)
		assetCompiler.excludeRules.default = compilerArgs.excludes ?: []
		assetCompiler.includeRules.default = compilerArgs.includes ?: []
		if(compilerArgs.get("configOptions")) {
			AssetPipelineConfigHolder.config = (AssetPipelineConfigHolder.config ?: [:]) + compilerArgs.get("configOptions")
		} else {
			AssetPipelineConfigHolder.config = [:]
		}
		if(compilerArgs.cacheLocation) {
			AssetPipelineConfigHolder.config.cacheLocation = compilerArgs.cacheLocation
		}
		try {
			assetCompiler.compile();
		} catch(Exception e) {
			log.error("Error compiling assets", e)
			System.err.println("Error compiling assets: ${e.message}")
			e.printStackTrace(System.err)
			System.exit(1)
		}

		System.exit(0);

	}

	void compile() {
		def assetDir = initializeWorkspace()

		threadPool = Executors.newFixedThreadPool(options.maxThreads ?: Runtime.getRuntime().availableProcessors())
	 	CompletionService completionService = new ExecutorCompletionService(threadPool);
		try {
			def minifyCssProcessor = new CssMinifyPostProcessor()

			filesToProcess = this.getAllAssets()?.sort { a,b -> (a instanceof GenericAssetFile ? 1 : 0) <=> (b instanceof GenericAssetFile ? 1 : 0)}
			// Lets clean up assets that are no longer being compiled
			removeDeletedFiles(filesToProcess)
			def futures = []
			for(int index = 0; index < filesToProcess.size(); index++) {
				def assetFile = filesToProcess[index]
				def indexPosition = new Integer(index)
				futures << completionService.submit({ ->
					def fileName = assetFile.path
					String futureResult = assetFile.path
					def startTime = new Date().time
					eventListener?.triggerEvent("StatusUpdate", "Processing File ${indexPosition + 1} of ${filesToProcess.size()} - ${fileName}")

					def digestName
					def isUnchanged = false
					def extension = AssetHelper.extensionFromURI(fileName)
					fileName = AssetHelper.nameWithoutExtension(fileName)
					def fileSystemName = fileName.replace(AssetHelper.DIRECTIVE_FILE_SEPARATOR, File.separator)


					if(assetFile) {
						def fileData
						if(!(assetFile instanceof GenericAssetFile)) {
							if(assetFile.compiledExtension) {
								extension = assetFile.compiledExtension
								fileName = AssetHelper.fileNameWithoutExtensionFromArtefact(fileName, assetFile)
							}
							def contentType = (assetFile.contentType instanceof String) ? assetFile.contentType : assetFile.contentType[0]
							def directiveProcessor = new DirectiveProcessor(contentType, this, options.classLoader)
							fileData = directiveProcessor.compile(assetFile)
							digestName = AssetHelper.getByteDigest(fileData.bytes)
							def existingDigestFile = manifestProperties.getProperty("${fileName}${extension ? ('.' + extension) : ''}")
							if(existingDigestFile && existingDigestFile == "${fileName}-${digestName}${extension ? ('.' + extension) : ''}") {
								isUnchanged = true
							}
							if(fileName.indexOf(".min") == -1 && contentType == 'application/javascript' && options.minifyJs && !isUnchanged && !isMinifyExcluded(assetFile.path)) {
								def newFileData = fileData
								try {
									def closureCompilerProcessor = new ClosureCompilerProcessor(this)
									// eventListener?.triggerEvent("StatusUpdate", "- Minifying File")
									newFileData = closureCompilerProcessor.process(fileName, fileData, options.minifyOptions ?: [:])
								} catch(e) {
									log.error("Closure uglify JS Exception", e)
									throw(e)
									newFileData = fileData
								}
								fileData = newFileData
							} else if(fileName.indexOf(".min") == -1 && contentType == 'text/css' && options.minifyCss && !isUnchanged && !isMinifyExcluded(assetFile.path)) {
								def newFileData = fileData
								try {
									// eventListener?.triggerEvent("StatusUpdate", "- Minifying File")
									newFileData = minifyCssProcessor.process(fileData)
								} catch(e) {
									log.error("Minify CSS Exception", e)
									newFileData = fileData
								}
								fileData = newFileData
							}

							if(assetFile.encoding) {
								fileData = fileData.getBytes(assetFile.encoding)
							} else {
								fileData = fileData.bytes
							}

						} else {
							digestName = assetFile.getByteDigest()
							def existingDigestFile = manifestProperties.getProperty("${fileName}${extension ? ('.' + extension) : ''}")
							if(existingDigestFile && existingDigestFile == "${fileName}-${digestName}${extension ? ('.' + extension) : ''}") {
								isUnchanged = true
							}
						}

						if(!isUnchanged) {
							def outputFileName = fileSystemName
							if(extension) {
								outputFileName = "${fileSystemName}.${extension}"
							}
							def outputFile = new File(options.compileDir, "${outputFileName}")

							def parentTree = new File(outputFile.parent)
							parentTree.mkdirs()

							byte[] outputBytes
							InputStream writeInputStream;
							if(fileData) {
								writeInputStream = new ByteArrayInputStream(fileData)
								// outputBytes = fileData

							} else {
								if(assetFile instanceof GenericAssetFile) {
									writeInputStream = assetFile.inputStream
									outputBytes = assetFile.bytes
								} else {
									writeInputStream = assetFile.inputStream
									outputBytes = assetFile.inputStream.bytes
									digestName = assetFile.getByteDigest()
								}
							}
							// TODO: Streamify!
							// eventListener?.triggerEvent("StatusUpdate","- Writing File")

							byte[] buffer = new byte[8192]
							int nRead
							def outputFileStream
							def digestFileStream
							def gzipFileStream
							def gzipStreamCollection = []

							if(!options.skipNonDigests) {
								outputFile.createNewFile()
								outputFileStream = outputFile.newOutputStream()
								if(options.enableGzip == true && !options.excludesGzip.find {
									it.toLowerCase() == extension?.toLowerCase()
								}) {
									File zipFile = new File("${outputFile.getAbsolutePath()}.gz")
									zipFile.createNewFile()
									gzipStreamCollection << zipFile.newOutputStream()
								}
							}
							if(extension) {
								if(options.enableDigests) {
									def digestedFile = new File(options.compileDir, "${fileSystemName}-${digestName}${extension ? ('.' + extension) : ''}")
									digestedFile.createNewFile()
									digestFileStream = digestedFile.newOutputStream()
									if(options.enableGzip == true && !options.excludesGzip.find {
										it.toLowerCase() == extension?.toLowerCase()
									}) {
										File zipFileDigest = new File("${digestedFile.getAbsolutePath()}.gz")
										zipFileDigest.createNewFile()
										gzipStreamCollection << zipFileDigest.newOutputStream()
									}
									manifestProperties.setProperty("${fileName}${extension ? ('.' + extension) : ''}", "${fileName}-${digestName}${extension ? ('.' + extension) : ''}")
								} else {
									manifestProperties.setProperty("${fileName}${extension ? ('.' + extension) : ''}", "${fileName}${extension ? ('.' + extension) : ''}")
								}
							}

							if(gzipStreamCollection) {
								MultiOutputStream targetStream = new MultiOutputStream(gzipStreamCollection)
								gzipFileStream = new GZIPOutputStream(targetStream, true)
							}
							while((nRead = writeInputStream.read(buffer, 0, buffer.length)) != -1) {
								// noop (just to complete the stream)
								outputFileStream?.write(buffer, 0, nRead);
								digestFileStream?.write(buffer, 0, nRead);
								gzipFileStream?.write(buffer, 0, nRead);
							}
							if(gzipFileStream) {
								gzipFileStream.finish()
								gzipFileStream.flush()
								gzipFileStream.close()
								gzipStreamCollection.each { stream ->
									stream.flush()
									stream.close()
								}
							}

							digestFileStream?.flush()
							outputFileStream?.flush()
							digestFileStream?.close()
							outputFileStream?.close()
							writeInputStream.close()
							return futureResult
						}

					}
				} as Callable)
			}
			int pending = futures.size()
		  	while (pending > 0) {
			      // Wait for up to 100ms to see if anything has completed.
			      // The completed future is returned if one is found; otherwise null.
			      // (Tune 100ms as desired)
			      def completed = completionService.poll(100, TimeUnit.MILLISECONDS);
			      
			      if (completed != null) {
			      		completed.get() //need this to throw exceptions on main thread it seems
			          --pending;
		    		}
			  }
			// Integer futureCounter = 1
			// for(future in futures) {
			// 	while(!future.isDone()) { sleep(100)}
			// 	eventListener?.triggerEvent("StatusUpdate", "Future Completed ${futureCounter++}")
			// }
		} finally {
			// eventListener?.triggerEvent("StatusUpdate", "Shutting Down ThreadPool")
			threadPool.shutdown()
		}
		// eventListener?.triggerEvent("StatusUpdate", "Saving Manifest")
		saveManifest()
		eventListener?.triggerEvent("StatusUpdate", "Finished Precompiling Assets")
	}

	private initializeWorkspace() {
		// Check for existing Compiled Assets
		def assetDir = new File(options.compileDir)
		if(assetDir.exists()) {
			def manifestFile = new File(options.compileDir, "manifest.properties")
			if(manifestFile.exists())
				manifestProperties.load(manifestFile.newDataInputStream())
		} else {
			assetDir.mkdirs()
		}
		return assetDir
	}

	/**
	 * Checks any user passed minification exclude patterns at (minifyOptions.excludes=['blah.js'])
	 * Exclude patterns can use glob patterns by default or regular expressions by prefixing the pattern with 'regex:'
	 * @param filePath the file path being tested against
	 * @return true if the file should be excluded from minification
	 */
	private boolean isMinifyExcluded(String filePath) {
		if(options.minifyOptions?.excludes) {
			return AssetHelper.isFileMatchingPatterns(filePath, options.minifyOptions.excludes)
		}
		return false
	}

	def getIncludesForPathKey(String key) {
		def includes = []
		def defaultIncludes = includeRules.default
		if(defaultIncludes) {
			includes += defaultIncludes
		}
		if(includeRules[key]) {
			includes += includeRules[key]
		}
		return includes.unique()
	}

	def getExcludesForPathKey(String key) {
		def excludes = ["**/.*", "**/.DS_Store", 'WEB-INF/**/*', '**/META-INF/*', '**/_*.*', '**/.svn/**']
		def defaultExcludes = excludeRules.default
		if(defaultExcludes) {
			excludes += defaultExcludes
		}
		if(excludeRules[key]) {
			excludes += excludeRules[key]
		}

		return excludes.unique()
	}


	def getAllAssets() {
		def filesToProcess = []
		AssetPipelineConfigHolder.resolvers.each { resolver ->
			def files = resolver.scanForFiles(getExcludesForPathKey(resolver.name), getIncludesForPathKey(resolver.name))
			filesToProcess += files
		}

		filesToProcess.unique { a, b -> a.path <=> b.path }
		return filesToProcess //Make sure we have a unique set
	}

	private saveManifest() {
		// Update Manifest
		def manifestFile = new File(options.compileDir, 'manifest.properties')
		manifestProperties.store(manifestFile.newWriter(), "")
	}


	private removeDeletedFiles(filesToProcess) {
		def compiledFileNames = filesToProcess.collect { assetFile ->
			def fileName = assetFile.path
			def extension = AssetHelper.extensionFromURI(fileName)
			fileName = AssetHelper.nameWithoutExtension(fileName)

			if(assetFile && !(assetFile instanceof GenericAssetFile) && assetFile.compiledExtension) {
				extension = assetFile.compiledExtension
				fileName = AssetHelper.fileNameWithoutExtensionFromArtefact(fileName, assetFile)
			}
			return "${fileName}${extension ? ('.' + extension) : ''}"
		}

		def propertiesToRemove = []
		manifestProperties.keySet().each { compiledUri ->
			def compiledName = compiledUri//.replace(AssetHelper.DIRECTIVE_FILE_SEPARATOR,File.separator)

			def fileFound = compiledFileNames.find { it == compiledName.toString() }
			if(!fileFound) {
				def digestedUri = manifestProperties.getProperty(compiledName)
				def digestedName = digestedUri//.replace(AssetHelper.DIRECTIVE_FILE_SEPARATOR,File.separator)
				def compiledFile = new File(options.compileDir, compiledName)
				def digestedFile = new File(options.compileDir, digestedName)
				def zippedFile = new File(options.compileDir, "${compiledName}.gz")
				def zippedDigestFile = new File(options.compileDir, "${digestedName}.gz")
				if(compiledFile.exists()) {
					compiledFile.delete()
				}
				if(digestedFile.exists()) {
					digestedFile.delete()
				}
				if(zippedFile.exists()) {
					zippedFile.delete()
				}
				if(zippedDigestFile.exists()) {
					zippedDigestFile.delete()
				}
				propertiesToRemove << compiledName
			} else {
				def compiledFile = new File(options.compileDir, compiledName)
				def zippedFile = new File(options.compileDir, "${compiledName}.gz")

				if(compiledFile.exists() && options.skipNonDigests == true) {
					compiledFile.delete()
				}
				if(zippedFile.exists() && options.skipNonDigests == true) {
					zippedFile.delete()
				}
				propertiesToRemove << compiledName
			}
		}

		propertiesToRemove.each {
			manifestProperties.remove(it)
		}
	}

	static void registerJarResolvers(File jarFile) {
		def isJarFile = jarFile.name.endsWith('.jar') || jarFile.name.endsWith('.zip')
		if (jarFile.exists() && isJarFile) {
			AssetPipelineConfigHolder.registerResolver(new JarAssetResolver(jarFile.name, jarFile.canonicalPath, 'META-INF/assets'))
			AssetPipelineConfigHolder.registerResolver(new JarAssetResolver(jarFile.name, jarFile.canonicalPath, 'META-INF/static'))
			AssetPipelineConfigHolder.registerResolver(new JarAssetResolver(jarFile.name, jarFile.canonicalPath, 'META-INF/resources'))
		}
	}
}
