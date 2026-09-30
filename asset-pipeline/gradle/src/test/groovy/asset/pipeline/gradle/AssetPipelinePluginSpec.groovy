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
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

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

    private GradleRunner runner() {
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
        write('gradle.properties', 'org.gradle.jvmargs=-Xmx512m\n')
        write('src/main/java/example/Application.java', getClass().getResource('/packaging/Application.java').text)
        write('src/assets/stylesheets/nested/site.css', 'body { color: #123456; }\n')
    }

    private void write(String path, String text) {
        new File(projectDirectory, path).tap {
            parentFile.mkdirs()
            setText(text, 'UTF-8')
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
