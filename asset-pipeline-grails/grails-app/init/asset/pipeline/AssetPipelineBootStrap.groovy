package asset.pipeline

import grails.core.GrailsApplication

class AssetPipelineBootStrap {

    GrailsApplication grailsApplication

    def init = {
        def storagePath = grailsApplication.config.getProperty('grails.assets.storagePath', String)
        if (!storagePath) {
            return
        }
        def manifest = AssetPipelineConfigHolder.manifest

        if(manifest) {
            def storageFile = new File(storagePath)
            storageFile.mkdirs()
            manifest.stringPropertyNames().each { propertyName ->
                def propertyValue = manifest.getProperty(propertyName)
                def res = grailsApplication.getParentContext().getResource("assets/${propertyValue}")
                if(!res.exists()) {
                    res = grailsApplication.getParentContext().getResource("classpath:assets/${propertyValue}")
                }
                def outputFile = new File(storagePath, propertyName)
                def parentFile = new File(outputFile.parent)
                parentFile.mkdirs()
                def outputDigestFile = new File(storagePath, propertyValue)
                copyFile(res.inputStream,outputFile,outputDigestFile)
                def gzRes = grailsApplication.getParentContext().getResource("assets/${propertyValue}.gz")
                if(!gzRes.exists()) {
                    gzRes = grailsApplication.getParentContext().getResource("classpath:assets/${propertyValue}.gz")
                }
                if(gzRes.exists()) {
                    def outputGzFile = new File(storagePath, "${propertyName}.gz")
                    def outputGzDigestFile = new File(storagePath, "${propertyValue}.gz")
                    copyFile(gzRes.inputStream,outputGzFile,outputGzDigestFile)
                }
            }
            def manifestFile = new File(storagePath,'manifest.properties')
            manifest.store(manifestFile.newWriter(), '')
        }
    }

    def copyFile(sourceStream, targetFile, digestFile) {
        try {
            if(!targetFile.exists()) {
                targetFile.createNewFile()
            }
            if(!digestFile?.exists()) {
                digestFile?.createNewFile()
            }
            def outputFileStream = targetFile.newOutputStream()
            def outputDigestFileStream = digestFile?.newOutputStream()
            byte[] buffer = new byte[8192]
            int nRead
            while((nRead = sourceStream.read(buffer, 0, buffer.length)) != -1) {
                // noop (just to complete the stream)
                outputFileStream?.write(buffer, 0, nRead);
                outputDigestFileStream?.write(buffer, 0, nRead);
            }
            outputFileStream.flush()
            outputFileStream.close()
            outputDigestFileStream.flush()
            outputDigestFileStream.close()
            try {
                targetFile.setReadable(true,false)
                targetFile.setExecutable(true,false)
                targetFile.setWritable(true)
                digestFile?.setReadable(true,false)
                digestFile?.setExecutable(true,false)
                digestFile?.setWritable(true)
            } catch (ignore) {
               // attempting permission set
            }
        } finally {
            sourceStream.close()
        }
    }
}
