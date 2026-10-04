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
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
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
     * a character a url must encode, which containers match inconsistently, one under {@code WEB-INF} or
     * {@code META-INF}, which containers never route to a filter, and one under the mapping, where the asset is already
     * served. A blank entry, as a trailing comma leaves, is skipped.
     * @param configured a collection or array of paths, a comma separated string of them (the form a system property
     *        gives a list), or null
     * @param mapping the url the filter serves assets under, without slashes; empty or null when it serves them at
     *        the root, which leaves nothing for an entry to be under
     * @return the paths without a leading slash, in the order configured, each once
     * @throws IllegalArgumentException for an entry that does not name exactly one asset outside the mapping
     */
    static List<String> rootPaths(Object configured, String mapping) {
        Collection<?> entries = entriesOf(configured, 'rootPaths', 'asset paths')
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
            String firstSegment = path.split('/')[0]
            if(firstSegment.equalsIgnoreCase('WEB-INF') || firstSegment.equalsIgnoreCase('META-INF')) {
                throw new IllegalArgumentException("rootPaths entry '${entry}' is under /${firstSegment}, which a servlet container never routes to a filter")
            }
            if(mapping && (path == mapping || path.startsWith(mapping + '/'))) {
                throw new IllegalArgumentException("rootPaths entry '${entry}' is under /${mapping}, where its asset is already served")
            }
            paths << path
        }
        return new ArrayList<String>(paths)
    }

    // A list setting as configuration gives it: a collection or array, or a comma separated string, the form a system
    // property gives a list
    private static Collection<?> entriesOf(Object configured, String setting, String what) {
        if(configured == null) {
            return []
        }
        if(configured instanceof CharSequence) {
            return configured.toString().split(',').toList()
        }
        if(configured instanceof Collection) {
            return (Collection<?>) configured
        }
        if(configured instanceof Object[]) {
            return Arrays.asList((Object[]) configured)
        }
        throw new IllegalArgumentException("${setting} must be a list of ${what}, not a ${configured.getClass().name}")
    }

    /**
     * The assets read from the {@code immutable} setting: patterns of assets whose url never changes content although
     * it has no digest in its name, such as a versioned webjar, so that they are cached for a year as a digested
     * asset is. Each is a pattern as {@code includes} and {@code excludes} take, matched against the asset's path.
     * @param configured a collection or array of patterns, a comma separated string of them, or null
     * @return a matcher for each pattern, blank ones skipped
     * @throws IllegalArgumentException for a pattern that cannot be read
     */
    static List<PathMatcher> immutable(Object configured) {
        List<PathMatcher> matchers = []
        for(Object entry in entriesOf(configured, 'immutable', 'asset path patterns')) {
            String pattern = entry == null ? '' : entry.toString().trim()
            if(!pattern) {
                continue
            }
            try {
                matchers.addAll(pathMatchers(pattern))
            } catch(IllegalArgumentException e) {
                throw new IllegalArgumentException("immutable pattern '${pattern}' cannot be read: ${e.message}", e)
            }
        }
        return matchers
    }

    /**
     * The matchers for one asset path pattern, as {@link AssetHelper#isFileMatchingPatterns} reads it: a glob, or a
     * regular expression after a {@code regex:} prefix. A glob with a {@code ** /} also matches with it left out, so
     * that a pattern for every directory matches the top one too.
     */
    static List<PathMatcher> pathMatchers(String pattern) {
        String syntax = 'glob'
        String expression = pattern
        if(pattern.startsWith('regex:')) {
            syntax = 'regex'
            expression = pattern.substring(6)
        } else if(pattern.startsWith('glob:')) {
            expression = pattern.substring(5)
        }
        List<PathMatcher> matchers = [FileSystems.getDefault().getPathMatcher("${syntax}:${expression}".toString())]
        if(syntax == 'glob' && expression.contains('**/')) {
            matchers << FileSystems.getDefault().getPathMatcher("${syntax}:${expression.replace('**/', '')}".toString())
        }
        return matchers
    }

    /** Whether an asset's path matches any of the matchers. */
    static boolean matchesAny(String path, Collection<PathMatcher> matchers) {
        if(!matchers) {
            return false
        }
        Path asPath = Paths.get(path)
        return matchers.any { PathMatcher matcher -> matcher.matches(asPath) }
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
     * The path a request names within its application, read as a container reads a path before it maps the request:
     * without path parameters such as {@code ;jsessionid=...}, decoded, with each run of slashes as one, and without
     * {@code .} and {@code ..} segments, which never climb above the root. The context path is read the same way and
     * then taken off as whole segments, so it is found whether the container gives it as the client wrote it, path
     * parameters included, as Tomcat does, or in its canonical form, as Jetty does.
     * @return the path, beginning with a slash
     */
    static String pathWithinContext(String requestUri, String contextPath) {
        String path = normalized(requestUri)
        String context = contextPath ? normalized(contextPath) : '/'
        if(context != '/') {
            if(path == context) {
                return '/'
            }
            if(path.startsWith(context + '/')) {
                return path.substring(context.length())
            }
        }
        return path
    }

    private static String normalized(String path) {
        String result = path.indexOf(';') < 0 ? path : PATH_PARAMETERS.matcher(path).replaceAll('')
        result = decoded(result)
        if(result.contains('//')) {
            result = REPEATED_SLASHES.matcher(result).replaceAll('/')
        }
        return withoutDotSegments(result.startsWith('/') ? result : '/' + result)
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

    // RFC 3986 remove_dot_segments for a path that begins with a slash; .. at the root stays at the root
    private static String withoutDotSegments(String path) {
        if(!path.contains('/.')) {
            return path
        }
        String[] parts = path.split('/', -1)
        List<String> segments = []
        for(int i = 1; i < parts.length; i++) {
            String segment = parts[i]
            boolean last = i == parts.length - 1
            if(segment == '..') {
                if(segments) {
                    segments.remove(segments.size() - 1)
                }
                if(last) {
                    segments << ''
                }
            } else if(segment == '.') {
                if(last) {
                    segments << ''
                }
            } else {
                segments << segment
            }
        }
        return '/' + segments.join('/')
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
     * The asset a request names, under the mapping or as one of the root paths, as a path from the root of the assets.
     * A root path is answered for {@code GET} and {@code HEAD} alone: the application may answer its url itself, so a
     * request with any other method is left to it, with its body unread.
     * @param method the request's method
     * @param path the request's path within the application, as {@link #pathWithinContext} gives it
     * @return the asset the request names, or null for a request the filter leaves to the application
     */
    static AssetUrl assetUrl(String method, String path, String mapping, Collection<String> rootPaths) {
        String underMapping = pathUnderMapping(path, mapping)
        if(underMapping != null) {
            return new AssetUrl(underMapping, false)
        }
        if(isRootPath(path, rootPaths) && (method == 'GET' || method == 'HEAD')) {
            return new AssetUrl(path, true)
        }
        return null
    }

    /**
     * An asset a request names: its path from the root of the assets, and whether it was asked for at one of the
     * root paths rather than under the mapping.
     */
    @CompileStatic
    static final class AssetUrl {

        final String path

        final boolean rootPath

        AssetUrl(String path, boolean rootPath) {
            this.path = path
            this.rootPath = rootPath
        }
    }
}
