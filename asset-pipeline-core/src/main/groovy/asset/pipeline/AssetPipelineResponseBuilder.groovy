package asset.pipeline

import groovy.transform.CompileStatic
import java.util.TimeZone
import java.text.SimpleDateFormat

@CompileStatic
public class AssetPipelineResponseBuilder {
	public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz"
    public String uri
    public String ifNoneMatchHeader
    public String ifModifiedSinceHeader
    public Integer statusCode = 200
	private Date lastModifiedDate

    public Map<String, String> headers = [:]

    AssetPipelineResponseBuilder(String uri, String ifNoneMatchHeader = null, String ifModifiedSinceHeader = null, Date lastModifiedDate = null) {
        this.uri = uri
        this.ifNoneMatchHeader = ifNoneMatchHeader
        this.ifModifiedSinceHeader = ifModifiedSinceHeader
		this.lastModifiedDate = lastModifiedDate
        boolean digestVersion = isDigestVersion()
		if(!checkDateChanged()) {
			statusCode = 304
		} else if (checkETag()) {
            headers['Vary'] = 'Accept-Encoding'
            if(digestVersion && !uri.endsWith(".html")) {
                headers['Cache-Control'] = 'public, max-age=31536000'    
            } else {
                headers['Cache-Control'] = 'no-cache'
            }
            
        }
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

        Properties manifest = AssetPipelineConfigHolder.manifest
        return "\"" + (manifest?.getProperty(manifestPath) ?: manifestPath) + "\""
    }

    public boolean isDigestVersion() {
        String manifestPath = uri
        if (uri.startsWith('/')) {
            manifestPath = uri.substring(1) //Omit forward slash
        }
        Properties manifest = AssetPipelineConfigHolder.manifest
        
        return manifest?.getProperty(manifestPath,null) ? false : true
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
