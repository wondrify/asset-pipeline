package asset.pipeline

import groovy.transform.CompileStatic
import java.util.TimeZone
import java.nio.file.InvalidPathException
import java.nio.file.PathMatcher
import java.util.regex.Pattern
import java.text.SimpleDateFormat

@CompileStatic
public class AssetPipelineResponseBuilder {
	public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz"
    public String uri
    public String ifNoneMatchHeader
    public String ifModifiedSinceHeader
    public Integer statusCode = 200
	private Date lastModifiedDate
	private final Properties manifest

	// The digested names of the manifest a builder last read, so a request doesn't scan every entry of it
	private static volatile DigestedNames digestedNames

	// The immutable patterns of the configuration a builder last read, compiled once rather than on each request
	private static volatile ImmutablePatterns immutablePatterns

    public Map<String, String> headers = [:]

    /**
     * @param manifest the manifest the asset was compiled into; the application's unless given, which a class loader
     *        registered with its own assets passes instead
     */
    AssetPipelineResponseBuilder(String uri, String ifNoneMatchHeader = null, String ifModifiedSinceHeader = null, Date lastModifiedDate = null, Properties manifest = AssetPipelineConfigHolder.manifest) {
        this.uri = uri
        this.manifest = manifest
        this.ifNoneMatchHeader = ifNoneMatchHeader
        this.ifModifiedSinceHeader = ifModifiedSinceHeader
		this.lastModifiedDate = lastModifiedDate
        boolean digestVersion = isDigestVersion()
		if(!checkDateChanged()) {
			statusCode = 304
		}
        // A 304 carries the same cache metadata as a 200 response.
        checkETag()
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

        return "\"" + (manifest?.getProperty(manifestPath) ?: manifestPath) + "\""
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

        if (ifNoneMatchHeader && ifNoneMatchHeader == etagName) {
            statusCode = 304
            return false
        }
        return true
    }

	public Boolean checkDateChanged() {
		SimpleDateFormat sdf = new SimpleDateFormat(HTTP_DATE_FORMAT,Locale.US);
		sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		boolean hasNotChanged = false
		if(lastModifiedDate) {
			headers["Last-Modified"] = getLastModifiedDate(lastModifiedDate)
		}
		if (ifModifiedSinceHeader && lastModifiedDate) {
			try {
				hasNotChanged = lastModifiedDate <= sdf.parse(ifModifiedSinceHeader)
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
