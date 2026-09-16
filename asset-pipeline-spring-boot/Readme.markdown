Spring Boot Asset Pipeline Gradle Adapter
-----------------------------------------

This is a preliminary test project for adding support to spring boot.

It takes files stored in `assets/javascripts` , `assets/stylesheets`, `assets/images`, `assets/**/*`, and compiles them into the root jar `assets` folder.
It supports both development mode where it compiles on the fly and production `gradle assemble`

The library configures itself: a servlet application that has it on the class path serves what
the pipeline compiled from `/assets/*`, without anything to wire up.

```groovy
package demo

@SpringBootApplication
class Application {

    static void main(String[] args) {
        SpringApplication.run Application, args
    }
}

```

An application that declares its own `assetPipelineFilterBean` keeps it, and one that wants the
library on the class path without the filter says so:

```yaml
assets:
    enabled: false
```

An older application that added `asset.pipeline.springboot` to its `@ComponentScan` needs no
change: the scan registers the configuration, the auto-configuration sees the bean it defines
and stands aside, and the same `assets.enabled` setting still switches it off. The scan entry is
redundant rather than inert - remove it and the auto-configuration does the same work.

Example Gradle File for Spring Boot:
```groovy
buildscript {
    ext {
        springBootVersion = '1.1.9.RELEASE'
    }
    repositories {
        mavenCentral()
        mavenLocal();
    }
    dependencies {
        classpath("org.springframework.boot:spring-boot-gradle-plugin:${springBootVersion}")
        //Asset Pipeline Dependencies
        classpath("com.bertramlabs.plugins:asset-pipeline-gradle:2.6.7")
        //optional
        //compile "com.bertramlabs.plugins.less-asset-pipeline:2.6.7"
        //compile "com.bertramlabs.plugins.coffee-asset-pipeline:2.6.7"
    }
}

apply plugin: 'groovy'
apply plugin: 'eclipse'
apply plugin: 'idea'
apply plugin: 'spring-boot'
apply plugin: 'asset-pipeline' //need this

sourceCompatibility = 1.7
targetCompatibility = 1.7

repositories {
    mavenCentral()
    mavenLocal()
}


dependencies {
    compile("org.springframework.boot:spring-boot-starter-thymeleaf")
    compile("org.codehaus.groovy:groovy")
    testCompile("org.springframework.boot:spring-boot-starter-test")

    //Asset Pipeline Dependencies
    compile("com.bertramlabs.plugins:asset-pipeline-spring-boot:2.6.7")
    //optional
    //compile "com.bertramlabs.plugins.less-asset-pipeline:2.6.7"
    //compile "com.bertramlabs.plugins.coffee-asset-pipeline:2.6.7"
}



```

We have to make sure we add the gradle plugin to our buildscript. We also need to make sure we add the extended asset-pipeline plugins for LESS and Coffee to both the `dependencies` block of runtime and `dependencies` block of buildscript.

It is also important to modify the `compileDir` of the `assets` config block and include the folder in the example above in your `jar` config with the `from` argument.

**NOTE** Do not forget to set `apply 'asset-pipeline'` after including `asset-pipeline-gradle` in your buildscript. For runtime you include `asset-pipeline-core`.

You can refer to files in your templates via the `/assets/**` mapping. So you might have an html file simply with

```html
<!DOCTYPE html>
<html>
<head>
    <title></title>
    <script type="text/javascript" src="/assets/application.js"></script>
    <link rel="stylesheet" href="/assets/application.css"/>
</head>
<body>

</body>
</html>
```

These files would be located in `src/assets/javascripts/application.js` and `src/assets/stylesheets/application.css` (or .less). Now you can take advantage of bundling and processing of files of different types. 

For information on how to use `require` directives in your files or configuration options check out the documentation for the gradle plugin at:

* [Asset Pipeline Core](http://github.com/bertramdev/asset-pipeline-core)
* And for usage check out the [Grails Guide](http://bertramdev.github.io/asset-pipeline)


### Things to be Done

* Need to support replacing urls with cache digest names in the different spring boot template languages

### Contributions

Contributions are of course most welcome and much appreciated. Please feel free to send Pull Requests. you can always test your local build by changing the version and taking advantage of `gradle publishToMavenLocal`.