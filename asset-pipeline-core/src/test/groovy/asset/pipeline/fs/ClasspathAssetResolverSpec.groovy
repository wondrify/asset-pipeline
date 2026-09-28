package asset.pipeline.fs

import asset.pipeline.CssAssetFile
import asset.pipeline.GenericAssetFile
import asset.pipeline.JsEs6AssetFile
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Created by davydotcom on 4/21/16.
 */
class ClasspathAssetResolverSpec extends Specification {

    @TempDir
    File tempDir

    void "should be able to fetch generic files with seperated extension"() {
        given:
        def resolver = new ClasspathAssetResolver('application','META-INF/assets','META-INF/assets.list')
        when:
        def file = resolver.getAsset('asset-test/grails_logo',null,'png')
        then:
        file instanceof GenericAssetFile
    }

    void "should not resolve an asset if no extension or content type is specified"() {
        given:
        def resolver = new ClasspathAssetResolver('application','META-INF/assets','META-INF/assets.list')
        when:
        def file = resolver.getAsset('asset-test/lib')
        then:
        file == null
    }

    void "should be able to fetch generic files without seperated extension"() {
        given:
        def resolver = new ClasspathAssetResolver('application','META-INF/assets','META-INF/assets.list')
        when:
        def file = resolver.getAsset('asset-test/grails_logo.png')
        then:
        file instanceof GenericAssetFile
    }

    void "should be able to resolve js files based on a content-type"() {
        given:
        def resolver = new ClasspathAssetResolver('application','META-INF/assets','META-INF/assets.list')
        when:
        def file = resolver.getAsset('asset-test/lib','application/javascript')
        then:
        file instanceof JsEs6AssetFile
    }

    void "should be able to resolve css files based on a content-type"() {
        given:
        def resolver = new ClasspathAssetResolver('application','META-INF/assets','META-INF/assets.list')
        when:
        def file = resolver.getAsset('asset-test/nested/filec','text/css')
        then:
        file instanceof CssAssetFile
    }

    void "should be able to fetch files recursively by content-type"() {
        given:
        def resolver = new ClasspathAssetResolver('application','META-INF/assets','META-INF/assets.list')
        when:
        def files = resolver.getAssets('asset-test','application/javascript')
        then:
        files?.size() == 3
    }

    void "should be able to fetch files recursively by content-type with relative baseFile"() {
        given:
        def resolver = new ClasspathAssetResolver('application','META-INF/assets','META-INF/assets.list')
        when:
        def relativeFile = resolver.getAsset('asset-test/lib','application/javascript')
        println "Fetched Relative File ${relativeFile?.name}"
        def files = resolver.getAssets('.','application/javascript', null, true, relativeFile)
        then:
        files?.size() == 3
    }

    void "should prefer .js file over .mjs file when explicitly requesting .js extension from classpath"() {
        given:
        def resolver = new ClasspathAssetResolver('application','META-INF/assets','META-INF/assets.list')

        when:
        def file = resolver.getAsset('asset-test/lib.js', 'application/javascript')

        then:
        file != null
        file.name == 'lib.js'
        file.path.endsWith('.js')
    }

    void "should prefer .mjs file over .js file when explicitly requesting .mjs extension from classpath"() {
        given:
        def resolver = new ClasspathAssetResolver('application','META-INF/assets','META-INF/assets.list')

        when:
        def file = resolver.getAsset('asset-test/lib.mjs', 'application/javascript')

        then:
        file != null
        file.name == 'lib.mjs'
        file.path.endsWith('.mjs')
    }

    void "should prefer .mjs over .js when no extension is specified from classpath"() {
        given:
        def resolver = new ClasspathAssetResolver('application','META-INF/assets','META-INF/assets.list')
        when:
        def file = resolver.getAsset('asset-test/lib', 'application/javascript')
        then:
        file != null
        file.name == 'lib.mjs'
        file.path.endsWith('.mjs')
    }

    void "a wildcard stands for one directory in any jar or directory on the classpath, highest version first: #path"() {
        given: 'the older versions in the jar and directory first on the classpath'
        URL[] classpath = [
            TestJars.write(new File(tempDir, 'old.jar'), ['webjars/marked/4.3.0/lib/marked.js', 'webjars/chart/9.0.0/chart.js', 'webjars/deep/1.0/nested/lib/x.js']).toURI().toURL(),
            TestJars.write(new File(tempDir, 'new.jar'), ['webjars/marked/5.1.2/lib/marked.js', 'vendor/.backup/only-in-backup.js']).toURI().toURL(),
            directory('exploded', ['webjars/chart/10.0.0/chart.js'])
        ]
        def resolver = new ClasspathAssetResolver('classpath', 'META-INF/resources', null, new URLClassLoader(classpath, (ClassLoader) null))
        when:
        def file = resolver.getAsset(path, 'application/javascript', 'js')
        then:
        file?.path == resolved
        where:
        path                            | resolved
        'webjars/marked/%/lib/marked'   | 'webjars/marked/5.1.2/lib/marked.js'
        'webjars/chart/%/chart'         | 'webjars/chart/10.0.0/chart.js'
        '%/marked/%/lib/marked'         | 'webjars/marked/5.1.2/lib/marked.js'
        'webjars/%/%/lib/marked'        | 'webjars/marked/5.1.2/lib/marked.js'
        'webjars/deep/%/lib/x'          | null
        'webjars/deep/%/nested/lib/x'   | 'webjars/deep/1.0/nested/lib/x.js'
        'vendor/%/only-in-backup'       | null
        '/webjars/marked/%/lib/marked'  | 'webjars/marked/5.1.2/lib/marked.js'
    }

    void "%% stands for any number of directories across the classpath, the fewest first, then the highest version: #path"() {
        given: 'the shallow jQuery and the newer marked in the second jar, the bundled copy and the rest in the first'
        URL[] classpath = [
            TestJars.write(new File(tempDir, 'first.jar'), ['webjars/other/1.0/vendor/jquery/dist/jquery.js', 'webjars/marked/4.3.0/lib/marked.js', 'webjars/.cache/x/lib/hidden.js']).toURI().toURL(),
            TestJars.write(new File(tempDir, 'second.jar'), ['webjars/jquery/3.7.1/dist/jquery.js', 'webjars/marked/5.1.2/lib/marked.js']).toURI().toURL(),
            directory('exploded', ['webjars/nest/a/b/c/deep.js'])
        ]
        def resolver = new ClasspathAssetResolver('classpath', 'META-INF/resources', null, new URLClassLoader(classpath, (ClassLoader) null))
        when:
        def file = resolver.getAsset(path, 'application/javascript', 'js')
        then:
        file?.path == resolved
        where:
        path                                  | resolved
        'webjars/jquery/3.7.1/%%/dist/jquery' | 'webjars/jquery/3.7.1/dist/jquery.js'
        'webjars/jquery/%%/dist/jquery'       | 'webjars/jquery/3.7.1/dist/jquery.js'
        'webjars/%%/dist/jquery'              | 'webjars/jquery/3.7.1/dist/jquery.js'
        'webjars/**/dist/jquery'              | 'webjars/jquery/3.7.1/dist/jquery.js'
        '%%/jquery'                           | 'webjars/jquery/3.7.1/dist/jquery.js'
        'webjars/%%/lib/marked'               | 'webjars/marked/5.1.2/lib/marked.js'
        'webjars/%%/deep'                     | 'webjars/nest/a/b/c/deep.js'
        'webjars/%%/hidden'                   | null
        'webjars/%/dist/jquery'               | null
        '/webjars/%%/lib/marked'              | 'webjars/marked/5.1.2/lib/marked.js'
    }

    // A classpath directory holding each path under META-INF/resources
    private URL directory(String name, List<String> paths) {
        File root = new File(tempDir, name)
        for(String path in paths) {
            File file = new File(root, "META-INF/resources/${path}")
            file.parentFile.mkdirs()
            file.text = "// ${path}"
        }
        return root.toURI().toURL()
    }

}
