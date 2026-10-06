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

import asset.pipeline.AssetPipelineConfigHolder
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.tasks.compile.GroovyForkOptions
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

import java.lang.reflect.Method

class AssetPipelineExtensionSpec extends Specification {

    @TempDir
    File projectDirectory

    void 'assets are read from src/assets by default'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()

        when:
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')

        then:
        extension(project).assetsPath.get().asFile == new File(project.projectDir, 'src/assets')
        assetCompile(project).srcDir.get().asFile == new File(project.projectDir, 'src/assets')
        assetCompile(project).destinationDirectory.get().asFile == new File(project.projectDir, 'build/assets')
    }

    void 'assets are read from grails-app/assets when the Grails plugin was applied first'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.extensions.add('grails', new Object())

        when:
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')

        then:
        extension(project).assetsPath.get().asFile == new File(project.projectDir, 'grails-app/assets')
        assetCompile(project).srcDir.get().asFile == new File(project.projectDir, 'grails-app/assets')
    }

    void 'the assets directory and the compile output follow later configuration'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')

        when:
        extension(project).assetsPath.set(project.layout.projectDirectory.dir('assets'))
        project.layout.buildDirectory.set(project.layout.projectDirectory.dir('out'))

        then:
        assetCompile(project).srcDir.get().asFile == new File(project.projectDir, 'assets')
        assetCompile(project).destinationDirectory.get().asFile == new File(project.projectDir, 'out/assets')
    }

    void 'a Grails project reads its assets from grails-app/assets however the extension or a task is created'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.extensions.add('grails', new Object())

        when:
        AssetPipelineExtension created = project.extensions.create('assets', AssetPipelineExtension)
        Project withoutExtension = ProjectBuilder.builder().withParent(project).withName('sub').build()
        withoutExtension.extensions.add('grails', new Object())
        AssetPluginPackage task = withoutExtension.tasks.register('assetPluginPackage', AssetPluginPackage).get()

        then:
        created.assetsPath.get().asFile == new File(project.projectDir, 'grails-app/assets')
        task.config.assetsPath.get().asFile == new File(withoutExtension.projectDir, 'grails-app/assets')
    }

    void 'from resolves a resolver path against the project directory'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')
        File absolute = new File(project.projectDir, 'elsewhere/assets')

        when:
        extension(project).from('shared-assets')
        extension(project).from(absolute.path)

        then:
        extension(project).resolvers.files == [new File(project.projectDir, 'shared-assets'), absolute] as Set
    }

    void 'each task takes its configuration from the assets extension unless it sets its own'() {
        given: 'tasks created before the extension is configured'
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')
        AssetForkedCompileTask compile = assetCompile(project)
        AssetPluginPackage pluginPackage = project.tasks.named('assetPluginPackage', AssetPluginPackage).get()
        AssetCompile inProcess = project.tasks.register('inProcessAssetCompile', AssetCompile).get()

        when:
        extension(project).with {
            minifyJs.set(false)
            maxThreads.set(2)
            excludes.set(['**/*.less'])
            configOptions.put('commonJs', false)
            assetsPath.set(project.layout.projectDirectory.dir('assets'))
            from('shared-assets')
        }
        compile.config.enableGzip.set(false)

        then: 'every task sees the extension'
        [compile.config, pluginPackage.config, inProcess.config].every { AssetPipelineExtension config ->
            !config.minifyJs.get() &&
                    config.maxThreads.get() == 2 &&
                    config.excludes.get() == ['**/*.less'] &&
                    config.configOptions.get() == [commonJs: false] &&
                    config.assetsPath.get().asFile == new File(project.projectDir, 'assets') &&
                    config.resolvers.files == [new File(project.projectDir, 'shared-assets')] as Set
        }
        compile.srcDir.get().asFile == new File(project.projectDir, 'assets')

        and: 'a value a task sets is its own'
        !compile.config.enableGzip.get()
        extension(project).enableGzip.get()
        pluginPackage.config.enableGzip.get()
        inProcess.config.enableGzip.get()

        and: 'no task nests the extension, or another task configuration'
        [compile.config, pluginPackage.config, inProcess.config].every { !it.is(extension(project)) }
        !compile.config.is(pluginPackage.config)
    }

    void 'adding to a list or map of a task adds to the values of the extension'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')
        AssetForkedCompileTask compile = assetCompile(project)
        extension(project).with {
            excludes = ['**/*.less']
            includes = ['**/keep.less']
            excludesGzip = ['**/*.png']
            configOptions = [commonJs: false]
            minifyOptions = [languageMode: 'ES6']
        }

        when:
        compile.config.with {
            excludes.add('**/*.map')
            includes.add('**/keep.map')
            excludesGzip.add('**/*.gif')
            configOptions.put('foo', 'bar')
            minifyOptions.put('optimizationLevel', 'SIMPLE')
        }

        then:
        compile.config.excludes.get() == ['**/*.less', '**/*.map']
        compile.config.includes.get() == ['**/keep.less', '**/keep.map']
        compile.config.excludesGzip.get() == ['**/*.png', '**/*.gif']
        compile.config.configOptions.get() == [commonJs: false, foo: 'bar']
        compile.config.minifyOptions.get() == [languageMode: 'ES6', optimizationLevel: 'SIMPLE']

        and: 'the extension, and other tasks, keep their values'
        extension(project).excludes.get() == ['**/*.less']
        extension(project).configOptions.get() == [commonJs: false]
        pluginPackage(project).config.excludes.get() == ['**/*.less']

        when: 'the task sets a value'
        compile.config.excludes.set(['**/*.txt'])

        then: "it replaces the extension's for that task"
        compile.config.excludes.get() == ['**/*.txt']
    }

    void 'every lazy value of the extension reaches the configuration of each task'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')
        List<AssetPipelineExtension> taskConfigurations = [
                assetCompile(project).config,
                pluginPackage(project).config,
                project.tasks.register('inProcessAssetCompile', AssetCompile).get().config,
        ]
        File resolver = new File(project.projectDir, 'shared-assets')
        // A value differing from the default for each; a new value of the extension must be added here
        Map<String, Object> samples = [
                assetsPath             : project.layout.projectDirectory.dir('elsewhere'),
                configOptions          : [commonJs: false],
                developmentRuntime     : false,
                enableDigests          : false,
                enableGzip             : false,
                enableSourceMaps       : false,
                excludeWebjarsByDefault: true,
                excludes               : ['**/*.less'],
                excludesGzip           : ['**/*.png'],
                includes               : ['**/keep.less'],
                jarTaskName            : 'customJar',
                maxThreads             : 3,
                minifyCss              : false,
                minifyJs               : false,
                minifyOptions          : [languageMode: 'ES6'],
                packagePlugin          : true,
                resolvers              : resolver,
                skipNonDigests         : false,
                verbose                : false,
        ]
        List<String> names = AssetPipelineExtension.LAZY_PROPERTIES.collect { Method getter ->
            getter.name.substring(3, 4).toLowerCase() + getter.name.substring(4)
        }

        expect:
        names.toSet() == samples.keySet()

        when:
        samples.each { String name, Object value ->
            def property = extension(project)."$name"
            property instanceof ConfigurableFileCollection ? property.from(value) : property.set(value)
        }

        then:
        taskConfigurations.every { AssetPipelineExtension config ->
            samples.every { String name, Object value ->
                def actual = config."$name"
                if (actual instanceof ConfigurableFileCollection) {
                    return actual.files == [resolver] as Set
                }
                def resolved = actual.get()
                resolved instanceof FileSystemLocation ? resolved.asFile == value.asFile : resolved == value
            }
        }
    }

    void 'the settings the plugin wires the tasks with still apply project-wide when set through a task'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')

        when:
        extension(project).packagePlugin = true

        then:
        pluginPackage(project).config.packagePlugin.get()

        when:
        assetCompile(project).config."$setting".set(value)

        then:
        extension(project)."$setting".get() == value
        pluginPackage(project).config."$setting".get() == value

        when:
        pluginPackage(project).config."$setting" = otherValue

        then:
        extension(project)."$setting".get() == otherValue
        assetCompile(project).config."$setting".get() == otherValue

        where:
        setting              | value       | otherValue
        'packagePlugin'      | false       | true
        'developmentRuntime' | false       | true
        'jarTaskName'        | 'customJar' | 'anotherJar'
    }

    void 'the compile task takes a copy of the fork options the extension has once the build is configured'() {
        given: 'the task exists before the fork options are set'
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')
        AssetForkedCompileTask compile = assetCompile(project)

        when: 'the build sets fork options, and then replaces them'
        extension(project).forkOptions = forkOptions(project, '256m')
        GroovyForkOptions replaced = forkOptions(project, '2g', ['-Dasset.pipeline.test=true'])
        extension(project).forkOptions = replaced

        then: 'the task forks with the options the extension has last'
        compile.config.forkOptions.memoryMaximumSize == '2g'
        compile.config.forkOptions.jvmArgs == ['-Dasset.pipeline.test=true']

        when: 'the task changes its options'
        compile.config.forkOptions.memoryMaximumSize = '1g'
        compile.config.forkOptions.jvmArgs.add('-Dother=true')

        then: 'only the task has them'
        compile.config.forkOptions.memoryMaximumSize == '1g'
        replaced.memoryMaximumSize == '2g'
        replaced.jvmArgs == ['-Dasset.pipeline.test=true']
    }

    void 'only the task that forks the compiler takes fork options'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')

        when:
        extension(project).forkOptions = forkOptions(project, '2g')

        then:
        assetCompile(project).config.forkOptions.memoryMaximumSize == '2g'
        pluginPackage(project).config.forkOptions == null
        project.tasks.register('inProcessAssetCompile', AssetCompile).get().config.forkOptions == null
    }

    void 'a task reads its sources from the assets directory and the resolver directories'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')
        File asset = new File(project.projectDir, 'src/assets/javascripts/app.js').tap { parentFile.mkdirs(); text = '' }
        File shared = new File(project.projectDir, 'shared-assets/shared.js').tap { parentFile.mkdirs(); text = '' }
        File jar = new File(project.projectDir, 'libs/assets.jar').tap { parentFile.mkdirs(); text = '' }
        extension(project).from('shared-assets')
        extension(project).from('libs/assets.jar')
        AssetCompile inProcess = project.tasks.register('inProcessAssetCompile', AssetCompile).get()

        expect:
        assetCompile(project).source.files == [asset, shared] as Set
        inProcess.source.files == [asset, shared] as Set
        assetCompile(project).source.is(assetCompile(project).source)
    }

    void 'the classpath of an in-process compile creates no other configuration'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply('java')
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')
        boolean created = false
        project.configurations.register('unrelated') { created = true }
        AssetCompile inProcess = project.tasks.register('inProcessAssetCompile', AssetCompile).get()

        when:
        Set<File> files = inProcess.classpath.files
        inProcess.classpath.buildDependencies.getDependencies(inProcess)

        then:
        files.empty
        then:
        !created
    }

    void 'applying the plugin leaves the asset pipeline configuration of the JVM alone'() {
        given:
        Map previous = AssetPipelineConfigHolder.config
        AssetPipelineConfigHolder.config = null
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()

        when:
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')

        then:
        AssetPipelineConfigHolder.config == null

        cleanup:
        AssetPipelineConfigHolder.config = previous
    }

    void 'an in-process compile uses only its own configuration'() {
        given: 'configuration an earlier task left in the JVM'
        Map previous = AssetPipelineConfigHolder.config
        AssetPipelineConfigHolder.config = [leftover: true, commonJs: false]
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply('cloud.wondrify.asset-pipeline')
        new File(project.projectDir, 'src/assets/javascripts/app.js').tap { parentFile.mkdirs(); text = 'var app = 1;' }
        extension(project).configOptions = [custom: 'value']
        AssetCompile inProcess = project.tasks.register('inProcessAssetCompile', AssetCompile).get()

        when:
        inProcess.compile()

        then:
        AssetPipelineConfigHolder.config == [
                cacheLocation: new File(project.projectDir, 'build/.assetcache').absolutePath,
                custom       : 'value',
        ]

        and: 'the asset tasks that compile in the JVM run one at a time'
        project.gradle.sharedServices.registrations.getByName(AssetPipelineConfigService.NAME).maxParallelUsages.get() == 1

        cleanup:
        AssetPipelineConfigHolder.config = previous
    }

    void 'an asset task works without the assets extension'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()

        when:
        AssetPluginPackage task = project.tasks.register('assetPluginPackage', AssetPluginPackage).get()

        then:
        task.config.assetsPath.get().asFile == new File(project.projectDir, 'src/assets')
        task.config.minifyJs.get()
        task.config.forkOptions == null
    }

    private static AssetPipelineExtension extension(Project project) {
        project.extensions.getByType(AssetPipelineExtension)
    }

    private static AssetForkedCompileTask assetCompile(Project project) {
        project.tasks.named('assetCompile', AssetForkedCompileTask).get()
    }

    private static AssetPluginPackage pluginPackage(Project project) {
        project.tasks.named('assetPluginPackage', AssetPluginPackage).get()
    }

    private static GroovyForkOptions forkOptions(Project project, String memoryMaximumSize, List<String> jvmArgs = []) {
        GroovyForkOptions options = project.objects.newInstance(GroovyForkOptions)
        options.memoryMaximumSize = memoryMaximumSize
        options.jvmArgs = jvmArgs
        options
    }
}
