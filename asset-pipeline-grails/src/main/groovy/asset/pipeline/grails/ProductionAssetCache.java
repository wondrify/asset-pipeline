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
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * What the filter has learned about each url it looked up in a compiled application: whether the
 * asset exists, and where. It keeps at most {@code grails.assets.maxCacheSize} assets it found,
 * {@value #DEFAULT_MAXIMUM_SIZE} unless configured, and as many urls it did not find, apart, so
 * that requests for urls that don't exist never push out an asset the application serves. Within
 * each, Caffeine admits an entry over the one it would evict by how often each is asked for, so
 * the assets an application serves most stay cached.
 *
 * <p>It is a {@code Map} of url to what was found, as it was when it extended
 * {@code ConcurrentHashMap}.
 */
public class ProductionAssetCache extends AbstractMap<String, AssetAttributes> {

    public static final String MAXIMUM_SIZE_KEY = "maxCacheSize";

    public static final long DEFAULT_MAXIMUM_SIZE = 10_000L;

    // What a size of zero keeps: nothing, without building a cache to evict from
    private static final Map<String, AssetAttributes> NOTHING = new AbstractMap<>() {
        @Override
        public AssetAttributes put(String uri, AssetAttributes attributes) {
            return null;
        }

        @Override
        public Set<Entry<String, AssetAttributes>> entrySet() {
            return Collections.emptySet();
        }
    };

    private volatile Tiers tiers;

    public ProductionAssetCache() {
        this(DEFAULT_MAXIMUM_SIZE);
    }

    /**
     * @param maximumSize the most assets kept, and the most missing urls; zero keeps none
     */
    public ProductionAssetCache(long maximumSize) {
        setMaximumSize(maximumSize);
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
        long maximumSize;
        try {
            // Rather than Number.longValue(), which would truncate 1.5 to 1
            maximumSize = new BigDecimal(configured.toString().trim()).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("grails.assets." + MAXIMUM_SIZE_KEY + " must be a whole number, not '" + configured + "'", e);
        }
        return zeroOrMore(maximumSize);
    }

    public long getMaximumSize() {
        return tiers.maximumSize;
    }

    /**
     * Empties the cache and bounds it anew.
     *
     * @param maximumSize the most assets kept, and the most missing urls; zero keeps none
     */
    public void setMaximumSize(long maximumSize) {
        this.tiers = new Tiers(zeroOrMore(maximumSize));
    }

    private static long zeroOrMore(long maximumSize) {
        if (maximumSize < 0) {
            throw new IllegalArgumentException("grails.assets." + MAXIMUM_SIZE_KEY + " must be zero or more, not " + maximumSize);
        }
        return maximumSize;
    }

    @Override
    public AssetAttributes get(Object uri) {
        Tiers current = tiers;
        AssetAttributes attributes = current.found.get(uri);
        return attributes != null ? attributes : current.missing.get(uri);
    }

    @Override
    public AssetAttributes put(String uri, AssetAttributes attributes) {
        Tiers current = tiers;
        // A url whose asset has appeared or gone moves from one to the other
        AssetAttributes moved = (attributes.exists() ? current.missing : current.found).remove(uri);
        AssetAttributes replaced = (attributes.exists() ? current.found : current.missing).put(uri, attributes);
        return replaced != null ? replaced : moved;
    }

    @Override
    public boolean containsKey(Object uri) {
        Tiers current = tiers;
        return current.found.containsKey(uri) || current.missing.containsKey(uri);
    }

    @Override
    public AssetAttributes remove(Object uri) {
        Tiers current = tiers;
        AssetAttributes found = current.found.remove(uri);
        AssetAttributes missing = current.missing.remove(uri);
        return found != null ? found : missing;
    }

    @Override
    public void clear() {
        Tiers current = tiers;
        current.found.clear();
        current.missing.clear();
    }

    @Override
    public Set<Entry<String, AssetAttributes>> entrySet() {
        Tiers current = tiers;
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<String, AssetAttributes>> iterator() {
                return new Iterator<>() {
                    private final Iterator<Entry<String, AssetAttributes>> found = current.found.entrySet().iterator();
                    private final Iterator<Entry<String, AssetAttributes>> missing = current.missing.entrySet().iterator();
                    private Iterator<Entry<String, AssetAttributes>> last;

                    @Override
                    public boolean hasNext() {
                        return found.hasNext() || missing.hasNext();
                    }

                    @Override
                    public Entry<String, AssetAttributes> next() {
                        last = found.hasNext() ? found : missing;
                        return last.next();
                    }

                    @Override
                    public void remove() {
                        if (last == null) {
                            throw new IllegalStateException();
                        }
                        last.remove();
                    }
                };
            }

            @Override
            public int size() {
                return current.found.size() + current.missing.size();
            }
        };
    }

    private static final class Tiers {

        final long maximumSize;

        final Map<String, AssetAttributes> found;

        final Map<String, AssetAttributes> missing;

        Tiers(long maximumSize) {
            this.maximumSize = maximumSize;
            this.found = bounded(maximumSize);
            this.missing = bounded(maximumSize);
        }

        private static Map<String, AssetAttributes> bounded(long maximumSize) {
            if (maximumSize == 0) {
                return NOTHING;
            }
            // Evict on the thread that adds, rather than on ForkJoinPool.commonPool(), so the bound
            // holds as entries are added and the application's own pool does none of the work
            return Caffeine.newBuilder()
                    .maximumSize(maximumSize)
                    .executor(Runnable::run)
                    .<String, AssetAttributes>build()
                    .asMap();
        }
    }
}
