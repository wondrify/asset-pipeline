Asset Pipeline Core
===================
[![Build Status](https://travis-ci.org/bertramdev/asset-pipeline.png?branch=master)](https://travis-ci.org/bertramdev/asset-pipeline)

Overview
--------
The Asset-Pipeline is a plugin used for managing and processing static assets in JVM applications primarily via Gradle (however not mandatory). Asset-Pipeline functions include processing and minification of both CSS and JavaScript files. It is also capable of being extended to compile custom static assets, such as CoffeeScript or LESS.


Basic Usage
-----------
If using gradle, this plugin adds a series of tasks directly to your gradle plugin. All you have to do is `apply plugin:'asset-pipeline-core'` after confirming this is in the classpath of your `buildscript` block. i.e.:

```groovy
//Example build.gradle file
buildscript {
  repositories {
    mavenCentral()
  }
  dependencies {
    classpath 'com.bertramlabs.plugins.asset-pipeline-core:2.6.7'
	//Example additional LESS support
	//classpath 'com.bertramlabs.plugins.less-asset-pipeline:2.6.7'
  }
}

apply plugin: 'asset-pipeline'
//apply plugin: 'less-asset-pipeline'

assets {
  minifyJs = true
  minifyCss = true
}
```

Now that you have your build.gradle files. All you need to do is put files in your projects `assets/javascripts`, `assets/stylesheets`, `assets/images`, or whatever subdirectory you want.
When you run `gradle asset-precompile` these files will be processed and output into your `build/assets` folder by default.

You can also configure custom resolution paths for your project (STILL WORKING ON IT).

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
class AssetHelper {
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



Grails Documentation
-------------
http://bertramdev.github.io/asset-pipeline


Contributions
-------------
All contributions are of course welcome as this is an ACTIVE project. Any help with regards to reviewing platform compatibility, adding more tests, and general cleanup is most welcome.
Thanks to several people for suggestions throughout development. Notably: Brian Wheeler (@bdwheeler), Rick Jensen (@cdeszaq), Bobby Warner (@bobbywarner), Ted Naleid (@tednaleid) and more to follow I'm sure...

Additional Resources
--------------------
* TODO: Port grails extension plugins to gradle
