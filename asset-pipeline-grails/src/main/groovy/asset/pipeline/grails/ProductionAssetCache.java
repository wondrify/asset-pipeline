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
 * What the filter has learned about each url it looked up in a compiled application: whether the
 * asset exists, and where. It holds at most {@code grails.assets.maxCacheSize} urls,
 * {@value #DEFAULT_MAXIMUM_SIZE} unless configured. Caffeine admits an entry over the one it would
 * evict by how often each is asked for, so once the cache has filled, a url asked for once, as a
 * scanner's are, does not push out an asset asked for more often.
 *
 * <p>It is a {@code ConcurrentMap} of url to what was found, as it was when it extended
 * {@code ConcurrentHashMap}: each method is Caffeine's map view of the cache, atomic where
 * {@code ConcurrentMap} requires it.
 */
public class ProductionAssetCache implements ConcurrentMap<String, AssetAttributes> {

    public static final String MAXIMUM_SIZE_KEY = "maxCacheSize";

    public static final long DEFAULT_MAXIMUM_SIZE = 10_000L;

    private final Cache<String, AssetAttributes> cache;

    private final ConcurrentMap<String, AssetAttributes> map;

    public ProductionAssetCache() {
        this(DEFAULT_MAXIMUM_SIZE);
    }

    /**
     * @param maximumSize the most urls kept; zero keeps none
     */
    public ProductionAssetCache(long maximumSize) {
        // Evict on the thread that adds, rather than on ForkJoinPool.commonPool(), so the bound
        // holds as entries are added and the application's own pool does none of the work
        this.cache = Caffeine.newBuilder()
                .maximumSize(zeroOrMore(maximumSize))
                .executor(Runnable::run)
                .build();
        this.map = cache.asMap();
    }

    /**
     * The {@code maxCacheSize} in the asset pipeline configuration, or the default when it is not
     * set. It is a whole number, or a string of one, as a system property or a {@code ${...}}
     * placeholder in application.yml gives it. Grails does not map an environment variable such as
     * {@code GRAILS_ASSETS_MAXCACHESIZE} onto {@code grails.assets}, so one reaches the setting
     * only through a placeholder.
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
            throw new IllegalArgumentException(setting() + " must be a whole number, not '" + configured + "'", e);
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(setting() + " must be zero or more, not " + configured);
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(setting() + " must be a whole number, not " + configured);
        }
        if (value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(setting() + " must be at most " + Long.MAX_VALUE + ", not " + configured);
        }
        return value.longValueExact();
    }

    public long getMaximumSize() {
        return eviction().getMaximum();
    }

    /**
     * Bounds the cache anew, keeping what it holds unless that is more than the new bound allows.
     *
     * @param maximumSize the most urls kept; zero keeps none
     */
    public void setMaximumSize(long maximumSize) {
        eviction().setMaximum(zeroOrMore(maximumSize));
    }

    private Policy.Eviction<String, AssetAttributes> eviction() {
        return cache.policy().eviction().orElseThrow();
    }

    private static long zeroOrMore(long maximumSize) {
        if (maximumSize < 0) {
            throw new IllegalArgumentException(setting() + " must be zero or more, not " + maximumSize);
        }
        return maximumSize;
    }

    private static String setting() {
        return "grails.assets." + MAXIMUM_SIZE_KEY;
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean containsKey(Object uri) {
        return map.containsKey(uri);
    }

    @Override
    public boolean containsValue(Object attributes) {
        return map.containsValue(attributes);
    }

    @Override
    public AssetAttributes get(Object uri) {
        return map.get(uri);
    }

    @Override
    public AssetAttributes getOrDefault(Object uri, AssetAttributes defaultAttributes) {
        return map.getOrDefault(uri, defaultAttributes);
    }

    @Override
    public AssetAttributes put(String uri, AssetAttributes attributes) {
        return map.put(uri, attributes);
    }

    @Override
    public void putAll(Map<? extends String, ? extends AssetAttributes> entries) {
        map.putAll(entries);
    }

    @Override
    public AssetAttributes remove(Object uri) {
        return map.remove(uri);
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public Set<String> keySet() {
        return map.keySet();
    }

    @Override
    public Collection<AssetAttributes> values() {
        return map.values();
    }

    @Override
    public Set<Entry<String, AssetAttributes>> entrySet() {
        return map.entrySet();
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super AssetAttributes> action) {
        map.forEach(action);
    }

    @Override
    public AssetAttributes putIfAbsent(String uri, AssetAttributes attributes) {
        return map.putIfAbsent(uri, attributes);
    }

    @Override
    public boolean remove(Object uri, Object attributes) {
        return map.remove(uri, attributes);
    }

    @Override
    public boolean replace(String uri, AssetAttributes oldAttributes, AssetAttributes newAttributes) {
        return map.replace(uri, oldAttributes, newAttributes);
    }

    @Override
    public AssetAttributes replace(String uri, AssetAttributes attributes) {
        return map.replace(uri, attributes);
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super AssetAttributes, ? extends AssetAttributes> function) {
        map.replaceAll(function);
    }

    @Override
    public AssetAttributes computeIfAbsent(String uri, Function<? super String, ? extends AssetAttributes> mappingFunction) {
        return map.computeIfAbsent(uri, mappingFunction);
    }

    @Override
    public AssetAttributes computeIfPresent(String uri, BiFunction<? super String, ? super AssetAttributes, ? extends AssetAttributes> remappingFunction) {
        return map.computeIfPresent(uri, remappingFunction);
    }

    @Override
    public AssetAttributes compute(String uri, BiFunction<? super String, ? super AssetAttributes, ? extends AssetAttributes> remappingFunction) {
        return map.compute(uri, remappingFunction);
    }

    @Override
    public AssetAttributes merge(String uri, AssetAttributes attributes, BiFunction<? super AssetAttributes, ? super AssetAttributes, ? extends AssetAttributes> remappingFunction) {
        return map.merge(uri, attributes, remappingFunction);
    }

    @Override
    public boolean equals(Object other) {
        return other == this || map.equals(other);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @Override
    public String toString() {
        return map.toString();
    }
}
