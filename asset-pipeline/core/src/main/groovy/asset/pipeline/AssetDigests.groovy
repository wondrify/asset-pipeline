package asset.pipeline

import groovy.transform.CompileStatic

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * The digests of the assets a filter serves under a name without one, which validate them: see
 * {@link AssetPipelineResponseBuilder#getCurrentETag()}. Each is computed from the asset's bytes when it is first
 * asked for and kept, rather than computed on every request, and computed again when the asset's last-modified
 * time has changed, as a file edited in place has.
 *
 * <p>At most {@value #MAXIMUM_SIZE} are kept: the memo starts over when it holds that many, so that urls crafted
 * to name one asset many ways, as a case-insensitive file system allows, cannot grow it without bound. Nothing is
 * lost then but the work of reading an asset again.
 */
@CompileStatic
class AssetDigests {

    static final int MAXIMUM_SIZE = 10_000

    /**
     * Opens an asset's bytes. It declares the exception a resource throws so that a closure coerced to it throws
     * the exception as itself, rather than wrapped as a proxy wraps one its interface does not declare.
     */
    @FunctionalInterface
    static interface Source {
        InputStream open() throws IOException
    }

    private final ConcurrentMap<String, Digest> digests = new ConcurrentHashMap<String, Digest>()

    /**
     * @param name the name the asset is served under
     * @param lastModified when the asset was last modified, or null when that is not known, which keeps the first digest
     * @param bytes opens the asset's bytes, read when no digest is kept for it
     * @return the digest, as {@link AssetHelper#getByteDigest(InputStream)} computes it, or null when the asset
     *         cannot be read, which serving it will report
     */
    String digestOf(String name, Long lastModified, Source bytes) {
        Digest current = digests.get(name)
        if(current == null || current.lastModified != lastModified) {
            String digest
            try {
                digest = AssetHelper.getByteDigest(bytes.open())
            } catch(IOException ignored) {
                return null
            }
            current = new Digest(lastModified, digest)
            if(digests.size() >= MAXIMUM_SIZE) {
                digests.clear()
            }
            digests.put(name, current)
        }
        return current.digest
    }

    private static final class Digest {
        final Long lastModified
        final String digest

        Digest(Long lastModified, String digest) {
            this.lastModified = lastModified
            this.digest = digest
        }
    }
}
