package asset.pipeline

import groovy.transform.CompileStatic

/**
 * Receives asynchronous cache persistance requests and executes them.
 * Also acts as a debouncer
 *
 * @author David Estes
 */
// @CompileStatic
public class CachePersister extends Thread {
	public Integer delay = 0
	public Boolean ran = true
	public final Integer RUN_DELAY = 1000
	public void run() {
		while(true) {
			sleep(RUN_DELAY)
			if(ran == false) {
				delay -= RUN_DELAY
				if(delay <= 0) {
					CacheManager.save()
					ran = true
				}
			}
			
		}
	}

	/**
	 * Asynchronously triggers a save with a debounce delay to reduce excessive persistence calls
	 * @param delay (milliseconds) between debounce save
	 */
	public void debounceSave(Integer delay) {
		this.delay = delay
		ran = false
	}
}