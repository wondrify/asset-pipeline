/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package asset.pipeline.gradle

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

import groovy.io.FileType
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class AssetPipelinePluginSpec extends Specification {

    @TempDir
    File projectDirectory

    void 'Spring Boot #bootVersion preserves assets through compact extraction with developmentRuntime=#developmentRuntime'() {
        given:
        def runner = runner()
        writeApplication()
        def plugins = ["id 'org.springframework.boot' version '$bootVersion'", "id 'cloud.wondrify.asset-pipeline'"]
        if (developmentRuntime) {
            plugins = plugins.reverse()
        }
        write('build.gradle', """
            plugins {
                id 'java'
                id 'war'
                ${plugins.join('\n')}
            }

            ${assetRuntime(runner)}

            dependencies {
                implementation platform('org.springframework.boot:spring-boot-dependencies:$bootVersion')
                implementation 'org.springframework.boot:spring-boot-starter'
            }
            assets {
                developmentRuntime = $developmentRuntime
                minifyCss = false
                minifyJs = false
                maxThreads = 1
                jarTaskName = 'customBootJar'
            }
            springBoot {
                mainClass = 'example.Application'
            }
            tasks.named('bootJar') {
                archiveFileName = 'application.jar'
            }
            tasks.named('bootWar') {
                archiveFileName = 'application.war'
            }
            tasks.named('jar') {
                archiveFileName = 'plain.jar'
            }
            tasks.named('war') {
                archiveFileName = 'plain.war'
            }
            tasks.register('customBootJar', org.springframework.boot.gradle.tasks.bundling.BootJar) {
                archiveFileName = 'custom.jar'
                mainClass = 'example.Application'
                classpath = sourceSets.main.runtimeClasspath
                targetJavaVersion = java.targetCompatibility
            }
        """.stripIndent())

        when:
        runner.withArguments('bootJar', 'customBootJar', 'bootWar', 'jar', 'war', '--stacktrace', '--max-workers=2').build()

        then: 'both default and custom BootJar tasks package assets as application resources'
        ['application.jar', 'custom.jar'].each { String name ->
            def archive = new File(projectDirectory, "build/libs/$name")
            assert contains(archive, 'BOOT-INF/classes/assets/manifest.properties')
            assert !contains(archive, 'assets/manifest.properties')
            assert runJava(projectDirectory, '-jar', archive.absolutePath).contains('ASSETS_OK')
            assertCompactExtraction(archive, name)
        }

        and: 'ordinary JAR and WAR packaging keeps its existing resource locations'
        contains(new File(projectDirectory, 'build/libs/plain.jar'), 'assets/manifest.properties')
        contains(new File(projectDirectory, 'build/libs/plain.war'), developmentRuntime ? 'assets/manifest.properties' : 'WEB-INF/classes/assets/manifest.properties')
        contains(new File(projectDirectory, 'build/libs/application.war'), developmentRuntime ? 'assets/manifest.properties' : 'WEB-INF/classes/assets/manifest.properties')
        runJava(projectDirectory, '-jar', new File(projectDirectory, 'build/libs/application.war').absolutePath).contains('ASSETS_OK')

        where:
        bootVersion | developmentRuntime
        '3.5.10'    | true
        '3.5.10'    | false
        '4.1.1'     | true
        '4.1.1'     | false
    }

    void 'ordinary and custom JAR tasks work without Spring Boot with developmentRuntime=#developmentRuntime'() {
        given:
        def runner = runner()
        writeApplication()
        write('build.gradle', """
            plugins {
                id 'java'
                id 'cloud.wondrify.asset-pipeline'
            }

            ${assetRuntime(runner)}

            assets {
                developmentRuntime = $developmentRuntime
                minifyCss = false
                minifyJs = false
                maxThreads = 1
                jarTaskName = 'customJar'
            }
            tasks.named('jar') {
                archiveFileName = 'plain.jar'
            }
            tasks.register('customJar', Jar) {
                archiveFileName = 'custom.jar'
                from(sourceSets.main.output)
            }
        """.stripIndent())

        when:
        runner.withArguments('jar', 'customJar', '--stacktrace', '--max-workers=2').build()

        then:
        ['plain.jar', 'custom.jar'].each { String name ->
            def archive = new File(projectDirectory, "build/libs/$name")
            assert contains(archive, 'assets/manifest.properties')
            assert runJava(projectDirectory, '-cp', archive.absolutePath, 'example.Application').contains('ASSETS_OK')
        }

        where:
        developmentRuntime << [true, false]
    }

    void 'assetCompile reuses the configuration cache entry and compiles the assets again'() {
        given:
        def runner = runner()
        writeApplication()
        write('src/assets/stylesheets/ignored.txt', 'not compiled\n')
        write('shared-assets/javascripts/shared.js', 'var shared = 1;\n')
        write('build.gradle', """
            plugins {
                id 'java'
                id 'cloud.wondrify.asset-pipeline'
            }

            ${assetRuntime(runner)}

            assets {
                minifyCss = false
                minifyJs = false
                maxThreads = 1
                excludes = ['**/*.txt']
                configOptions = [commonJs: false]
                forkOptions = objects.newInstance(org.gradle.api.tasks.compile.GroovyForkOptions)
                forkOptions.memoryMaximumSize = '256m'
                forkOptions.jvmArgs = ['-Dasset.pipeline.test=true']
                from 'shared-assets'
            }
        """.stripIndent())

        when: 'the configuration cache entry is stored'
        BuildResult stored = runner.withArguments('assetCompile', '--stacktrace').build()

        then:
        stored.task(':assetCompile').outcome == SUCCESS
        stored.output.contains('Configuration cache entry stored')
        assertCompiledAssets('build/assets')

        when: 'the compiled assets are removed'
        BuildResult cleaned = runner.withArguments('assetClean', '--stacktrace').build()

        then:
        cleaned.task(':assetClean').outcome == SUCCESS
        !new File(projectDirectory, 'build/assets').exists()

        when: 'the same build runs again'
        BuildResult reused = runner.withArguments('assetCompile', '--info', '--stacktrace').build()

        then: 'the task restored from the configuration cache compiles the assets again'
        reused.task(':assetCompile').outcome == SUCCESS
        reused.output.contains('Configuration cache entry reused')
        assertCompiledAssets('build/assets')

        and: 'with the fork options it was configured with'
        reused.output.contains('-Dasset.pipeline.test=true')
        reused.output.contains('-Xmx256m')

        when: 'nothing changed'
        BuildResult unchanged = runner.withArguments('assetCompile', '--stacktrace').build()

        then:
        unchanged.task(':assetCompile').outcome == UP_TO_DATE
        unchanged.output.contains('Configuration cache entry reused')
    }

    void 'assetPluginPackage reuses the configuration cache entry and packages the assets again'() {
        given:
        def runner = runner()
        writeApplication()
        write('src/assets/javascripts/library.js', 'var library = 1;\n')
        write('build.gradle', """
            plugins {
                id 'java'
                id 'cloud.wondrify.asset-pipeline'
            }

            ${assetRuntime(runner)}

            assets {
                packagePlugin = true
            }
            tasks.named('jar') {
                archiveFileName = 'plugin.jar'
            }
        """.stripIndent())

        when: 'the configuration cache entry is stored'
        BuildResult stored = runner.withArguments('jar', '--stacktrace').build()

        then:
        stored.task(':assetPluginPackage').outcome == SUCCESS
        stored.task(':assetCompile') == null
        stored.output.contains('Configuration cache entry stored')
        assertPackagedAssets()

        when: 'the build output is removed and the same build runs again'
        new File(projectDirectory, 'build').deleteDir()
        BuildResult reused = runner.withArguments('jar', '--stacktrace').build()

        then: 'the task restored from the configuration cache packages the assets again'
        reused.task(':assetPluginPackage').outcome == SUCCESS
        reused.output.contains('Configuration cache entry reused')
        assertPackagedAssets()
    }

    void 'assetCompile and assetPluginPackage reuse the configuration cache entry when both run'() {
        given:
        def runner = runner()
        writeApplication()
        write('src/assets/javascripts/library.js', 'var library = 1;\n')
        write('build.gradle', """
            plugins {
                id 'java'
                id 'cloud.wondrify.asset-pipeline'
            }

            ${assetRuntime(runner)}

            // Created before the fork options below are set
            tasks.named('assetCompile').get()

            assets {
                packagePlugin = true
                minifyCss = false
                minifyJs = false
                maxThreads = 1
                forkOptions = objects.newInstance(org.gradle.api.tasks.compile.GroovyForkOptions)
                forkOptions.jvmArgs = ['-Dasset.pipeline.test=both']
            }
            tasks.named('jar') {
                archiveFileName = 'plugin.jar'
            }
        """.stripIndent())

        when: 'the configuration cache entry is stored'
        BuildResult stored = runner.withArguments('assetCompile', 'jar', '--stacktrace').build()

        then:
        stored.task(':assetCompile').outcome == SUCCESS
        stored.task(':assetPluginPackage').outcome == SUCCESS
        stored.output.contains('Configuration cache entry stored')
        assertPackagedAssets()

        when: 'the build output is removed and the same build runs again'
        new File(projectDirectory, 'build').deleteDir()
        BuildResult reused = runner.withArguments('assetCompile', 'jar', '--info', '--stacktrace').build()

        then:
        reused.task(':assetCompile').outcome == SUCCESS
        reused.task(':assetPluginPackage').outcome == SUCCESS
        reused.output.contains('Configuration cache entry reused')
        reused.output.contains('-Dasset.pipeline.test=both')
        assertPackagedAssets()
        manifest('build/assets').getProperty('library.js')?.startsWith('library-')
        manifest('build/assets').getProperty('nested/site.css')?.startsWith('nested/site-')
    }

    void 'a registered in-process AssetCompile task reuses the configuration cache entry'() {
        given:
        def runner = runner()
        writeApplication()
        write('shared-assets/javascripts/shared.js', 'var shared = 1;\n')
        write('build.gradle', """
            plugins {
                id 'java'
                id 'cloud.wondrify.asset-pipeline'
            }

            ${assetRuntime(runner)}

            assets {
                minifyCss = false
                minifyJs = false
                maxThreads = 1
                from 'shared-assets'
            }
            tasks.register('inProcessAssetCompile', asset.pipeline.gradle.AssetCompile) {
                destinationDirectory = layout.buildDirectory.dir('in-process-assets')
                flattenResolvers = true
            }
        """.stripIndent())

        when: 'the configuration cache entry is stored'
        BuildResult stored = runner.withArguments('inProcessAssetCompile', '--stacktrace').build()

        then:
        stored.task(':inProcessAssetCompile').outcome == SUCCESS
        stored.output.contains('Configuration cache entry stored')
        assertCompiledAssets('build/in-process-assets')

        when: 'the compiled assets are removed and the same build runs again'
        new File(projectDirectory, 'build/in-process-assets').deleteDir()
        BuildResult reused = runner.withArguments('inProcessAssetCompile', '--stacktrace').build()

        then: 'the task restored from the configuration cache compiles the assets again'
        reused.task(':inProcessAssetCompile').outcome == SUCCESS
        reused.output.contains('Configuration cache entry reused')
        assertCompiledAssets('build/in-process-assets')
    }

    void 'setting #setting through a task still wires the build and reuses the configuration cache'() {
        given:
        def runner = runner()
        writeApplication()
        write('build.gradle', """
            plugins {
                id 'java'
                id 'cloud.wondrify.asset-pipeline'
            }

            ${assetRuntime(runner)}

            assets {
                minifyCss = false
                minifyJs = false
                maxThreads = 1
            }
            tasks.named('jar') {
                archiveFileName = 'plugin.jar'
            }
            tasks.register('customJar', Jar) {
                archiveFileName = 'custom.jar'
            }
            $configuration
        """.stripIndent())

        when:
        runner.withArguments(target, '--stacktrace')
        BuildResult stored = runner.build()

        then:
        stored.output.contains('Configuration cache entry stored')
        stored.task(":$assetTask").outcome == SUCCESS
        new File(projectDirectory, output).file
        !entry || contains(new File(projectDirectory, output), entry)

        when:
        new File(projectDirectory, 'build').deleteDir()
        BuildResult reused = runner.build()

        then:
        reused.output.contains('Configuration cache entry reused')
        reused.task(":$assetTask").outcome == SUCCESS
        new File(projectDirectory, output).file
        !entry || contains(new File(projectDirectory, output), entry)

        where:
        setting              | configuration                                                | target             | assetTask            | output                                            | entry
        'developmentRuntime' | 'assetCompile { config.developmentRuntime = false }'           | 'processResources' | 'assetCompile'       | 'build/resources/main/assets/manifest.properties' | null
        'packagePlugin'      | 'assetPluginPackage { config.packagePlugin = true }'           | 'jar'              | 'assetPluginPackage' | 'build/libs/plugin.jar'                           | 'META-INF/assets.list'
        'jarTaskName'        | "assetCompile { config.jarTaskName.set('customJar') }"         | 'customJar'        | 'assetCompile'       | 'build/libs/custom.jar'                           | 'assets/manifest.properties'
    }

    void 'asset tasks that compile in the Gradle daemon run one at a time with their own configuration'() {
        given:
        def runner = runner()
        writeApplication()
        write('shared-assets/javascripts/shared.js', 'var shared = 1;\n')
        write('src/assets/javascripts/library.js', 'var library = 1;\n')
        write('build.gradle', """
            plugins {
                id 'java'
                id 'cloud.wondrify.asset-pipeline'
            }

            ${assetRuntime(runner)}

            assets {
                minifyCss = false
                minifyJs = false
                maxThreads = 1
                from 'shared-assets'
            }
            tasks.register('withoutShared', asset.pipeline.gradle.AssetCompile) {
                destinationDirectory = layout.buildDirectory.dir('without-shared')
                flattenResolvers = true
                config.excludes.add('shared.js')
            }
            tasks.register('withoutLibrary', asset.pipeline.gradle.AssetCompile) {
                destinationDirectory = layout.buildDirectory.dir('without-library')
                flattenResolvers = true
                config.excludes.add('library.js')
            }
            tasks.matching { it.name in ['withoutShared', 'withoutLibrary', 'assetPluginPackage'] }.configureEach { task ->
                File interval = layout.buildDirectory.file("task-intervals/\${task.name}.txt").get().asFile
                task.doFirst {
                    interval.parentFile.mkdirs()
                    interval.text = "\${System.nanoTime()}\\n"
                    // Give another worker time to start an unlocked task, even when compiling these small fixtures.
                    Thread.sleep(1500)
                }
                task.doLast {
                    interval << "\${System.nanoTime()}\\n"
                }
            }
        """.stripIndent())

        when: 'three workers can run the tasks while storing the configuration cache entry'
        runner.withArguments('withoutShared', 'withoutLibrary', 'assetPluginPackage', '--max-workers=3', '--stacktrace')
        BuildResult stored = runner.build()

        then:
        stored.output.contains('Configuration cache entry stored')
        assertInProcessTasksSerialized(stored)

        when: 'the tasks execute again from the configuration cache'
        new File(projectDirectory, 'build').deleteDir()
        BuildResult reused = runner.build()

        then:
        reused.output.contains('Configuration cache entry reused')
        assertInProcessTasksSerialized(reused)
    }

    private void assertInProcessTasksSerialized(BuildResult result) {
        List<List<Long>> intervals = ['withoutShared', 'withoutLibrary', 'assetPluginPackage'].collect { String name ->
            assert result.task(":$name").outcome == SUCCESS
            List<Long> interval = new File(projectDirectory, "build/task-intervals/${name}.txt").readLines()*.toLong()
            assert interval.size() == 2
            assert interval[0] < interval[1]
            interval
        }.sort { it[0] }
        for (int index = 1; index < intervals.size(); index++) {
            assert intervals[index - 1][1] <= intervals[index][0]: "Asset task execution overlapped: $intervals"
        }
        assert manifest('build/without-shared').stringPropertyNames() == ['nested/site.css', 'library.js'] as Set
        assert manifest('build/without-library').stringPropertyNames() == ['nested/site.css', 'shared.js'] as Set
    }

    private GradleRunner runner() {
        // Every build runs with the configuration cache, and fails on any configuration cache problem
        write('gradle.properties', [
                'org.gradle.jvmargs=-Xmx512m',
                'org.gradle.configuration-cache=true',
                'org.gradle.configuration-cache.problems=fail',
        ].join('\n') + '\n')
        GradleRunner.create().withProjectDir(projectDirectory).withPluginClasspath()
    }

    private static String assetRuntime(GradleRunner runner) {
        def classpath = runner.pluginClasspath.collect { File file -> "'${file.absolutePath.replace('\\', '\\\\').replace("'", "\\'")}'" }.join(', ')
        """
            repositories {
                mavenCentral()
            }
            // Resolve the compiler runtime from the plugin under test, not a published release.
            configurations.assetDevelopmentRuntime.dependencies.clear()
            dependencies {
                assetDevelopmentRuntime files($classpath)
                assetDevelopmentRuntime localGroovy()
                assetDevelopmentRuntime files(org.slf4j.LoggerFactory.protectionDomain.codeSource.location.toURI())
            }
        """.stripIndent()
    }

    private void writeApplication() {
        write('settings.gradle', "rootProject.name = 'asset-packaging-test'\n")
        write('src/main/java/example/Application.java', getClass().getResource('/packaging/Application.java').text)
        write('src/assets/stylesheets/nested/site.css', 'body { color: #123456; }\n')
    }

    private void write(String path, String text) {
        new File(projectDirectory, path).tap {
            parentFile.mkdirs()
            setText(text, 'UTF-8')
        }
    }

    private Properties manifest(String path) {
        def manifest = new Properties()
        new File(projectDirectory, "$path/manifest.properties").withInputStream { manifest.load(it) }
        manifest
    }

    private void assertCompiledAssets(String path) {
        def compiled = new File(projectDirectory, path)
        def manifest = manifest(path)
        ['nested/site.css', 'shared.js'].each { String asset ->
            String digested = manifest.getProperty(asset)
            assert digested && digested != asset
            assert new File(compiled, digested).file
            assert new File(compiled, "${digested}.gz").file
        }
        assert new File(compiled, manifest.getProperty('nested/site.css')).text.contains('#123456')
        assert new File(compiled, manifest.getProperty('shared.js')).text.contains('var shared = 1')
        assert !manifest.stringPropertyNames().any { it.endsWith('.txt') }
    }

    private void assertPackagedAssets() {
        new JarFile(new File(projectDirectory, 'build/libs/plugin.jar')).withCloseable { JarFile jar ->
            assert jar.getInputStream(jar.getJarEntry('META-INF/assets.list')).text == 'library.js\nnested/site.css'
            assert jar.getInputStream(jar.getJarEntry('META-INF/assets/nested/site.css')).text.contains('#123456')
            assert jar.getInputStream(jar.getJarEntry('META-INF/assets/library.js')).text.contains('var library = 1')
            assert jar.getJarEntry('assets/manifest.properties') == null
        }
    }

    private static boolean contains(File archive, String path) {
        new JarFile(archive).withCloseable { it.getJarEntry(path) != null }
    }

    private void assertCompactExtraction(File archive, String name) {
        def extracted = new File(projectDirectory, "extracted-$name")
        runJava(projectDirectory, '-Djarmode=tools', '-jar', archive.absolutePath,
                'extract', '--layers', '--destination', extracted.absolutePath)
        def merged = new File(projectDirectory, "runtime-$name")
        ['dependencies', 'spring-boot-loader', 'snapshot-dependencies', 'application'].each { String layer ->
            def directory = new File(extracted, layer)
            directory.eachFileRecurse(FileType.FILES) { File source ->
                def target = merged.toPath().resolve(directory.toPath().relativize(source.toPath()))
                Files.createDirectories(target.parent)
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        assert contains(new File(merged, name), 'assets/manifest.properties')
        assert runJava(merged, '-jar', name).contains('ASSETS_OK')
    }

    private static String runJava(File directory, String... arguments) {
        def output = File.createTempFile('asset-packaging-', '.log', directory)
        def executable = new File(System.getProperty('java.home'), "bin/${System.getProperty('os.name').startsWith('Windows') ? 'java.exe' : 'java'}")
        def process = new ProcessBuilder([executable.absolutePath] + arguments.toList())
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
        try {
            assert process.waitFor(60, TimeUnit.SECONDS): 'Java process did not exit within 60 seconds'
            assert process.exitValue() == 0: output.text
            output.text
        } finally {
            if (process.alive) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
    }
}
