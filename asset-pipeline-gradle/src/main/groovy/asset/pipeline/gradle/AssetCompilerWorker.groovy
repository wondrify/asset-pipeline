/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package asset.pipeline.gradle

import groovy.transform.CompileStatic
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

/**
 * Worker implementation for Asset Pipeline compilation using Gradle Worker API.
 * This replaces the forked execution to avoid command line length issues on Windows.
 */
@CompileStatic
abstract class AssetCompilerWorker implements WorkAction<AssetCompilerWorker.Parameters> {

    static interface Parameters extends WorkParameters {
        /**
         * Input directory containing assets to compile
         */
        String getInputDirectory()
        void setInputDirectory(String inputDirectory)

        /**
         * Output directory for compiled assets
         */
        String getOutputDirectory()
        void setOutputDirectory(String outputDirectory)

        /**
         * Base64 encoded JSON configuration
         */
        String getConfigurationJson()
        void setConfigurationJson(String configurationJson)

        /**
         * List of jar file paths for resolvers
         */
        List<String> getJarResolvers()
        void setJarResolvers(List<String> jarResolvers)

        /**
         * List of additional input directories
         */
        List<String> getAdditionalInputs()
        void setAdditionalInputs(List<String> additionalInputs)
    }

    @Override
    void execute() {
        def params = getParameters()
        
        // Build arguments array similar to original implementation
        List<String> arguments = [
            "-i", params.inputDirectory,
            "-o", params.outputDirectory
        ]

        // Add additional input directories
        params.additionalInputs?.each { inputDir ->
            arguments.add("-i")
            arguments.add(inputDir)
        }

        // Add jar resolvers
        params.jarResolvers?.each { jarPath ->
            arguments.add("-j")
            arguments.add(jarPath)
        }

        // Add base64 encoded configuration
        if (params.configurationJson) {
            arguments.add("-B")
            arguments.add(params.configurationJson)
        }

        // Execute the asset compiler main method directly
        try {
            // Convert arguments to array
            String[] argsArray = arguments.toArray(new String[0])
            
            // Call the AssetCompiler main method directly in the worker process
            Class<?> assetCompilerClass = Class.forName('asset.pipeline.AssetCompiler')
            def mainMethod = assetCompilerClass.getMethod('main', String[].class)
            mainMethod.invoke(null, (Object) argsArray)
            
        } catch (Exception e) {
            throw new RuntimeException("Asset compilation failed", e)
        }
    }
}
