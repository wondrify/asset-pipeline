Asset Pipeline Core
===================
[![Build Status](https://github.com/wondrify/asset-pipeline/actions/workflows/ci.yml/badge.svg?branch=5.0.x)](https://github.com/wondrify/asset-pipeline/actions/workflows/ci.yml)

> **📚 Documentation:** Full documentation is now available at [https://wondrify.github.io/asset-pipeline/](https://wondrify.github.io/asset-pipeline/)

Overview
--------
The Asset-Pipeline is a plugin used for managing and processing static assets in JVM applications primarily via Gradle (however not mandatory). Asset-Pipeline functions include processing and minification of both CSS and JavaScript files. It is also capable of being extended to compile custom static assets, such as CoffeeScript or LESS.

**Features:**
* Asset Bundling
* Extensible Modules (Supports LESS,Handlebars,Coffeescript, Ember-Handlebars, SASS) via other modules.
* Cache Digest Names (Creates cache digested names and stores aliases in a manifest.properties)
* Js Minification
* Js SourceMap Generation
* Css Minification / Relative Path assertion
* File Encoding Support
* GZIP File Generation
* Last-Modified Header

Requirements
------------

* **Java 21 or newer** — the closure compiler dependency (`com.google.javascript:closure-compiler-unshaded`) requires a Java 21+ JVM as of version `v20260302`. The asset pipleine as of 5.2.x requires Java 21 as a result.

Version Compatibility
---------------------

| Asset Pipeline | Grails | Spring Boot | Java |
| -------------- | ------ | ----------- | ---- |
| 5.2.x          | 8.x    | 4.x         | 21+  |
| 5.1.x          | 7.x    | 3.x         | 21+  |
| 5.0.x          | 7.x    | 3.x         | 17+  |

Documentation
------------

* [Usage Guide](https://wondrify.github.io/asset-pipeline/) - Complete documentation on GitHub Pages
* [API Doc](http://asset-pipeline.com/apidoc/index.html) - JavaDoc/GroovyDoc API reference


Gradle Usage
-----------
If using gradle, this plugin adds a series of tasks directly to your gradle plugin. All you have to do is `apply plugin:'asset-pipeline'` after confirming this is in the classpath of your `buildscript` block. i.e.:

```groovy
//Example build.gradle file
buildscript {
  repositories {
    maven {
      url "https://plugins.gradle.org/m2/"
    }
    mavenCentral()
  }
  dependencies {
    classpath "cloud.wondrify:asset-pipeline-gradle:5.1.0"
  }
}

apply plugin: 'cloud.wondrify.asset-pipeline'

assets {
  minifyJs = true
  minifyCss = true
  enableSourceMaps = true
  configOptions = [:]
  
  minifyOptions = [
    languageMode: 'ES5',
    targetLanguage: 'ES5', //Can go from ES6 to ES5 for those bleeding edgers
    optimizationLevel: 'SIMPLE',
    angularPass: true // Can use @ngInject annotation for Angular Apps
  ]
  
  includes = []
  excludes = ['**/*.less'] //Example Exclude GLOB pattern

  //for plugin packaging
  packagePlugin=false //set to true if this is a library

  //developmentRuntime can be turned off
  developmentRuntime=true

  //if you want to customize the jar task this task runs on you can specify a jarTaskName
  jarTaskName=null

  // Can add custom asset locations (directories or individual jar files)
  from '/vendor/lib'
  from '/path/to/file.jar'
  
  // can be used to customize the hashing of the assets
  digestAlgorithm = 'MD5'
  digestSalt = ''
}

dependencies {
  // Example additional LESS support
  // assets 'cloud.wondrify:less-asset-pipeline:{}'
}

```

Now that you have your build.gradle files. All you need to do is put files in your projects `src/assets/javascripts`, `src/assets/stylesheets`, `src/assets/images`, or whatever subdirectory you want.
When you run `gradle assetCompile` these files will be processed and output into your `build/assets` folder by default.

Thats about all there is to it. Now you can use gradle to handle processing of all your client side assets.

Advanced Usage
--------------
The core part of asset-pipeline is rather agnostic to whichever implementation you want to use, be it servlet based, netty based, or whatever else you want to do.
The recommended use case for this aspect of the plugin is to integrate with other plugins that are capable of being scoped to specific frameworks. Currently the best example of the use case for the asset-pipeline plugin is in the Grails framework.

The core plugin provides interfaces for asset resolution, processing, and compiling into a target directory.
You can register resolvers in the `AssetPipelineConfigHolder` to essentially add scan paths for your static assets. This includes both File System support as well as Jar file support (ClassPathResolver coming soonish)


```groovy
import asset.pipeline.*
import asset.pipeline.fs.*

def fsResolver = new FileSystemAssetResolver('application','assets')
def jarResolver = new JarAssetResolver('application','/path/to/file.jar','META-INF/assets')
AssetPipelineConfigHolder.registerResolver(fsResolver)
AssetPipelineConfigHolder.registerResolver(jarResolver)
```
As can be seen above, we have registered 2 examples of implementations of the `AssetResolver` interface

Now we can take advantage of asset-pipeline endpoints to fetch files for development mode as well as compiling files for production.

To Fetch a File in Development mode simply use the following endpoint:

```groovy
class AssetPipeline {
	static byte[] serveAsset(uri, contentType = null, extension = null, encoding = null) {

	//Returns a dependency list of your asset (based on require directives)
	static def getDependencyList(uri, contentType = null, extension = null)

	//For skipping directive processing (when requiring files individually)
	static byte[] serveUncompiledAsset(uri, contentType, extension = null,encoding=null)
}
```


These endpoints are great when actively developing your application. They allow you to grab a file and process it on the fly without waiting for a watcher to reload.


Production
----------
In a compiled production environment, it is not recommended that files get resolved on the fly and processed like this. It adds a large amount of overhead to your application.
To solve this, the Asset Pipeline provides an `AssetCompiler`. This is a configurable compiler that can scan your assets, process them, fingerprint them, and throw them into a target.
The target can vary depending on the implementation of the framework in question. For example, in Grails we compile assets into the `target/assets` folder then copy those into the War file during war build.
We then register a Servlet Filter that looks in this folder based on a url mapping to serve assets, check for gzip, set cache headers, and more.


```groovy
def compiler = new AssetCompiler()

compiler.compile()
```

This extraction is not yet 100% complete and is in active development. If you are interested in implementing the asset-pipeline for a jvm framework. Feel free to take a look to get an idea what you will need to do.


CDN Notes (Last-Modified Header)
---------
Some CDN providers rely on the existence of a `Last-Modified` header to successfully use the `If-None-Match` request header.  This will be served up in production mode for assets served locally from asset-pipeline.


Dependencies
------------
To accommodate varying uses of the groovy module (i.e. groovy-all vs. groovy) This library depends on groovy even though it does not directly export it. Make sure you add the following dependenices

```groovy
dependencies {
  compile 'org.codehaus.groovy:groovy:2.0.7'
  compile 'org.codehaus.groovy:groovy-templates:2.0.7'
  //or
  //compile 'org.codehaus.groovy:groovy-all:2.0.7'
}
```


Documentation
-------------

* [Usage Guide](https://wondrify.github.io/asset-pipeline/) - Complete documentation on GitHub Pages
* [API Doc](http://asset-pipeline.com/apidoc/index.html) - JavaDoc/GroovyDoc API reference


For Grails, asset-pipeline has to be provided both for Grails and Gradle. An example configuration could be: 

```groovy
// Add the Gradle plugin to the build dependencies and apply it to the build process
buildscript {
    dependencies {        
        classpath 'cloud.wondrify:asset-pipeline-gradle:5.1.0'
    }
}
apply plugin: 'asset-pipeline'

// The plugin could also be applied with the newer syntax 
// plugins {
//     id "cloud.wondrify.asset-pipeline" version "5.1.0"
// }

dependencies {        
    // Add the Grails Plugin to the runtime dependencies
    runtime 'cloud.wondrify:asset-pipeline-grails:5.1.0'
    
    // Define needed asset-pipeline plugins with the special assets-scope 
    assets 'cloud.wondrify:less-asset-pipeline:5.1.0'
    assets 'cloud.wondrify:sass-asset-pipeline:5.1.0'
}
```

WebJar Support
--------------

The Asset Pipeline plugin provides wildcard asset path resolution for WebJars, eliminating the need to hardcode version numbers in your views.

### Setup

```groovy
dependencies {
    // Add your webjar dependencies
    assetDevelopmentRuntime "org.webjars.npm:jquery:3.7.1"
    assetDevelopmentRuntime "org.webjars.npm:bootstrap:5.3.0"
}
```

**Note:** Use `assetDevelopmentRuntime` instead of `implementation` to keep WebJars out of your production JAR/WAR. The webjars are only needed during development and asset compilation.

### Usage in Grails

In your GSP files, you can now reference wildcard paths like in webJars without specifying package names or versions:

```gsp
<!-- Version automatically resolved from classpath -->
<!-- IMPORTANT: Use file path WITHOUT package name -->
<asset:javascript src="webjars/jquery/*/dist/jquery.js"/>
<asset:stylesheet href="webjars/bootstrap/*/css/bootstrap.css"/>

<!-- Explicit versions still work (backward compatible) -->
<asset:javascript src="webjars/jquery/3.7.1/dist/jquery.js"/>
```


### Usage with Require Directives

WebJar version resolution also works with require directives in JavaScript and CSS files:

**JavaScript:**
```javascript
//= require webjars/jquery/*/dist/jquery.js
```

**CSS:**
```css
/*
 *= require webjars/bootstrap/%/dist/css/bootstrap.css
 *= require webjars/bootstrap/%/font/bootstrap-icons.css
 */
```

**Before (with explicit versions):**
```javascript
//= require webjars/jquery/3.7.1/dist/jquery.js
//= require webjars/bootstrap/5.3.0/dist/js/bootstrap.bundle.js
```

**After (version-less):**
```javascript
//= require webjars/jquery/*/dist/jquery.js
//= require webjars/bootstrap/*/dist/js/bootstrap.bundle.js
```

When you upgrade dependencies in `build.gradle`, your require directives automatically resolve to the new versions - no code changes needed!

### How It Works

**Version Resolution:**
1. Detects wildcard path (e.g., `webjars/jquery/*/dist/jquery.js`)
2. Resolves to versioned path (e.g., `webjars/jquery/3.7.1/dist/jquery.js`)
3. Caches resolved paths for performance

A `*` or `%` path component stands for exactly one directory. It can be the first component or appear more than once (`webjars/%/%/dist/jquery.js`). A `**` or `%%` component stands for any number of directories, none included, as `**` does in a glob, so `webjars/%%/dist/jquery.js` finds `webjars/jquery/3.7.1/dist/jquery.js`. In a CSS require block use `%` and `%%`, since `*/` there ends the comment. The file system, the classpath and jars resolve wildcards the same way, and so does the Grails plugin's manifest lookup in production. When several paths hold the file, the one with the fewest directories in place of each `%%` wins, so a copy that another webjar bundles further down is not picked up; after that, the highest version wins (`5.1.2` over `4.3.0`, `10.0.0` over `9.0.0`, and a release over its own pre-releases, `5.1.2` over `5.1.2-rc.1`). Hidden directories (a name starting with `.`) are never used.

### Benefits

- **No version maintenance in views**: Update dependencies in `build.gradle` without changing GSP files
- **No package names needed**: Simpler paths - just specify the file path within the webjar
- **Eliminates 404 errors**: No mismatched versions between dependencies and view references
- **Cleaner code**: Shorter, more maintainable asset references
- **Production optimized**: WebJars excluded from production JAR/WAR when using `assetDevelopmentRuntime`
- **Performance**: Resolved paths are cached for fast lookups
- **Backward compatible**: Explicit versions with package names continue to work

### Example

**Before:**
```gsp
<asset:javascript src="webjars/jquery/3.7.1/dist/jquery.js"/>
<asset:javascript src="webjars/jquery-form/4.3.0/src/jquery.form.js"/>
<asset:javascript src="webjars/bootstrap/5.3.0/dist/js/bootstrap.bundle.js"/>
<asset:stylesheet href="webjars/bootstrap/5.3.0/dist/css/bootstrap.css"/>
```

**After:**
```gsp
<asset:javascript src="webjars/jquery/*/dist/jquery.js"/>
<asset:javascript src="webjars/jquery-form/*/src/jquery.form.js"/>
<asset:javascript src="webjars/bootstrap/*/dist/js/bootstrap.bundle.js"/>
<asset:stylesheet href="webjars/bootstrap/*/dist/css/bootstrap.css"/>
```

When you upgrade jQuery from 3.7.1 to 3.7.2, just update `build.gradle` - no view changes needed!


Contributions
-------------
All contributions are of course welcome as this is an ACTIVE project. Any help with regards to reviewing platform compatibility, adding more tests, and general cleanup is most welcome.
Thanks to several people for suggestions throughout development. Notably: Brian Wheeler (@bdwheeler), Rick Jensen (@cdeszaq), Bobby Warner (@bobbywarner), Ted Naleid (@tednaleid), Craig Burke (@craigburke1) and more to follow I'm sure...

When creating custom binary plugins to extend this (details also in the grails documentation) You will want to use groovy but not directly export it from gradle. Here is an example of how to do that.

```groovy
configurations {
    provided
}
 
sourceSets {
    main {
        compileClasspath += configurations.provided
    }
}

dependencies {
  provided 'org.apache.groovy:groovy-all:4.0.30'
  compile "cloud.wondrify:asset-pipeline-core:5.1.0"
}
```
