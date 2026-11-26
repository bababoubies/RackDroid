package org.rackdroid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Voice input for the rack -- milestone 1: speech to text.
 *
 * A mic button floats over the bottom-right corner of the rack. Tap it,
 * speak, and what the device heard comes back in a glass card you can
 * select or copy anywhere.
 *
 * Why a floating button and not a toolbar icon: the tool row is a fixed
 * 8-column grid of exactly sixteen buttons (addMidiButton), two full rows.
 * A seventeenth needs a third row and reshapes the whole card, which is a
 * far larger change than this feature warrants.
 *
 * Why an Application subclass does the attaching: MainActivity is 142 KB
 * of programmatic UI and every edit to it is a large, risky diff. An
 * ActivityLifecycleCallbacks hook gives the same result with MainActivity.kt
 * untouched. See RackApplication.kt.
 *
 * The recogniser is the platform's own (SpeechRecognizer), not a bundled
 * model and not a network call of ours. That matters for this app: its
 * whole privacy story is that it talks to nobody. The device decides where
 * the audio goes -- on many phones the platform service is on-device; on
 * some it is the assistant's cloud. Like microphone access itself, it is
 * the user's setting to make; this class only reports when that choice
 * needs a network the phone does not have.
 */
class VoiceAssistant private constructor(private val activity: MainActivity) {

	private val ui = Handler(Looper.getMainLooper())
	private var popup: PopupWindow? = null
	private var mic: ImageButton? = null
	private var recognizer: SpeechRecognizer? = null
	private var listening = false

	private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

	/** Puts the button on screen. Idempotent: calling it twice is a no-op,
	 * which matters because it is driven from onActivityResumed. */
	fun attach() {
		if (popup != null) {
			showButton()
			return
		}
		val button = ImageButton(activity).apply {
			setImageResource(R.drawable.ic_tb_mic)
			imageTintList = android.content.res.ColorStateList.valueOf(AppTheme.current.accent)
			scaleType = ImageView.ScaleType.FIT_CENTER
			contentDescription = "Voice input"
			setPadding(dp(11), dp(11), dp(11), dp(11))
			background = GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(AppTheme.withAlpha(AppTheme.current.surface, 88))
				setStroke(dp(2), AppTheme.current.accent)
			}
			setOnClickListener { onMicTapped() }
		}
		mic = button
		val p = PopupWindow(button, dp(52), dp(52))
		// Never steal keys from the canvas, same rule the toolbar follows.
		p.isFocusable = false
		popup = p
		showButton()
	}

	private fun showButton() {
		val p = popup ?: return
		if (p.isShowing) return
		val decor = activity.window.decorView
		if (decor.windowToken == null) {
			decor.post { runCatching { showButton() } }
			return
		}
		runCatching {
			// Clear of the navigation bar, and above where the module palette
			// opens along the bottom of the screen.
			val nav = ViewCompat.getRootWindowInsets(decor)
				?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
			p.showAtLocation(decor, Gravity.BOTTOM or Gravity.END, dp(14), nav + dp(104))
		}
	}

	/** Takes the button down before the activity goes away. */
	fun detach() {
		stopListening()
		runCatching { popup?.dismiss() }
		popup = null
		mic = null
	}

	// ---- Listening ----

	private fun onMicTapped() {
		if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
				!= PackageManager.PERMISSION_GRANTED) {
			activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
			return
		}
		if (listening) stopListening() else startListening()
	}

	/** Called from MainActivity.onRequestPermissionsResult for REQ_MIC. */
	fun onPermissionResult(granted: Boolean) {
		if (granted) {
			ui.post { runCatching { startListening() } }
		} else {
			toast("Microphone permission is needed for voice input.")
		}
	}

	fun startListening() {
		if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
			toast("No speech recognition service on this device.")
			return
		}
		stopListening()
		val r = runCatching { SpeechRecognizer.createSpeechRecognizer(activity) }.getOrNull()
		if (r == null) {
			toast("No speech recognition service on this device.")
			return
		}
		recognizer = r
		r.setRecognitionListener(object : RecognitionListener {
			override fun onReadyForSpeech(params: Bundle?) = setListeningUi(true)
			override fun onBeginningOfSpeech() {}
			override fun onRmsChanged(rmsdB: Float) {}
			override fun onBufferReceived(buffer: ByteArray?) {}
			override fun onEndOfSpeech() = setListeningUi(false)

			override fun onError(error: Int) {
				setListeningUi(false)
				releaseRecognizer()
				val message = when (error) {
					SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
					SpeechRecognizer.ERROR_NO_MATCH -> "Did not catch that"
					SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
						"Microphone permission is needed for voice input."
					SpeechRecognizer.ERROR_NETWORK,
					SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
						"Speech recognition needs a network connection on this device."
					SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy; try again in a moment."
					SpeechRecognizer.ERROR_CLIENT -> "Voice input was cancelled"
					else -> "Voice input failed ($error)"
				}
				// ERROR_CLIENT is what a deliberate cancel produces; it is not
				// worth a toast over the tap that caused it.
				if (error != SpeechRecognizer.ERROR_CLIENT) toast(message)
			}

			override fun onResults(results: Bundle?) {
				setListeningUi(false)
				val heard = results
					?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
					?.firstOrNull()
					?.trim()
					.orEmpty()
				releaseRecognizer()
				if (heard.isEmpty()) {
					toast("Did not catch that")
					return
				}
				showTranscript(heard)
			}

			override fun onPartialResults(partialResults: Bundle?) {}
			override fun onEvent(eventType: Int, params: Bundle?) {}
		})

		val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
			putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
				RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
			putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
			putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
			// A few candidates: the first is usually the best, but this is the
			// hook milestone 2 wants for "did you mean a different module".
			putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
			putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
		}
		runCatching { r.startListening(intent) }.onFailure {
			releaseRecognizer()
			setListeningUi(false)
			toast("Voice input could not start")
		}
		setListeningUi(true)
	}

	fun stopListening() {
		setListeningUi(false)
		val r = recognizer
		recognizer = null
		if (r != null) {
			runCatching { r.cancel() }
			runCatching { r.destroy() }
		}
	}

	private fun releaseRecognizer() {
		val r = recognizer ?: return
		recognizer = null
		runCatching { r.cancel() }
		runCatching { r.destroy() }
	}

	/** Amber at rest, the danger red while it is actually listening -- the
	 * palette the rest of the app already uses for "you are touching this". */
	private fun setListeningUi(on: Boolean) {
		listening = on
		val b = mic ?: return
		b.imageTintList = android.content.res.ColorStateList.valueOf(
			if (on) AppTheme.current.danger else AppTheme.current.accent)
		b.alpha = if (on) 0.6f else 1f
		b.contentDescription = if (on) "Listening" else "Voice input"
	}

	// ---- Result ----

	private fun showTranscript(text: String) {
		val col = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(20), dp(18), dp(20), dp(20))
		}
		col.addView(TextView(activity).apply {
			setText("Heard")
			setTextColor(AppTheme.current.accent)
			textSize = 17f
			typeface = Typeface.DEFAULT_BOLD
			setPadding(0, 0, 0, dp(8))
		})
		col.addView(TextView(activity).apply {
			setText("Copy it to paste anywhere, or tap the mic and say it again.")
			setTextColor(AppTheme.current.textSecondary)
			textSize = 13f
			setPadding(0, 0, 0, dp(12))
		})
		col.addView(TextView(activity).apply {
			this.text = text
			setTextColor(AppTheme.current.textPrimary)
			textSize = 16f
			setTextIsSelectable(true)
			setPadding(dp(14), dp(14), dp(14), dp(14))
			background = GradientDrawable().apply {
				cornerRadius = dp(12).toFloat()
				setColor(AppTheme.withAlpha(AppTheme.current.surfaceInset, 40))
			}
		})

		val dialog = AlertDialog.Builder(activity)
			.setPositiveButton("Copy") { _, _ -> copyToClipboard(text) }
			.setNegativeButton(android.R.string.cancel, null)
			.create()
		dialog.setView(ScrollView(activity).apply { addView(col) })
		dialog.window?.apply {
			setBackgroundDrawable(GradientDrawable().apply {
				cornerRadius = dp(24).toFloat()
				setColor(activity.glassCardColor())
				setStroke(dp(1), AppTheme.edge(18))
			})
			setDimAmount(0.4f)
		}
		// Same two hooks every other glass dialog in the app goes through:
		// glassify blurs what is behind it, trackTopWindow keeps a tutorial
		// card visible on top if one is open.
		runCatching { activity.trackTopWindow(dialog) }
		runCatching { activity.glassify(dialog.window) }
		dialog.show()
	}

	private fun copyToClipboard(text: String) {
		runCatching {
			val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
			cm.setPrimaryClip(ClipData.newPlainText("RackDroid voice", text))
			toast("Copied")
		}
	}

	private fun toast(message: String) {
		ui.post { runCatching { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() } }
	}

	companion object {
		/** Request code MainActivity routes back to onPermissionResult. */
		const val REQ_MIC = 4

		// One assistant per activity, and only a weak reference to it: the
		// activity is kept alive by the manifest across rotation, and this
		// hook fires again on every resume. Rebuilding the popup every time
		// would stack mic buttons on top of each other.
		private val assistants = java.util.WeakHashMap<MainActivity, VoiceAssistant>()

		/** Entry point for RackApplication. */
		fun attachTo(activity: Activity) {
			if (activity !is MainActivity) return
			val existing = assistants[activity]
			if (existing != null) {
				// The popup can be torn down with the window while the activity
				// itself survives (a language change restarts the process; a
				// rotation does not). Re-show rather than rebuild.
				runCatching { existing.attach() }
				return
			}
			val created = VoiceAssistant(activity)
			assistants[activity] = created
			runCatching { created.attach() }
		}

		fun detachFrom(activity: Activity) {
			if (activity !is MainActivity) return
			assistants.remove(activity)?.detach()
		}

		fun onPermissionResultFor(activity: Activity, granted: Boolean) {
			if (activity !is MainActivity) return
			assistants[activity]?.onPermissionResult(granted)
		}

		fun isKnown(activity: Activity): Boolean =
			activity is MainActivity && assistants.containsKey(activity)

		/** Nothing here holds the activity strongly; this exists so the
		 *  WeakReference import is not a lie about the design. */
		@Suppress("unused")
		private val self = WeakReference<VoiceAssistant>(null)
	}
}
