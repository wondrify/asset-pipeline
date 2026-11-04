package asset.pipeline

import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

class AssetDigestsSpec extends Specification {

    static final String ABC = '900150983cd24fb0d6963f7d28e17f72'

    AssetDigests digests = new AssetDigests()
    AtomicInteger reads = new AtomicInteger()

    private AssetDigests.Source bytes(String content) {
        return { -> reads.incrementAndGet(); new ByteArrayInputStream(content.bytes) } as AssetDigests.Source
    }

    def "an asset is read once, however often its digest is asked for"() {
        when:
        List<String> results = (1..3).collect { digests.digestOf('app.js', 1700000000000L, bytes('abc')) }

        then:
        results.every { it == ABC }
        reads.get() == 1
    }

    def "an asset is read again when its last-modified time has changed, as a file edited in place has"() {
        given:
        String first = digests.digestOf('app.js', 1700000000000L, bytes('abc'))

        when:
        String second = digests.digestOf('app.js', 1700000002000L, bytes('abd'))

        then:
        first == ABC
        second != first
        reads.get() == 2
    }

    def "without a last-modified time, the first digest is kept"() {
        when:
        String first = digests.digestOf('app.js', null, bytes('abc'))
        String second = digests.digestOf('app.js', null, bytes('abd'))

        then:
        second == first
        reads.get() == 1
    }

    def "each name has a digest of its own"() {
        expect:
        digests.digestOf('a.js', null, bytes('abc')) == ABC
        digests.digestOf('b.js', null, bytes('abd')) != ABC
        reads.get() == 2
    }

    def "an asset that cannot be read has no digest, and is read again when next asked for"() {
        given:
        AssetDigests.Source failing = { -> reads.incrementAndGet(); throw new IOException('gone') } as AssetDigests.Source

        expect:
        digests.digestOf('app.js', null, failing) == null
        digests.digestOf('app.js', null, bytes('abc')) == ABC
        reads.get() == 2
    }

    def "the memo starts over when it holds as many digests as it keeps, rather than grow without bound"() {
        given: 'as many names as are kept, each read once'
        (1..AssetDigests.MAXIMUM_SIZE).each { int n -> digests.digestOf("asset-${n}.js", null, bytes('abc')) }

        when: 'one more, and then the first again'
        digests.digestOf('one-more.js', null, bytes('abc'))
        digests.digestOf('asset-1.js', null, bytes('abc'))

        then: 'the first was forgotten with the rest, and read again'
        reads.get() == AssetDigests.MAXIMUM_SIZE + 2
    }
}
