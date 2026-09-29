package asset.pipeline

import groovy.util.logging.Slf4j

/*
 * Copyright 2014 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Loads asset specification. The asset-pipeline determines what processors and file types are processible via addon
 * plugins by scanning the classpath for "META-INF/asset-pipeline/asset.specs" files and "META-INF/asset-pipeline/processor.specs"
 * files. The 'asset.specs' file is a line by line list of classes that implement the {@link AssetFile} interface.
 * These are automatically added to the list of known specs and are what facilitate clean extensibility of the asset-pipeline.
 *
 * The 'processor.specs' file allows for adding additional {@link Processor} implementations to existing {@link AssetFile} classes.
 * It is a properties file with the key being the full class name of the {@link Processor} implementation and the value being
 * a comma delimited list of full class names of {@link AssetFile} implementations to be appended to
 *
 * @author Graeme Rocher
 */
@Slf4j
class AssetSpecLoader {

    static final String FACTORIES_RESOURCE_LOCATION = "META-INF/asset-pipeline/asset.specs"
    static final String PROCESSORS_RESOURCE_LOCATION = "META-INF/asset-pipeline/processor.specs"

    private static List<Class<AssetFile>> specifications = null
    /**
     * Load the specifications and return a list of specification classes
     *
     * @param classLoader The classloader
     *
     * @return A list of specification classes
     */
    static List<Class<AssetFile>> loadSpecifications(ClassLoader classLoader = Thread.currentThread().contextClassLoader) {

        if(specifications == null) {
            def resources = classLoader.getResources(FACTORIES_RESOURCE_LOCATION)
            specifications = []

            resources.each { URL res ->
                def classNames = res.getText('UTF-8').split(/\r?\n/).collect()  { String str -> str.trim() }

                for(className in classNames) {
                    try {
                        def cls = classLoader.loadClass(className)
                        if(AssetFile.isAssignableFrom(cls) ) {
                            if(!specifications.contains(cls))
                                specifications << (Class<AssetFile>) cls
                        }
                        else {
                            log.warn("Asset specification $className not registered because it does not implement the AssetFile interface")
                        }
                    } catch (Throwable e) {
                        log.error("Error loading asset specification $className: $e.message", e)
                    }
                }
            }
            applyProcessors(classLoader)
        }
        return specifications
    }

    /**
     * Loads processor.specs files and appends {@link Processor} implementations to matching {@link AssetFile} implementations.
     *
     * @param classLoader The classloader
     */
    static void applyProcessors(ClassLoader classLoader = Thread.currentThread().contextClassLoader) {
        def resources = classLoader.getResources(PROCESSORS_RESOURCE_LOCATION)
        resources.each { URL res ->
            Properties prop = new Properties()
            prop.load(res.openStream())
            prop.keySet().each { processorName ->
                def processorClass = classLoader.loadClass(processorName)
                def specs = prop.getProperty(processorName).split(',')
                specs.each { specName ->
                    try {
                        def specCls = classLoader.loadClass(specName)
                        if(!specCls.processors.contains(processorClass)) {
                            specCls.processors << processorClass
                        }
                    } catch(e) {
                        //Spec doesnt exist this could be normal if it is for example supporting coffee too
                    }
                }
            }
        }
    }
}
