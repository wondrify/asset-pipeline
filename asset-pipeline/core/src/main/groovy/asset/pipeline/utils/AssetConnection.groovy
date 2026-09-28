package asset.pipeline.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import groovy.transform.CompileStatic;
import asset.pipeline.AssetFile;
import asset.pipeline.AssetHelper;

@CompileStatic
public class AssetConnection extends URLConnection
{
	private boolean connected = false;

	public AssetConnection(URL u) {
		super(u);
	}

	@Override
	public void connect()  throws IOException {
		connected = true;
		return;
	}

	@Override
	public Object getContent() throws IOException {
	throw new UnsupportedOperationException(
		"The getContent() method is not supported"
			);
	}

	@Override
	public InputStream getInputStream()
	throws IOException {
		AssetFile newFile = AssetHelper.fileForUri( url.path, null, null, null)
		if(newFile) {
			return newFile.inputStream
		} else {
			throw new IOException("File not Found")
		}
	}
}
