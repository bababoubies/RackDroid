package org.rackdroid

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Wires features onto MainActivity without editing it.
 *
 * MainActivity is a NativeActivity holding 142 KB of programmatically built
 * UI -- the toolbar, palette, menus and dialogs are all constructed in code,
 * and there is no layout XML to hang a new control on. Every edit to that
 * file is a large diff against a file this project could not test from here.
 *
 * ActivityLifecycleCallbacks gives the same reach from outside: the mic
 * button is put up when an activity resumes and taken down when it is
 * destroyed. Nothing else in the app is touched, so nothing else can break
 * because of it.
 *
 * The one thing this cannot do is answer a permission result, because
 * ActivityLifecycleCallbacks has no such callback -- that is handled in
 * MainActivity.onRequestPermissionsResult, one small branch at REQ_MIC.
 */
class RackApplication : Application() {

	override fun onCreate() {
		super.onCreate()
		registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
			override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
			override fun onActivityStarted(activity: Activity) {}
			override fun onActivityResumed(activity: Activity) {
				VoiceAssistant.attachTo(activity)
			}
			override fun onActivityPaused(activity: Activity) {
				// Not detach(): a pause is a notification shade or a dialog in
				// front of us, not the end of the session, and tearing the
				// button down there would make it vanish and come back.
			}
			override fun onActivityStopped(activity: Activity) {}
			override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
			override fun onActivityDestroyed(activity: Activity) {
				VoiceAssistant.detachFrom(activity)
			}
		})
	}
}
