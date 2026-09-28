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

import asset.pipeline.fs.AssetResolver
import java.util.regex.Pattern

/**
* Provides an interface for referencing files resolved by the Asset-Pipeline AssetResolvers
 *
* @author David Estes
*/
interface AssetFile {
    static List<String> contentType
    static List<String> extensions
    static String compiledExtension
    static List<Class<Processor>> processors

    /**
     * @return The base file
     */
    AssetFile getBaseFile()

    /**
     * @return The encoding to use
     */
    String getEncoding()

    String getByteDigest()

    /**
     * Sets the encoding to use
     */
    void setEncoding(String encoding)
    /**
     * Sets the base file
     */
    void setBaseFile(AssetFile baseFile)

    /**
     * An input stream for the AssetFile
     */
    InputStream getInputStream()
    /**
     * The path to the parent
     */
    String getParentPath()
    /**
     * The path to the asset file
     */
    String getPath()
    /**
     * The name of the asset file
     */
    String getName()
    /**
     * @return the list of matched directive options
     */
    List<String> getMatchedDirectives()
    /**
     * set the matched directives list
     */
    void setMatchedDirectives(List<String> matchedDirectives)
    /**
     * The AssetResolver used to resolve the asset file
     */
    AssetResolver getSourceResolver()

    /**
     * Processes the AssetFile uses the Asset {@link Processor} instances
     *
     * @param precompiler The {@link AssetCompiler}
     * @param skipCaching Optional flag to disable development time cache manager persistence.
     * @return The processes contents
     */
    String processedStream(AssetCompiler precompiler, Boolean skipCaching)

    /**
     * Processes the AssetFile uses the Asset {@link Processor} instances
     *
     * @param precompiler The {@link AssetCompiler}
     * @return The processes contents
     */
    String processedStream(AssetCompiler precompiler)

    /**
     * Returns the directive pattern used for bundling directive matching.
     * Note this pattern should be multi-line capable.
     * @return
     */
    Pattern getDirectivePattern()

}
