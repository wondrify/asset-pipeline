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

package asset.pipeline.gradle

import asset.pipeline.AssetEventListener
import org.gradle.api.logging.Logger

/**
 * Used to broadcast status messages provided by the asset compiler to a Gradle logger
 */
class GradleEventListener implements AssetEventListener {

    private Logger logger

    GradleEventListener(Logger logger) {
        this.logger = logger
    }

    @Override
    void triggerEvent(String eventName, String message) {
        if(eventName == 'StatusDebug') {
            logger.debug(message)
        }
        else {
            logger.info(message)
        }
    }
}
