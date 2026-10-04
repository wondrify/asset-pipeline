package asset.pipeline

import groovy.transform.CompileStatic
import java.util.TimeZone
import java.nio.file.InvalidPathException
import java.nio.file.PathMatcher
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.text.SimpleDateFormat

@CompileStatic
public class AssetPipelineResponseBuilder {
	public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz"
	/** Ends the ETag of an asset sent gzipped, whose bytes differ from the ones sent as they are */
	public static final String GZIP_ETAG_SUFFIX = '-gz'
    public String uri
    public String ifNoneMatchHeader
    public String ifModifiedSinceHeader
    public Integer statusCode = 200
	private Date lastModifiedDate
	private final Properties manifest
	private final String method
	private final boolean gzip

	// The digested names of the manifest a builder last read, so a request doesn't scan every entry of it
	private static volatile DigestedNames digestedNames

	// The immutable patterns of the configuration a builder last read, compiled once rather than on each request
	private static volatile ImmutablePatterns immutablePatterns

    public Map<String, String> headers = [:]

    /**
     * @param manifest the manifest the asset was compiled into; the application's unless given, which a class loader
     *        registered with its own assets passes instead
     * @param method the request's method: only GET and HEAD are answered 304, and any other fails its precondition
     * @param gzip whether the gzipped asset is the one sent, which has an ETag of its own (RFC 9110 section 8.8.3.3)
     */
    AssetPipelineResponseBuilder(String uri, String ifNoneMatchHeader = null, String ifModifiedSinceHeader = null, Date lastModifiedDate = null, Properties manifest = AssetPipelineConfigHolder.manifest, String method = 'GET', boolean gzip = false) {
        this.uri = uri
        this.manifest = manifest
        this.method = method
        this.gzip = gzip
        this.ifNoneMatchHeader = ifNoneMatchHeader
        this.ifModifiedSinceHeader = ifModifiedSinceHeader
		this.lastModifiedDate = lastModifiedDate
        boolean digestVersion = isDigestVersion()
		boolean etagChanged = checkETag()
		boolean dateChanged = checkDateChanged()
		if(!etagChanged || !dateChanged) {
			statusCode = conditionFailedStatus()
		}
        // A 304 carries the same cache metadata as a 200 response.
        headers['Vary'] = 'Accept-Encoding'
        if(digestVersion && !uri.endsWith(".html")) {
            headers['Cache-Control'] = 'public, max-age=31536000'
        } else {
            headers['Cache-Control'] = 'no-cache'
        }
    }

	// qvalue = ( "0" [ "." 0*3DIGIT ] ) / ( "1" [ "." 0*3("0") ] ), from RFC 9110
	private static final Pattern QVALUE = ~/0(\.\d{0,3})?|1(\.0{0,3})?/

	/**
	 * Whether a request's Accept-Encoding field lines allow a gzipped response. They form one list,
	 * so a request that sends the field twice is read as one that lists both lines' codings.
	 */
	public static boolean acceptsGzip(Enumeration<String> acceptEncodingLines) {
		return acceptsGzip(acceptEncodingLines == null ? null : Collections.list(acceptEncodingLines).join(','))
	}

	/**
	 * Whether a request's Accept-Encoding allows a gzipped response, read as RFC 9110 lays it out:
	 * codings separated by commas and optional whitespace, matched without regard to case, each
	 * with an optional weight. x-gzip is gzip, and * stands for any coding not listed. A weight of
	 * zero refuses a coding wherever else it is listed, and so does a weight that is not a qvalue,
	 * so a request that can't be understood gets the response as it is.
	 */
	public static boolean acceptsGzip(String acceptEncoding) {
		if(!acceptEncoding) {
			return false
		}
		Boolean gzip = null
		Boolean any = null
		for(String element : acceptEncoding.split(',')) {
			String[] parameters = element.split(';')
			String coding = parameters[0].trim()
			if(coding.equalsIgnoreCase('gzip') || coding.equalsIgnoreCase('x-gzip')) {
				gzip = (gzip == null || gzip) && accepts(parameters)
			} else if(coding == '*') {
				any = (any == null || any) && accepts(parameters)
			}
		}
		return gzip != null ? gzip : (any != null && any)
	}

	private static boolean accepts(String[] parameters) {
		for(int i = 1; i < parameters.length; i++) {
			String[] nameAndValue = parameters[i].split('=', 2)
			if(nameAndValue[0].trim().equalsIgnoreCase('q')) {
				String weight = nameAndValue.length > 1 ? nameAndValue[1].trim() : ''
				return QVALUE.matcher(weight).matches() && new BigDecimal(weight).signum() > 0
			}
		}
		return true
	}

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getCurrentETag() {

        String manifestPath = uri
        if (uri.startsWith('/')) {
            manifestPath = uri.substring(1) //Omit forward slash
        }

        return "\"" + (manifest?.getProperty(manifestPath) ?: manifestPath) + (gzip ? GZIP_ETAG_SUFFIX : '') + "\""
    }

    /**
     * Whether the uri can be cached for a year: the digested name the manifest gives an asset, which changes whenever
     * the asset does, or an asset the {@code immutable} setting says never changes at its url. Any other name,
     * including every name when there is no manifest, may be sent different content tomorrow.
     */
    public boolean isDigestVersion() {
        String manifestPath = uri
        if (uri.startsWith('/')) {
            manifestPath = uri.substring(1) //Omit forward slash
        }
        return (manifest != null && digestedNamesOf(manifest).contains(manifestPath)) || isImmutable(manifestPath)
    }

    private static boolean isImmutable(String path) {
        Object configured = AssetPipelineConfigHolder.config?.get('immutable')
        if(configured == null) {
            return false
        }
        ImmutablePatterns current = immutablePatterns
        if(current == null || !current.configured.is(configured)) {
            current = new ImmutablePatterns(configured)
            immutablePatterns = current
        }
        try {
            return AssetPaths.matchesAny(path, current.matchers)
        } catch(InvalidPathException ignored) {
            return false // a name no file could have
        }
    }

    private static final class ImmutablePatterns {
        final Object configured
        final List<PathMatcher> matchers

        ImmutablePatterns(Object configured) {
            this.configured = configured
            this.matchers = AssetPaths.immutable(configured)
        }
    }

    private static Set<String> digestedNamesOf(Properties manifest) {
        DigestedNames current = digestedNames
        if(current == null || !current.manifest.is(manifest) || current.size != manifest.size()) {
            current = new DigestedNames(manifest)
            digestedNames = current
        }
        return current.names
    }

    private static final class DigestedNames {
        final Properties manifest
        final int size
        final Set<String> names

        DigestedNames(Properties manifest) {
            this.manifest = manifest
            this.size = manifest.size()
            this.names = new HashSet<String>(manifest.stringPropertyNames().collect { String name -> manifest.getProperty(name) })
        }
    }

    public Boolean checkETag() {
        String etagName = getCurrentETag()
        headers["ETag"] = etagName

        if (ifNoneMatchHeader != null && matchesETag(etagName)) {
            statusCode = conditionFailedStatus()
            return false
        }
        return true
    }

    // RFC 9110 section 13.2.2: GET and HEAD are told the asset has not changed, and any other method that its
    // precondition failed. Only If-None-Match can fail another method's, as If-Modified-Since is read for GET and HEAD alone.
    private int conditionFailedStatus() {
        return isGetOrHead() ? 304 : 412
    }

    private boolean isGetOrHead() {
        return method == 'GET' || method == 'HEAD'
    }

    /** Combine field lines without confusing an absent If-None-Match with an empty one. */
    public static String combineIfNoneMatchHeaders(Enumeration<String> lines) {
        return lines != null && lines.hasMoreElements() ? Collections.list(lines).join(',') : null
    }

    // One list member (including empty members). Commas inside a quoted tag belong to the tag, rather than
    // separating list members. The tag may hold any character but a quote, rather than only RFC 9110's
    // etagc, as the tags this builder sends hold the asset's name as it is, a space included.
    private static final Pattern ENTITY_TAG = ~/[ \t]*(?:(?:W\/)?("[^"]*")[ \t]*)?(?:,|\z)/

    private boolean matchesETag(String etag) {
        if (ifNoneMatchHeader.trim() == '*') {
            return true
        }
        String opaqueTag = etag.startsWith('W/') ? etag.substring(2) : etag
        Matcher matcher = ENTITY_TAG.matcher(ifNoneMatchHeader)
        boolean matched = false
        int position = 0
        while (position < ifNoneMatchHeader.length()) {
            matcher.region(position, ifNoneMatchHeader.length())
            if (!matcher.lookingAt()) {
                return false
            }
            matched = matched || matcher.group(1) == opaqueTag
            position = matcher.end()
        }
        return matched
    }

	public Boolean checkDateChanged() {
		SimpleDateFormat sdf = new SimpleDateFormat(HTTP_DATE_FORMAT,Locale.US);
		sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		boolean hasNotChanged = false
		if(lastModifiedDate) {
			headers["Last-Modified"] = getLastModifiedDate(lastModifiedDate)
		}
		// RFC 9110 section 13.1.3: read for GET and HEAD alone, and even an empty If-None-Match suppresses it.
		if (isGetOrHead() && ifNoneMatchHeader == null && ifModifiedSinceHeader && lastModifiedDate) {
			try {
				// Last-Modified is sent in whole seconds, so the date a client sends back is compared in them too
				hasNotChanged = Math.floorDiv(lastModifiedDate.time, 1000L) <= Math.floorDiv(sdf.parse(ifModifiedSinceHeader).time, 1000L)
			} catch (Exception e) {
				//Ignore this just a parse error
			}
		}
		return !hasNotChanged
	}

	private String getLastModifiedDate(Date date) {
		SimpleDateFormat sdf = new SimpleDateFormat(HTTP_DATE_FORMAT,Locale.US);
		sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		String lastModifiedDateTimeString = sdf.format(new Date())
		try {
			lastModifiedDateTimeString = sdf.format(date)
		} catch (Exception e) {
			//Ignore
		}
		return lastModifiedDateTimeString
	}
}
