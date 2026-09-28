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
package asset.pipeline.utils.net;


import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * @author Ross Goldberg
 */
public final class Urls {

	private static final Map<String, Integer> DEFAULT_PORT_BY_SCHEME = new ConcurrentHashMap<>();

	public static final String  URL_SCHEME_SANS_COLON_REGEX   = "\\p{L}[\\p{L}\\d+-.]*+";

	/**
	 * matches text that starts with either {@code "/"} or a <a href="https://tools.ietf.org/html/std66">URI scheme</a> followed by {@code ":/"}
	 */
	public static final Pattern ABSOLUTE_URL_PATTERN          = Pattern.compile("^(?:" + URL_SCHEME_SANS_COLON_REGEX + ":)?/");

	/**
	 * matches text that starts with either {@code "//"} or a <a href="https://tools.ietf.org/html/std66">URI scheme</a> followed by {@code "://"}
	 */
	public static final Pattern HAS_AUTHORITY_URL_PATTERN     = Pattern.compile("^(?:" + URL_SCHEME_SANS_COLON_REGEX + ":)?//");

	/**
	 * matches text that starts with a <a href="https://tools.ietf.org/html/std66">URI scheme</a> including the colon at the start of text
	 */
	public static final Pattern URL_SCHEME_WITH_COLON_PATTERN = Pattern.compile("^" + URL_SCHEME_SANS_COLON_REGEX + ":");

	/**
	 * matches text that starts with a <a href="https://tools.ietf.org/html/std66">URI scheme</a> before the colon at the start of text
	 */
	public static final Pattern URL_SCHEME_SANS_COLON_PATTERN = Pattern.compile("^" + URL_SCHEME_SANS_COLON_REGEX + "(?=:)");


	public static final String  URL_COMPONENT_SCHEME   = "scheme";
	public static final String  URL_COMPONENT_USER     = "user";
	public static final String  URL_COMPONENT_PASSWORD = "password";
	public static final String  URL_COMPONENT_HOST     = "host";
	public static final String  URL_COMPONENT_PORT     = "port";
	public static final String  URL_COMPONENT_PATH     = "path";
	public static final String  URL_COMPONENT_QUERY    = "query";
	public static final String  URL_COMPONENT_FRAGMENT = "fragment";

	/**
	 * Named capture groups for URL components
	 *
	 * Does not enforce that:
	 * <ul>
	 *     <li>a port can only be an unsigned 16-bit number</li>
	 *     <li>a [/?#] must follow any port</li>
	 * </ul>
	 */
	public static final Pattern URL_COMPONENT_PATTERN =
		Pattern.compile(
			"^(?:(?<"         + URL_COMPONENT_SCHEME   + ">" + URL_SCHEME_SANS_COLON_REGEX + "):)?" +
			"(?://" +
				"(?:" +
					"(?<"     + URL_COMPONENT_USER     + ">[^#?/@:]*+)" +
					"(?::(?<" + URL_COMPONENT_PASSWORD + ">[^#?/@]*+))?" +
				"@)?" +
				"(?<"         + URL_COMPONENT_HOST     + ">[^#?/:]*+)" +
				"(?::(?<"     + URL_COMPONENT_PORT     + ">\\d*+))?" +
			")?" +
			"(?<"             + URL_COMPONENT_PATH     + ">[^#?]*+)" +
			"(?:\\?(?<"       + URL_COMPONENT_QUERY    + ">[^#]*+))?" +
			"(?:#(?<"         + URL_COMPONENT_FRAGMENT + ">.*+))?"
		)
	;

	public static StringBuilder concatenateUrl(
		final String scheme,
		final String user,
		final String password,
		final String host,
		final String port,
		final String path,
		final String query,
		final String fragment
	) {
		return concatenateUrl(scheme, user, password, host, port, path, query, fragment, 0);
	}

	public static StringBuilder concatenateUrl(
		final String scheme,
		final String user,
		final String password,
		final String host,
		final String port,
		final String path,
		final String query,
		final String fragment,
		final int    extraLength
	) {
		final StringBuilder urlSb =
			new StringBuilder(
				length(scheme,   1) +
				length(user,     3) +
				length(password, 4) +
				length(host,     2) +
				length(port,     3) +
				length(path,     1) +
				length(query,    1) +
				length(fragment, 1) +
				extraLength
			)
		;
		if (scheme != null) {
			urlSb.append(scheme).append(":");
		}
		if (
			user     != null ||
			password != null ||
			host     != null ||
			port     != null
		) {
			urlSb.append("//");
			if (
				user     != null ||
				password != null
			) {
				append(urlSb, user);
				append(urlSb, ':', password);
				urlSb.append('@');
			}
			append(urlSb,      host);
			append(urlSb, ':', port);
			if (path != null) {
				if (path.charAt(0) != '/') {
					urlSb.append('/');
				}
				urlSb.append(path);
			}
		}
		else {
			append(urlSb, path);
		}
		append(urlSb, '?', query);
		append(urlSb, '#', fragment);
		return urlSb;
	}

	private static int length(final CharSequence cs, final int extraLength) {
		return
			cs == null
				? 0
				: cs.length() + extraLength
			;
	}

	private static StringBuilder append(final StringBuilder sb, final String s) {
		return
			s == null
				? sb
				: sb.append(s)
		;
	}

	private static StringBuilder append(final StringBuilder sb, final char prefix, final String s) {
		return
			s == null
				? sb
				: sb.append(prefix).append(s)
		;
	}


	public static int getDefaultPort(final String scheme) {
		Integer port = DEFAULT_PORT_BY_SCHEME.get(scheme);
		if (port == null) {
			try {
				port = new URL(scheme, "localhost", "").getDefaultPort();
				DEFAULT_PORT_BY_SCHEME.put(scheme, port);
			}
			catch (final MalformedURLException ex) {
				throw new IllegalArgumentException(scheme + " is an unknown scheme", ex);
			}
		}
		return port;
	}


	/**
	 * {@code url} starts with either {@code "/"} or a <a href="https://tools.ietf.org/html/std66">URI scheme</a> followed by {@code ":/"}
	 */
	public static boolean isAbsolute(final String url) {
		return ABSOLUTE_URL_PATTERN.matcher(url).find();
	}

	/**
	 * {@code url} does not start with either {@code "/"} or a <a href="https://tools.ietf.org/html/std66">URI scheme</a> followed by {@code ":/"}
	 */
	public static boolean isRelative(final String url) {
		return ! isAbsolute(url);
	}

	/**
	 * {@code url} starts with either {@code "//"} or a <a href="https://tools.ietf.org/html/std66">URI scheme</a> followed by {@code "://"}
	 */
	public static boolean hasAuthority(final String url) {
		return HAS_AUTHORITY_URL_PATTERN.matcher(url).find();
	}


	/**
	 * @return <a href="https://tools.ietf.org/html/std66">URI scheme</a>, including trailing colon, if present;
	 * otherwise, returns {@code null}
	 */
	public static String getSchemeWithColon(final String url) {
		return getSchemeWithColon(url, null);
	}

	/**
	 * @return <a href="https://tools.ietf.org/html/std66">URI scheme</a>, including trailing colon, if present;
	 * otherwise, returns {@code defaultScheme}
	 */
	public static String getSchemeWithColon(final String url, final String defaultScheme) {
		final Matcher m = URL_SCHEME_WITH_COLON_PATTERN.matcher(url);
		return
			m.find()
				? m.group()
				: defaultScheme
		;
	}

	/**
	 * @return the substring of {@code url} after any <a href="https://tools.ietf.org/html/std66">URI scheme</a>, if scheme is present;
	 * otherwise, returns {@code url}
	 */
	public static String getUrlSansScheme(final String url) {
		return URL_SCHEME_WITH_COLON_PATTERN.matcher(url).replaceFirst("");
	}


	public static String withScheme(final String url, final Object o) {
		return
			o == null
				? url
				: o instanceof Boolean
					? withScheme(url, ((Boolean) o).booleanValue())
					: withScheme(url, o.toString())
		;
	}

	public static String withScheme(final String url, final boolean retainScheme) {
		return
			retainScheme
				? url
				: getUrlSansScheme(url)
		;
	}

	public static String withScheme(final String url, final String scheme) {
		return scheme + ':' + getUrlSansScheme(url);
	}


	private Urls() {}
}
