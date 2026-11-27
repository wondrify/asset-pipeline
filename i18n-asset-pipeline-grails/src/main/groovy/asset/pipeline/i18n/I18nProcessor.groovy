/*
 * I18nProcessor.groovy
 *
 * Copyright (c) 2014-2016, Daniel Ellermann
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


package asset.pipeline.i18n

import asset.pipeline.AbstractProcessor
import asset.pipeline.AssetCompiler
import asset.pipeline.AssetFile
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.fs.AssetResolver
import asset.pipeline.fs.JarAssetResolver
import grails.io.IOUtils
import grails.plugins.GrailsPlugin
import grails.plugins.Plugin
import grails.util.Environment
import grails.util.Holder
import grails.util.Holders
import groovy.transform.CompileStatic
import org.grails.plugins.DefaultGrailsPlugin
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.UrlResource

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.regex.Matcher
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import groovy.util.logging.Slf4j
import java.util.regex.Pattern


/**
 * The class {@code I18nProcessor} represents an asset processor which converts
 * i18n file consisting of code keys to localized messages and builds a
 * JavaScript file containing the function {@code $L} to obtain localized
 * strings on client side.
 * <p>
 * I18n files must obey the following rules:
 * <ul>
 *   <li>The file name (without extension) must end with the locale
 *   specification, e. g. {@code messages_de.i18n} or
 * {@code msg_en_UK.i18n}.</li>
 *   <li>The files are line based.</li>
 *   <li>All lines are trimmed (that is, whitespaces are removed from beginning
 *   and end of lines.</li>
 *   <li>Empty lines and lines starting with a hash {@code #} (comment lines)
 *   are ignored.</li>
 *   <li>Lines starting with <code>@import <i>path</i></code> are replaced by
 *   the content of the file with path <code><i>path</i></code>.  The suffix
 * {@code .i18n} at path is optional and is appended automatically.</li>
 *   <li>All other lines are treated as code keys which will be looked up in
 *   Grails message resources for the locale specified in the file.</li>
 * </ul>
 *
 * @author Daniel Ellermann
 * @author David Estes
 * @version 3.0
 */
@CompileStatic
@Slf4j
class I18nProcessor extends AbstractProcessor {

    //-- Constants ------------------------------

    protected static final String PROPERTIES_SUFFIX = '.properties'
    protected static final String XML_SUFFIX = '.xml'

    //-- Fields ---------------------------------

    ResourceLoader resourceLoader = new DefaultResourceLoader()

    //-- Constructors ---------------------------

    /**
     * Creates a new i18n resource processor within the given asset
     * pre-compiler.
     *
     * @param precompiler the given asset pre-compiler
     */
    I18nProcessor(AssetCompiler precompiler) {
        super(precompiler)
    }

    //-- Public methods -------------------------

    @Override
    String process(String inputText, AssetFile assetFile) {
        Matcher m = assetFile.name =~ /(\w+?)(_\w+)?\.i18n$/
        
        def options = []
        
        if(m){
            def baseFile = m.group(1)
            if (m.group(2)){
                def locales = m.group(2).split('_')
                
                def sb = new StringBuffer(baseFile)
                for(locale in locales){
                    if(locale.empty){
                        options << sb.toString()
                    }
                    else{
                        sb.append('_').append(locale)
                        options << sb.toString()
                    }
                }
            }
            else{
                options << baseFile
            }
        }
        else{
            options << 'messages'
        }
        
        
        
        Properties props
        if (assetFile.encoding != null) {
            props = loadMessages(options as List<String>, assetFile.encoding)
        } else {
            props = loadMessages(options as List<String>)
        }

        // At this point, inputText has been pre-processed (I18nPreprocessor).
        Map<String, String> messages = [:]
        inputText.toString()
                .eachLine { String line ->
            if (line != '') {
                if(line.startsWith('regexp:')){
                    def p = Pattern.compile(line.substring('regexp:'.length()).trim())
                    Map<Object,Object> matchedEntries = props.findAll{p.matcher((String)it.key).matches()}
                    messages.putAll((Map<String,String>)(Map<?,?>)matchedEntries)
                }
                else{
                    messages.put line, props.getProperty(line, line/*defaultValue*/)
                }
            }            
        }

        compileJavaScript messages
    }

    //-- Non-public methods ---------------------

    /**
     * Compiles JavaScript code from the given localized messages.
     *
     * @param messages the given messages
     * @return the compiled JavaScript code
     */
    private String compileJavaScript(Map<String, String> messages) {
        StringBuilder buf = new StringBuilder('''(function (win) {
    var messages = {
''')
        int i = 0
        messages = messages.sort()
        for (Map.Entry<String, String> entry in messages.entrySet()) {
            if (i++ > 0) {
                buf << ',\n'
            }
            String value = entry.value
                    .replace('\\', '\\\\')
                    .replace('\n', '\\n')
                    .replace('"', '\\"')
            buf << '        "' << entry.key << '": "' << value << '"'
        }
        buf << '''
    }

    if (typeof win.$L === 'function' && typeof win.$L.messages !== 'undefined') {

        var merge = function(destination, source) {
            for (var property in source) {
                if (source.hasOwnProperty(property)) {
                    destination[property] = source[property];
                }
            }
            return destination;
        };

        messages = merge(messages, win.$L.messages);
    }

    var getMessage = function (code) {
        return messages[code];
    };

    getMessage.messages = messages;

    win.$L = getMessage;
}(this));
'''
        buf.toString()
    }

    /**
     * Loads the message resources from the given file.
     *
     * @param fileName the given base file name
     * @return the read message resources
     * @throws FileNotFoundException    if no resource with the required
     *                                  localized messages exists
     */
    private Properties loadMessages(List<String> options, String encoding = 'utf-8') {
        Properties messages = new Properties()
        
        for(option in options){
            Properties props = new Properties()
            try{
                File file = locateFile(option)
                if(!file?.exists()) {
                     Resource res = locateResource(option)
                    if(!res?.exists()) {
                        log.warn "i18N: Could not load file ${option}"    
                    } else {
                        String propertiesString = IOUtils.toString(res.inputStream, encoding)
                        props.load(new StringReader(propertiesString))
                        messages.putAll(props)    
                    }
                } else {
                    //
                    props.load(new StringReader(file.getText(encoding)))
                    messages.putAll(props)    
                }
               
                
            }
            catch(Exception e){

                log.warn("i18N: Could not load file ${option}",e)
            }
        }
        messages
    }

    /**
     * Locates the resource containing the localized messages.  The method looks
     * in the following places:
     * <ul>
     *   <li>in classpath with extension {@code .properties}</li>
     *   <li>in classpath with extension {@code .xml}</li>
     *   <li>in file system in folder {@code grails-app/i18n} with extension
     * {@code .properties}</li>
     *   <li>in file system in folder {@code grails-app/i18n} with extension
     * {@code .xml}</li>
     * </ul>
     *
     * @param fileName the given base file name
     * @return the resource containing the messages
     * @throws FileNotFoundException    if no resource with the required
     *                                  localized messages exists
     */
    private Resource locateResource(String fileName) {
        Resource resource =
                resourceLoader.getResource(fileName + PROPERTIES_SUFFIX)
        if (!resource.exists()) {
            resource = resourceLoader.getResource(fileName + XML_SUFFIX)
        } 
        if (!resource.exists()) {
            resource = resourceLoader.getResource(
                    "file:grails-app/i18n/${fileName}${PROPERTIES_SUFFIX}"
            )
        } 
        if (!resource.exists()) {
            resource = resourceLoader.getResource(
                    "file:grails-app/i18n/${fileName}${XML_SUFFIX}"
            )
        } 
        String i18nPath = AssetPipelineConfigHolder.config.i18nPath

        if(!resource.exists()) {
            if(i18nPath) {
                resource = resourceLoader.getResource(
                    "file:${i18nPath}/${fileName}${PROPERTIES_SUFFIX}"
                )
                if(!resource.exists()) {
                    resource = resourceLoader.getResource(
                        "file:${i18nPath}/${fileName}${XML_SUFFIX}"
                    )   
                }

            }
        }
        if (!resource.exists()) {
            // Nuclear approach, scan all jar files for the messages file
            resource = loadFromAssetResolvers(fileName)
            
            
        }
        resource
    }

    private File locateFile(String fileName) {
       
        String i18nPath = AssetPipelineConfigHolder.config.i18nPath
        File i18nFile = null
        
        if(i18nPath) {
            i18nFile = new File(i18nPath,"${fileName}${PROPERTIES_SUFFIX}")
            if(!i18nFile.exists()) {
                i18nFile = new File(i18nPath,"${fileName}${XML_SUFFIX}")
            }
        }
        return i18nFile
        
    }

    private static Resource loadFromAssetResolvers(String filename){
        Resource result = null
        def pluginManager = Holders.pluginManager 
        if(pluginManager != null){
            for(GrailsPlugin plugin in pluginManager.allPlugins){
                File projectDir = plugin instanceof DefaultGrailsPlugin ? ((DefaultGrailsPlugin)plugin).projectDirectory : null
                if(projectDir != null){
                    String i18nPropertiesPath = new File(projectDir,"grails-app/i18n").canonicalPath
                    
                    if(new File(i18nPropertiesPath,filename+PROPERTIES_SUFFIX).exists()){
                        result = new FileSystemResource(new File(i18nPropertiesPath,filename+PROPERTIES_SUFFIX))
                        break
                    }
                    else if(new File(i18nPropertiesPath,filename+XML_SUFFIX).exists()){
                        result = new FileSystemResource(new File(i18nPropertiesPath,filename+XML_SUFFIX))
                        break
                    }
                }
                else{
                    def classLoader = plugin.getPluginClass().getClassLoader()
                    def url = classLoader.getResource(filename+PROPERTIES_SUFFIX)
                    if(!url){
                        url = classLoader.getResource(filename+XML_SUFFIX)
                    }
                    if(url){
                        result = new UrlResource(url)
                        break
                    }
                }
            }
        }
        else{
            def filenameOptions = [filename+PROPERTIES_SUFFIX,filename+XML_SUFFIX]
            for(AssetResolver resolver in AssetPipelineConfigHolder.resolvers){
                if(resolver instanceof JarAssetResolver){
                    JarFile jarFile = ((JarAssetResolver)resolver).baseJar;
                    Enumeration<JarEntry> entries = jarFile.entries()

                    while(entries.hasMoreElements()){
                        JarEntry entry = entries.nextElement()
                        String entryName = entry.name
                        if(filenameOptions.contains(entryName)){
                            result = new InputStreamResource(jarFile.getInputStream(entry))
                            break
                        }
                    }
                    if(result) break
                }
            }
        }
        return result
    }
}
