Asset Pipeline GSP
==================
The tag libraries the Grails plugin carries also work in a Spring Boot application that renders GSP
without being a Grails application. Adding this library and `asset-pipeline-spring-boot` to such an
application is enough: the tag libraries become beans of its context, the filter serves what the
pipeline compiled, and `<asset:javascript>` and `<asset:stylesheet>` behave as they do in a Grails
application. Both are needed - this module contributes the tag libraries, the other one configures
the pipeline they read.

The `grails.assets` settings an application writes reach the pipeline, so the urls the tag
libraries build honour a configured `mapping`. The filter `asset-pipeline-spring-boot` registers
serves from `/assets/*` regardless, so an application that changes the mapping also has to
register its own `assetPipelineFilterBean` on the matching path.

A Grails application is unaffected: it finds these tag libraries as artefacts of the plugin, the
way it always has.
