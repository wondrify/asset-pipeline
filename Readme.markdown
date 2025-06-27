Asset Pipeline Core
===================
[![Build Status](https://github.com/wondrify/asset-pipeline/actions/workflows/ci.yml/badge.svg?branch=5.0.x)](https://github.com/wondrify/asset-pipeline/actions/workflows/ci.yml)


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

Documentation
------------

* [API Doc](http://www.asset-pipeline.com/apidoc/index.html)
* [Usage Guide](http://www.asset-pipeline.com/manual/)
* [Website](http://www.asset-pipeline.com)


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
    classpath "com.bertramlabs.plugins:asset-pipeline-gradle:2.10.1"
  }
}

apply plugin: 'com.bertramlabs.asset-pipeline'

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
  // assets 'com.bertramlabs.plugins:less-asset-pipeline:{}'
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
To accomodate varying uses of the groovy module (i.e. groovy-all vs. groovy) This library depends on groovy even though it does not directly export it. Make sure you add the following dependenices

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

* [API Doc](http://www.asset-pipeline.com/apidoc/index.html)
* [Doc](http://www.asset-pipeline.com/manual/index.html)


For Grails 3 asset-pipeline has to be provided both for Grails and Gradle. An example configuration could be: 

```groovy
// Add the Gradle plugin to the build dependencies and apply it to the build process
buildscript {
    dependencies {        
        classpath 'com.bertramlabs.plugins:asset-pipeline-gradle:2.10.1'
    }
}
apply plugin: 'asset-pipeline'

// The plugin could also be applied with the newer syntax 
// plugins {
//     id "com.bertramlabs.asset-pipeline" version "2.10.1"
// }

dependencies {        
    // Add the Grails Plugin to the runtime dependencies
    runtime 'com.bertramlabs.plugins:asset-pipeline-grails:2.10.1'
    
    // Define needed asset-pipeline plugins with the special assets-scope 
    assets 'com.bertramlabs.plugins:less-asset-pipeline:2.10.1'
    assets 'com.bertramlabs.plugins:sass-asset-pipeline:2.10.1'
}
```

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
  provided 'org.codehaus.groovy:groovy-all:2.0.7'
  compile "com.bertramlabs.plugins:asset-pipeline-core:2.10.1"
}
```

Additional Resources
--------------------
* [Coffeescript Asset-Pipeline Plugin](http://github.com/bertramdev/coffee-asset-pipeline)
* [LESS Css Asset-Pipeline Plugin](http://github.com/bertramdev/less-asset-pipeline)
* SASS Coming Soon
* [Handlebars Asset-Pipeline Plugin](http://github.com/bertramdev/handlebars-asset-pipeline)
* [Ember Asset-Pipeline Plugin](http://github.com/bertramdev/ember-asset-pipeline)
* [AngularJS Template Asset-Pipeline Plugin](https://github.com/craigburke/angular-template-grails-asset-pipeline)
* [AngularJS Annotate Asset-Pipeline Plugin](https://github.com/craigburke/angular-annotate-grails-asset-pipeline)
* [Grails Asset Pipeline Guide](http://bertramdev.github.io/grails-asset-pipeline/)
