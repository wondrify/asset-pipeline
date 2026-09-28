package asset.pipeline.fs

import groovy.transform.CompileStatic

import java.util.zip.ZipEntry

@CompileStatic
public class JarAssetEntry{
	ZipEntry zipEntry
	JarAssetResolver resolver

}
