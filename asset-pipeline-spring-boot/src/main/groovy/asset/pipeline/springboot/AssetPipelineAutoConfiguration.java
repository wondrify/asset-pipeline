/*
 * Copyright 2026 the original author or authors.
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
package asset.pipeline.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * Serves what the pipeline compiled, in a Spring Boot application that adds this library. The
 * manifest an application ships is read where it is, and the filter serves the digest-named files
 * beside it; an application built without one gets the development filter, which compiles an asset
 * as it is asked for.
 *
 * <p>An application that declares {@link AssetPipelineService} itself keeps its own, and one that
 * has this library on its class path without wanting the filter sets {@code assets.enabled} to
 * {@code false}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = AssetPipelineAutoConfiguration.ENABLED, matchIfMissing = true)
@ConditionalOnMissingBean(name = AssetPipelineAutoConfiguration.FILTER_BEAN_NAME)
@Import(AssetPipelineService.class)
public class AssetPipelineAutoConfiguration {

    static final String FILTER_BEAN_NAME = "assetPipelineFilterBean";

    /** Named for {@code assets.mapping}, which the Micronaut adapter already reads. */
    static final String ENABLED = "assets.enabled";

}
