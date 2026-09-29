package asset.pipeline.utils

import groovy.transform.CompileStatic

/**
 * An OutputStream capable of writing to a collection of output streams underneath simultaneously
 *
 * @author David Estes
 */
@CompileStatic
public class MultiOutputStream extends OutputStream{
	private final Collection<OutputStream> streams;

    /**
     * Constructor method for creating the input Stream
     * @param streams a list or collection of output streams
     */
	public MultiOutputStream(Collection<OutputStream> streams) {
		if (streams == null)
			throw new NullPointerException();
		this.streams = streams
	}

	@Override
	public void write(int b) throws IOException {
		streams.each { OutputStream stream ->
			stream.write(b)
		}
	}

	@Override
	public void write(byte[] b) throws IOException {
		streams.each { OutputStream stream ->
			stream.write(b)
		}
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		streams.each { OutputStream stream ->
			stream.write(b, off, len)
		}
	}

	@Override
	public void flush() throws IOException {
		streams.each { OutputStream stream ->
			stream.flush()
		}
	}
}