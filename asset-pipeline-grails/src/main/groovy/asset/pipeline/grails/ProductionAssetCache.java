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
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy;

/**
 * What the filter has learned about the urls it looked up in a compiled application. It keeps at
 * most {@code grails.assets.maxCacheSize} assets it found, {@value #DEFAULT_MAXIMUM_SIZE} unless
 * configured, favoring those asked for most often, and as many urls that matched no asset, apart
 * from them, so that requests for urls that don't exist never evict an asset.
 *
 * <p>The assets are a {@code ConcurrentMap} of url to what was found: each of its methods is
 * Caffeine's map view of the cache, atomic where {@code ConcurrentMap} requires it. It is no
 * longer a {@code ConcurrentHashMap}, so that class's own methods, such as {@code mappingCount()},
 * are gone. The urls that matched no asset are kept through {@link #isMissing} and
 * {@link #putMissing}, and {@link #clear()} forgets them too.
 *
 * <p>With a bound of zero, {@link #put} and {@link #putMissing} keep nothing and leave the caches
 * alone, rather than adding entries for Caffeine to evict at once.
 */
public class ProductionAssetCache implements ConcurrentMap<String, AssetAttributes> {

    public static final String MAXIMUM_SIZE_KEY = "maxCacheSize";

    public static final long DEFAULT_MAXIMUM_SIZE = 10_000L;

    /** The largest bound Caffeine keeps: it lowers any larger maximum to this. */
    public static final long LARGEST_MAXIMUM_SIZE = Long.MAX_VALUE - Integer.MAX_VALUE;

    private static final String SETTING = "grails.assets." + MAXIMUM_SIZE_KEY;

    private final Cache<String, AssetAttributes> assets;

    private final Cache<String, Boolean> missing;

    public ProductionAssetCache() {
        this(DEFAULT_MAXIMUM_SIZE);
    }

    /**
     * @param maximumSize the most assets kept, and the most urls that matched none; zero keeps none
     */
    public ProductionAssetCache(long maximumSize) {
        long bound = validated(BigDecimal.valueOf(maximumSize), maximumSize);
        this.assets = bounded(bound);
        this.missing = bounded(bound);
    }

    /**
     * The {@code maxCacheSize} in the asset pipeline configuration, or the default when it is not
     * set. It is a whole number, or a string of one, as a system property gives it.
     */
    public static long maximumSizeOf(Map<?, ?> config) {
        Object configured = config == null ? null : config.get(MAXIMUM_SIZE_KEY);
        if (configured == null || configured.toString().isBlank()) {
            return DEFAULT_MAXIMUM_SIZE;
        }
        BigDecimal value;
        try {
            // Rather than Number.longValue(), which would truncate 1.5 to 1
            value = new BigDecimal(configured.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(SETTING + " must be a whole number, not '" + configured + "'", e);
        }
        return validated(value, configured);
    }

    public long getMaximumSize() {
        return eviction(assets).getMaximum();
    }

    /**
     * Bounds the cache anew, keeping what it holds unless that is more than the new bound allows.
     *
     * @param maximumSize the most assets kept, and the most urls that matched none; zero keeps none
     */
    public void setMaximumSize(long maximumSize) {
        long bound = validated(BigDecimal.valueOf(maximumSize), maximumSize);
        eviction(assets).setMaximum(bound);
        eviction(missing).setMaximum(bound);
    }

    /** Whether the url was recorded as matching no asset, and has not been evicted since. */
    public boolean isMissing(String uri) {
        return missing.getIfPresent(uri) != null;
    }

    /** Records that the url matched no asset. */
    public void putMissing(String uri) {
        if (getMaximumSize() > 0) {
            missing.put(uri, Boolean.TRUE);
        }
    }

    private static long validated(BigDecimal value, Object given) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(SETTING + " must be zero or more, not " + given);
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(SETTING + " must be a whole number, not " + given);
        }
        if (value.compareTo(BigDecimal.valueOf(LARGEST_MAXIMUM_SIZE)) > 0) {
            throw new IllegalArgumentException(SETTING + " must be at most " + LARGEST_MAXIMUM_SIZE + ", not " + given);
        }
        return value.longValueExact();
    }

    private static <V> Cache<String, V> bounded(long maximumSize) {
        // Evict on the thread that adds, rather than on ForkJoinPool.commonPool(), so the bound
        // holds as entries are added and the application's own pool does none of the work
        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .executor(Runnable::run)
                .build();
    }

    private static Policy.Eviction<String, ?> eviction(Cache<String, ?> cache) {
        return cache.policy().eviction().orElseThrow();
    }

    @Override
    public AssetAttributes put(String uri, AssetAttributes attributes) {
        if (getMaximumSize() == 0) {
            return null;
        }
        return assets.asMap().put(uri, attributes);
    }

    @Override
    public void clear() {
        assets.invalidateAll();
        missing.invalidateAll();
    }

    @Override
    public int size() {
        return assets.asMap().size();
    }

    @Override
    public boolean isEmpty() {
        return assets.asMap().isEmpty();
    }

    @Override
    public boolean containsKey(Object uri) {
        return assets.asMap().containsKey(uri);
    }

    @Override
    public boolean containsValue(Object attributes) {
        return assets.asMap().containsValue(attributes);
    }

    @Override
    public AssetAttributes get(Object uri) {
        return assets.asMap().get(uri);
    }

    @Override
    public AssetAttributes getOrDefault(Object uri, AssetAttributes defaultAttributes) {
        return assets.asMap().getOrDefault(uri, defaultAttributes);
    }

    @Override
    public void putAll(Map<? extends String, ? extends AssetAttributes> entries) {
        assets.asMap().putAll(entries);
    }

    @Override
    public AssetAttributes remove(Object uri) {
        return assets.asMap().remove(uri);
    }

    @Override
    public Set<String> keySet() {
        return assets.asMap().keySet();
    }

    @Override
    public Collection<AssetAttributes> values() {
        return assets.asMap().values();
    }

    @Override
    public Set<Entry<String, AssetAttributes>> entrySet() {
        return assets.asMap().entrySet();
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super AssetAttributes> action) {
        assets.asMap().forEach(action);
    }

    @Override
    public AssetAttributes putIfAbsent(String uri, AssetAttributes attributes) {
        return assets.asMap().putIfAbsent(uri, attributes);
    }

    @Override
    public boolean remove(Object uri, Object attributes) {
        return assets.asMap().remove(uri, attributes);
    }

    @Override
    public boolean replace(String uri, AssetAttributes oldAttributes, AssetAttributes newAttributes) {
        return assets.asMap().replace(uri, oldAttributes, newAttributes);
    }

    @Override
    public AssetAttributes replace(String uri, AssetAttributes attributes) {
        return assets.asMap().replace(uri, attributes);
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super AssetAttributes, ? extends AssetAttributes> function) {
        assets.asMap().replaceAll(function);
    }

    @Override
    public AssetAttributes computeIfAbsent(String uri, Function<? super String, ? extends AssetAttributes> mappingFunction) {
        return assets.asMap().computeIfAbsent(uri, mappingFunction);
    }

    @Override
    public AssetAttributes computeIfPresent(String uri, BiFunction<? super String, ? super AssetAttributes, ? extends AssetAttributes> remappingFunction) {
        return assets.asMap().computeIfPresent(uri, remappingFunction);
    }

    @Override
    public AssetAttributes compute(String uri, BiFunction<? super String, ? super AssetAttributes, ? extends AssetAttributes> remappingFunction) {
        return assets.asMap().compute(uri, remappingFunction);
    }

    @Override
    public AssetAttributes merge(String uri, AssetAttributes attributes, BiFunction<? super AssetAttributes, ? super AssetAttributes, ? extends AssetAttributes> remappingFunction) {
        return assets.asMap().merge(uri, attributes, remappingFunction);
    }

    @Override
    public boolean equals(Object other) {
        return other == this || assets.asMap().equals(other);
    }

    @Override
    public int hashCode() {
        return assets.asMap().hashCode();
    }

    @Override
    public String toString() {
        return assets.asMap().toString();
    }
}
