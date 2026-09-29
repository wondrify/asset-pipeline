package asset.pipeline.utils;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import groovy.transform.CompileStatic

@CompileStatic
public class Handler extends URLStreamHandler
{
	@Override
	protected URLConnection openConnection(URL u)
	throws IOException
	{
		return new AssetConnection(u);
	}
}
