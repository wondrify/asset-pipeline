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

import groovy.util.logging.Slf4j
import asset.pipeline.processors.JsRequireProcessor
import groovy.transform.CompileStatic

/**
 * This processor is a base processor put in front of most asset processing. It is responsible for parsing directives
 * like '//=require file' or '//=require_tree .' or '//=require_self'. It is capable of creating a dependency graph
 * of all files needed to be bundled together from these directives. Duplicate requires are ignored.
 *
 * @author David Estes
 */
@Slf4j
class DirectiveProcessor {

    /**
    * References names of directives to implemented methods.
    * This can be adjusted at run time to either override behavior or add custom directives
    */
    static DIRECTIVES = [require_self: "requireSelfDirective" ,require_tree: "requireTreeDirective", require_full_tree: "requireFullTreeDirective" , require: "requireFileDirective", encoding: "encodingTypeDirective"]

    private String contentType
    private AssetCompiler precompiler
    private Map files = [:]
    private def baseFile
    ClassLoader classLoader

    DirectiveProcessor(String contentType, AssetCompiler precompiler = null, ClassLoader classLoader=Thread.currentThread().contextClassLoader) {
        this.classLoader = classLoader
        this.contentType = contentType
        this.precompiler = precompiler
    }


    /**
    * Takes an AssetFile and compiles it to a final result based on input directives and
    * setup Processors. If a GenericAssetFile is passed in the raw byte array is returned.
    * @param file an instance of an AbstractAssetFile (i.e. JsAssetFile or CssAssetFile)
    */
    @CompileStatic
    def compile(AssetFile file) {
        try {
            Long startTime =  new Date().time
            if(file instanceof GenericAssetFile) {
                return file.getBytes()
            }
            this.baseFile = file
            this.files = [:]
            Map tree = getDependencyTree(file)
            StringBuilder buffer = new StringBuilder(64000)
            //since we are using tree based injection we need to remove duplicate asset requires
            JsRequireProcessor.withinDirectiveTree.set(true)
            JsRequireProcessor.commonJsModules.set([:] as Map<String,String>)

            loadContentsForTree(tree,buffer)
            if(JsRequireProcessor.commonJsModules.get()) {
                return JsRequireProcessor.requireMethod + JsRequireProcessor.modulesJs() + buffer.toString()
            } else {
                return buffer.toString()    
            }
        } finally {
            JsRequireProcessor.commonJsModules.set([:] as Map<String,String>)
            JsRequireProcessor.withinDirectiveTree.set(false)
        }
        
    }

    /**
    * Returns a Flattened list of files based on the require tree
    * This is useful for converting a script tag into several script tags for debugging
    * @param file an instance of an AbstractAssetFile (i.e. JsAssetFile or CssAssetFile)
    */
    def getFlattenedRequireList(file) {
        if(file instanceof GenericAssetFile) {
            return [[path: file.path, encoding: null]]
        }
        def flattenedList = []
        def tree = getDependencyTree(file)

        flattenedList = loadRequiresForTree(tree, flattenedList)
        return flattenedList
    }


    /**
    * Scans through a generated tree and builds a flatted list of requirements recursively
    */
    protected loadRequiresForTree(treeSet, flattenedList) {
        def selfLoaded = false
        for(childTree in treeSet.tree) {
            if(childTree == "self") {
                def extension = treeSet.file.compiledExtension
                def fileName = AssetHelper.fileNameWithoutExtensionFromArtefact(treeSet.file.path,treeSet.file)
                flattenedList << [path: "${fileName}.${extension}", encoding: treeSet.file.encoding]
                selfLoaded = true
            } else {
                flattenedList = loadRequiresForTree(childTree, flattenedList)
            }
        }

        if(!selfLoaded) {
            def extension = treeSet.file.compiledExtension
            def fileName = AssetHelper.fileNameWithoutExtensionFromArtefact(treeSet.file.path,treeSet.file)
            flattenedList << [path: "${fileName}.${extension}", encoding: treeSet.file.encoding]
        }
        return flattenedList
    }


    /**
    * Scans through a generated tree and builds a files contents recursively
    */
    protected void loadContentsForTree(Map treeSet,StringBuilder buffer) {

        Boolean selfLoaded = false
        for(childTree in treeSet.tree) {
            if(childTree == "self") {
                buffer.append(fileContents(treeSet.file)).append('\n')
                selfLoaded = true
            } else {
                loadContentsForTree(childTree,buffer)
            }
        }

        if(!selfLoaded) {
            buffer.append(fileContents(treeSet.file)).append('\n')
        }
    }

    /**
    * Builds a dependency tree for a particular file
    */
    @CompileStatic
    protected Map getDependencyTree(AssetFile file) {
        this.files[file.path] = file
        Map tree = [file:file,tree:[]]
        if(!(file instanceof GenericAssetFile)) {
            this.findDirectives(file as AssetFile,tree)
        }

        return tree
    }

    /**
    * Scans an AssetFile for directive patterns and builds a dependency tree
    * @param fileSpec The assetFile we wish to scan
    * @param tree The tree object we use to build the graph (should be a List)
    */
    @CompileStatic
    protected findDirectives(AssetFile fileSpec, Map tree) {

        String line = fileSpec.inputStream.getText("UTF-8")
        List directives = []
        if(fileSpec.directivePattern) {
            directives = (line =~ fileSpec.directivePattern)?.collect { List it -> it[1] as String }
        }
        for(String directive in directives) {
            directive = directive.trim()
            String[] unprocessedArgs = directive.split(/\s+/)
            String processor = DIRECTIVES[unprocessedArgs[0].toLowerCase()]
            if(processor) {
                String[] directiveArguments = unprocessedArgs
                if(directive.indexOf('$') >= 0) {
                        directiveArguments = directiveArgumentsFromGString(directive)

                }
                directiveArguments[0] = directiveArguments[0].toLowerCase()
                callDirective(processor,directiveArguments, fileSpec, tree)
            }
            else {
                // No matching processor means its a matched directive
                fileSpec.matchedDirectives += directive
            }
        }
    }


    public String[] directiveArgumentsFromGString(String directive) {
        try {
            return new groovy.text.GStringTemplateEngine(classLoader ?: this.class.classLoader).createTemplate(directive).make().toString().split(/\s+/)
        } catch(ex) {
            println "Error Processing GString Template Engine ${ex}"
            log.error("Error Processing GString Template Engine",ex)
            return directive
        }
    }

    public void callDirective(String name, String[] directiveArguments, AssetFile fileSpec, Map tree) {
        this."${name}"(directiveArguments, fileSpec,tree)
    }
    /**
    * Used to control file order for when content within your manifest exists
    */
    def requireSelfDirective(command, file, tree) {
        tree.tree << "self"
    }

    /**
    * Set your file encoding within your manifest
    */
    def encodingTypeDirective(command, fileSpec, tree) {
        if(!command[1]) {
            return;
        }
        if(fileSpec.baseFile) {
            fileSpec.baseFile.encoding = command[1]
        }
        fileSpec.encoding = command[1]
    }


    /**
    * Loads files recursively within the specified folder within the same source resolver
    * Example: //=require_tree .
    */
    def requireTreeDirective(command, fileSpec, tree) {
        String directivePath = command[1]
        def resolver = fileSpec.sourceResolver
        def files = resolver.getAssets(directivePath,contentType,null,true ,fileSpec,baseFile)

        files.each { file ->
            if(!isFileInTree(file)) {
                tree.tree << getDependencyTree(file)
            }
        }
    }


    /**
    * Loads files recursively within the specified relative path across ALL resolvers
    * Example: //=require_full_tree /spud/admin
    */
    def requireFullTreeDirective(command, fileSpec, tree) {
        String directivePath = command[1]
        for(resolver in AssetPipelineConfigHolder.resolvers) {
            def files = resolver.getAssets(directivePath,contentType,null,true ,fileSpec,baseFile)
            files.each { file ->
                if(!isFileInTree(file)) {
                    tree.tree << getDependencyTree(file)
                }
            }
        }
    }

    /**
    * Directive which allows inclusion of individual files
    * Example: //=require sample.js
    * Example (WebJar): //=require webjars/dist/jquery.js
    */
    def requireFileDirective(command, file, tree) {
        def fileName = command[1]

        List fileNameList = fileName.tokenize(',')
        if( fileNameList.size() > 1 ) {
            fileNameList.each{ thisListItem ->
                requireFileDirective( [ command[0], thisListItem ], file, tree )
            }
        }
        else {
            // Resolve WebJar paths before attempting to locate the file
            fileName = AssetHelper.resolveWebjarPath(fileName)

            def newFile
            if( fileName.startsWith( AssetHelper.DIRECTIVE_FILE_SEPARATOR ) ) {
                newFile = AssetHelper.fileForUri( fileName, this.contentType, null, this.baseFile )
            }
            else {
                def relativeFileName = [ file.parentPath, fileName ].join( AssetHelper.DIRECTIVE_FILE_SEPARATOR )
                newFile = AssetHelper.fileForUri( relativeFileName, this.contentType, null, this.baseFile )
            }

            if( newFile ) {
                if( !isFileInTree( newFile ) ) {
                    tree.tree << getDependencyTree( newFile )
                }
            }
            else if( !fileName.startsWith( AssetHelper.DIRECTIVE_FILE_SEPARATOR ) ) {
                command[ 1 ] = AssetHelper.DIRECTIVE_FILE_SEPARATOR + command[ 1 ]
                requireFileDirective( command, file, tree )
            }
            else {
                log.warn( "Unable to Locate Asset: ${ command[ 1 ] }" )
            }
        }
    }

    /**
     * Checks if a file reference is already in the dependency graph
     * @param file The file being checked for within the dependency graph
     * @return true/false depending on wether or not the file has already been included
     */
    @CompileStatic
    protected boolean isFileInTree(AssetFile file) {
        AssetFile result = files[file.path] as AssetFile
        if(result) {
            return true
        } else {
            return false
        }
    }

    /**
    * Used for fetching the contents of a file be it a Generic unprocessable entity
    * or an AssetFile with a processable stream
    */
    @CompileStatic
    String fileContents(AssetFile file) {
        return file.processedStream(this.precompiler)
    }

}
