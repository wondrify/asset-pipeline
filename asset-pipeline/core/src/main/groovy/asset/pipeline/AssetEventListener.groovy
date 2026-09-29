package asset.pipeline

import groovy.transform.CompileStatic

@CompileStatic
interface AssetEventListener {

    void triggerEvent(String eventName, String message)
}
