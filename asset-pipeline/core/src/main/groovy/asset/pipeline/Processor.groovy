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

/**
 * Processor for an {@link AssetFile}
 *
 * @author David Estes
 * @author Graeme Rocher
 *
 */
interface Processor {
    /**
     * Takes a String input and runs the relevant processor of the file contents. The result is then returned as a String
     * @param inputText the input text to be processed
     * @param assetFile a reference to the current {@link AssetFile} being processed
     * @return the results
     */
	String process(String inputText, AssetFile assetFile)
}