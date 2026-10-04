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

package asset.pipeline

import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Paths that name assets, as configuration and request urls give them: which urls an asset filter is registered for,
 * and which asset a request it receives names.
 *
 * <p>Kept apart from {@link AssetHelper}, which loads every asset specification on the class path when it is first
 * used, so that bean registration can read and check the configuration without doing that first.
 */
@CompileStatic
final class AssetPaths {

    /**
     * The characters that make an asset path a wildcard: {@code *} and {@code %}, alone or doubled.
     */
    static final Pattern WILDCARD_PATTERN = Pattern.compile(/[*%]/)

    /**
     * Characters an exact url pattern cannot match as written: a query, a fragment, path parameters, which a container
     * takes off before it maps a request, and a backslash.
     */
    private static final Pattern URL_DELIMITER = Pattern.compile(/[?#;\\]/)

    /**
     * The characters a url path carries as they are. Any other, such as a space or a letter outside ASCII, is encoded
     * in the url, and containers differ on whether an exact pattern written with it matches: Tomcat matches the
     * decoded path, Jetty the encoded one.
     */
    private static final Pattern URL_PATH = Pattern.compile(/[A-Za-z0-9\-._~!$&'()+,=:@\/]+/)

    /** Path parameters, such as {@code ;jsessionid=...}, which a container takes off a path before it maps a request. */
    private static final Pattern PATH_PARAMETERS = Pattern.compile(/;[^\/]*/)

    /** A run of slashes, which a container reads as one when it maps a request. */
    private static final Pattern REPEATED_SLASHES = Pattern.compile(/\/{2,}/)

    private AssetPaths() {
    }

    /**
     * The assets an application also serves from the root of its context, read from the {@code rootPaths} setting.
     * Browsers ask for some assets by a fixed name whatever a page links to: {@code /favicon.ico},
     * {@code /apple-touch-icon.png}, {@code /apple-touch-icon-precomposed.png}. Each entry names one
     * asset the way a tag does, and becomes an exact servlet url pattern, so an entry with a wildcard, a character a
     * url pattern cannot match, an empty, {@code .} or {@code ..} segment, or no file name is rejected, as is one with
     * a character a url must encode, which containers match inconsistently, and one under the mapping, where the asset
     * is already served. A blank entry, as a trailing comma leaves, is skipped.
     * @param configured a collection or array of paths, a comma separated string of them (the form a system property
     *        gives a list), or null
     * @param mapping the url the filter serves assets under, without slashes; empty or null when it serves them at
     *        the root, which leaves nothing for an entry to be under
     * @return the paths without a leading slash, in the order configured, each once
     * @throws IllegalArgumentException for an entry that does not name exactly one asset outside the mapping
     */
    static List<String> rootPaths(Object configured, String mapping) {
        Collection<?> entries
        if(configured == null) {
            entries = []
        } else if(configured instanceof CharSequence) {
            entries = configured.toString().split(',').toList()
        } else if(configured instanceof Collection) {
            entries = (Collection<?>) configured
        } else if(configured instanceof Object[]) {
            entries = Arrays.asList((Object[]) configured)
        } else {
            throw new IllegalArgumentException("rootPaths must be a list of asset paths, not a ${configured.getClass().name}")
        }
        Set<String> paths = new LinkedHashSet<String>()
        for(Object entry in entries) {
            String path = entry == null ? '' : entry.toString().trim()
            if(!path) {
                continue
            }
            while(path.startsWith('/')) {
                path = path.substring(1)
            }
            if(WILDCARD_PATTERN.matcher(path).find()) {
                throw new IllegalArgumentException("rootPaths entry '${entry}' names a pattern; each entry names one asset, such as favicon.ico")
            }
            if(!path || path.endsWith('/') || URL_DELIMITER.matcher(path).find() || path.split('/').any { String segment -> segment in ['', '.', '..'] }) {
                throw new IllegalArgumentException("rootPaths entry '${entry}' does not name one asset, such as favicon.ico")
            }
            if(!URL_PATH.matcher(path).matches()) {
                throw new IllegalArgumentException("rootPaths entry '${entry}' has a character a url must encode, which servlet containers match inconsistently; serve it under the mapping")
            }
            if(mapping && (path == mapping || path.startsWith(mapping + '/'))) {
                throw new IllegalArgumentException("rootPaths entry '${entry}' is under /${mapping}, where its asset is already served")
            }
            paths << path
        }
        return new ArrayList<String>(paths)
    }

    /**
     * The url patterns an asset filter is registered for: everything under the mapping, and the exact url of each
     * root path, so that no other request passes through it. An empty mapping already passes every request through
     * the filter, root paths included.
     */
    static List<String> urlPatterns(String mapping, Collection<String> rootPaths) {
        if(!mapping) {
            return ['/*']
        }
        List<String> patterns = ["/${mapping}/*".toString()]
        for(String rootPath in rootPaths) {
            patterns << '/' + rootPath
        }
        return patterns
    }

    /**
     * The path a request names within its application, decoded, as a container reads it before it maps the request:
     * the request uri without the context path, without path parameters such as {@code ;jsessionid=...}, and with
     * each run of slashes read as one. The uri and the context path are compared before decoding, as the container
     * gives them, and path parameters are taken off both, since a container such as Tomcat keeps those of the
     * context's own segment in the context path.
     * @return the decoded path, beginning with a slash
     */
    static String pathWithinContext(String requestUri, String contextPath) {
        String path = withoutPathParameters(requestUri)
        String context = contextPath ? withoutPathParameters(contextPath) : ''
        if(context && context != '/' && path.startsWith(context)) {
            path = path.substring(context.length())
        }
        return decoded(REPEATED_SLASHES.matcher(path ?: '/').replaceAll('/'))
    }

    private static String withoutPathParameters(String path) {
        return path.indexOf(';') < 0 ? path : PATH_PARAMETERS.matcher(path).replaceAll('')
    }

    // Percent-decoded as a path, in which + is itself, rather than through java.net.URI, which reads a leading // as
    // the start of an authority. A malformed escape names no asset, so a path with one is left as it is.
    private static String decoded(String path) {
        if(path.indexOf('%') < 0) {
            return path
        }
        try {
            return URLDecoder.decode(path.replace('+', '%2B'), StandardCharsets.UTF_8)
        } catch(IllegalArgumentException ignored) {
            return path
        }
    }

    /**
     * The asset a path within the application names under the mapping, as a path from the root of the assets:
     * {@code /app.js} for {@code /assets/app.js}, and empty for {@code /assets} itself. The mapping is a whole
     * segment, so {@code /assets-logo.png} is not under {@code assets}. With an empty mapping every path is under it.
     * @return the asset's path, or null for a path outside the mapping
     */
    static String pathUnderMapping(String path, String mapping) {
        if(!mapping) {
            return path
        }
        String base = '/' + mapping
        if(path == base) {
            return ''
        }
        return path.startsWith(base + '/') ? path.substring(base.length()) : null
    }

    /**
     * Whether a path within the application is the url of one of the root paths.
     */
    static boolean isRootPath(String path, Collection<String> rootPaths) {
        return path.length() > 1 && rootPaths != null && rootPaths.contains(path.substring(1))
    }

    /**
     * The asset a path within the application names, under the mapping or as one of the root paths, as a path from
     * the root of the assets.
     * @return the asset's path, or null for a url that names no asset, which the filter leaves to the application
     */
    static String assetPath(String path, String mapping, Collection<String> rootPaths) {
        String underMapping = pathUnderMapping(path, mapping)
        if(underMapping != null) {
            return underMapping
        }
        return isRootPath(path, rootPaths) ? path : null
    }
}
