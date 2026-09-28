package asset.pipeline.processors;

/**
* When a Minify Closure parser error is reached this exception is thrown.
* @author David Estes
*/
public class MinifyException extends Exception {
	public MinifyException(String message) {
        super(message);
    }
}