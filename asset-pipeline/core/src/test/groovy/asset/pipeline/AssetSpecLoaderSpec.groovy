package asset.pipeline

import spock.lang.Specification

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
 * @author graemerocher
 */
class AssetSpecLoaderSpec extends Specification {

    void "Test that the asset spec loader loads specs correctly"() {
        when:"The specs are loaded"
            def specifications = AssetSpecLoader.loadSpecifications()

        then:"The specifications are correct"
            specifications.size() == 4
            specifications.contains JsAssetFile
            specifications.contains JsEs6AssetFile
            specifications.contains HtmlAssetFile
            specifications.contains CssAssetFile
    }
}
