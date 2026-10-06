/*
 * Copyright 2026 the original author or authors.
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
package asset.pipeline.gradle

import asset.pipeline.AssetPipelineConfigHolder
import groovy.transform.CompileStatic
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.services.BuildServiceSpec

/**
 * Lets one asset task at a time use {@link AssetPipelineConfigHolder}. The tasks that compile or package the assets in
 * the Gradle daemon, rather than in a forked JVM, set the configuration and resolvers the holder keeps for the whole
 * JVM, so two of them running at once, as tasks of one project do with the configuration cache, would use each
 * other's.
 *
 * @since 5.2.1
 */
@CompileStatic
abstract class AssetPipelineConfigService implements BuildService<BuildServiceParameters.None> {

    static final String NAME = 'assetPipelineConfigHolder'

    /**
     * Makes the task wait for any other asset task using the holder.
     */
    static void usedBy(Task task, Project project) {
        Provider<AssetPipelineConfigService> service = project.gradle.sharedServices.registerIfAbsent(
                NAME, AssetPipelineConfigService) { BuildServiceSpec<BuildServiceParameters.None> spec ->
            spec.maxParallelUsages.set(1)
        }
        task.usesService(service)
    }
}
