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
 * Abstract implementation of the {@link Processor} interface
 *
 * @author David Estes
 */
abstract class AbstractProcessor implements Processor {
	AssetCompiler precompiler

	/**
	* Constructor for building a Processor
     *
	* @param precompiler - An Instance of the AssetCompiler class compiling the file or NULL for dev mode.
	*/
	AbstractProcessor(AssetCompiler precompiler) {
		this.precompiler = precompiler
	}
}