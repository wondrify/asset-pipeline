/*
 * Copyright 2015 the original author or authors.
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

package asset.pipeline.grails;

import java.math.BigDecimal;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * What the filter has learned about each url it looked up in a compiled application: whether the
 * asset exists, and where. It holds at most {@code grails.assets.maxCacheSize} entries,
 * {@value #DEFAULT_MAXIMUM_SIZE} unless configured. Caffeine admits an entry over the one it would
 * evict by how often each is asked for, so the assets an application serves most stay cached.
 */
public class ProductionAssetCache {

    public static final String MAXIMUM_SIZE_KEY = "maxCacheSize";

    public static final long DEFAULT_MAXIMUM_SIZE = 10_000L;

    private final Cache<String, AssetAttributes> cache;

    public ProductionAssetCache() {
        this(DEFAULT_MAXIMUM_SIZE);
    }

    /**
     * @param maximumSize the most entries kept; zero keeps none
     */
    public ProductionAssetCache(long maximumSize) {
        if (maximumSize < 0) {
            throw new IllegalArgumentException("grails.assets." + MAXIMUM_SIZE_KEY + " must be zero or more, not " + maximumSize);
        }
        this.cache = Caffeine.newBuilder().maximumSize(maximumSize).build();
    }

    /**
     * A cache sized by {@code maxCacheSize} in the asset pipeline configuration, a whole number or a
     * string of one, as an environment variable or system property gives it.
     */
    public static ProductionAssetCache fromConfig(Map<?, ?> config) {
        Object configured = config == null ? null : config.get(MAXIMUM_SIZE_KEY);
        if (configured == null || configured.toString().isBlank()) {
            return new ProductionAssetCache();
        }
        long maximumSize;
        try {
            // Rather than Number.longValue(), which would truncate 1.5 to 1
            maximumSize = new BigDecimal(configured.toString().trim()).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("grails.assets." + MAXIMUM_SIZE_KEY + " must be a whole number, not '" + configured + "'", e);
        }
        return new ProductionAssetCache(maximumSize);
    }

    public AssetAttributes get(String uri) {
        return cache.getIfPresent(uri);
    }

    public void put(String uri, AssetAttributes attributes) {
        cache.put(uri, attributes);
    }

    public void clear() {
        cache.invalidateAll();
    }

    /** The number of entries, once any eviction still pending has run. */
    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    public long getMaximumSize() {
        return cache.policy().eviction().orElseThrow().getMaximum();
    }
}
