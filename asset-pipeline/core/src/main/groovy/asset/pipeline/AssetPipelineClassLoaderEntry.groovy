package asset.pipeline

/**
 * Holds config for nested class loaders for dynamically loading, and unloading assets
 * @author David Estes
 */
class AssetPipelineClassLoaderEntry {
	public ClassLoader classLoader
	Properties manifest
	ClassLoader classLoader 

	static final String MANIFEST_LOCATION = "assets/manifest.properties"

	public AssetPipelineClassLoaderEntry(ClassLoader classLoader) {
		this.classLoader = classLoader
		URL res = classLoader.getResource(MANIFEST_LOCATION)
		if(res) {
			def manifestProps = new Properties()
			URLConnection resConnection = res.openConnection()
			resConnection.setUseCaches(false)
			def propertiesStream = resConnection.getInputStream()
			manifestProps.load(propertiesStream)
			manifest = manifestProps
		}
	}

}