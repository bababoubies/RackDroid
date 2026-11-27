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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Voice input for the rack -- milestones 1 & 2: speech to text + patch generation.
 *
 * A mic button floats over the bottom-right corner of the rack. Tap it,
 * speak, and what the device heard comes back in a glass card with an
 * *editable* text field plus a "Generate Patch" action that turns the
 * (possibly edited) text into a VCV .vcv patch and loads it into the rack
 * through the app's existing patch-import intent.
 */
class VoiceAssistant private constructor(private val activity: MainActivity) {

	private val ui = Handler(Looper.getMainLooper())
	private var popup: PopupWindow? = null
	private var mic: ImageButton? = null
	private var recognizer: SpeechRecognizer? = null
	private var listening = false

	private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

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
			val nav = ViewCompat.getRootWindowInsets(decor)
				?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
			p.showAtLocation(decor, Gravity.BOTTOM or Gravity.END, dp(14), nav + dp(104))
		}
	}

	fun detach() {
		stopListening()
		runCatching { popup?.dismiss() }
		popup = null
		mic = null
	}

	// ---- Listening ----

	private fun onMicTapped() {
		if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
			!= PackageManager.PERMISSION_GRANTED
		) {
			activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
			return
		}
		if (listening) stopListening() else startListening()
	}

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
					SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
						"The recogniser is busy; try again in a moment."
					SpeechRecognizer.ERROR_CLIENT -> "Voice input was cancelled"
					else -> "Voice input failed ($error)"
				}
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

	private fun setListeningUi(on: Boolean) {
		listening = on
		val b = mic ?: return
		b.imageTintList = android.content.res.ColorStateList.valueOf(
			if (on) AppTheme.current.danger else AppTheme.current.accent)
		b.alpha = if (on) 0.6f else 1f
		b.contentDescription = if (on) "Listening" else "Voice input"
	}

	// ---- Result: editable transcript + patch generation ----

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

		val edit = EditText(activity).apply {
			setText(text)
			setTextColor(AppTheme.current.textPrimary)
			textSize = 16f
			setPadding(dp(14), dp(14), dp(14), dp(14))
			background = GradientDrawable().apply {
				cornerRadius = dp(12).toFloat()
				setColor(AppTheme.withAlpha(AppTheme.current.surfaceInset, 40))
			}
			setSingleLine(false)
			setLines(3)
			setMaxLines(8)
		}
		col.addView(edit)

		col.addView(TextView(activity).apply {
			setText("Edit anything misheard, then tap Generate Patch to build a .vcv patch and load it into the rack.")
			setTextColor(AppTheme.current.textSecondary)
			textSize = 12f
			setPadding(0, dp(6), 0, dp(8))
		})

		val btnRow = LinearLayout(activity).apply {
			orientation = LinearLayout.HORIZONTAL
			setGravity(Gravity.CENTER)
			setPadding(0, dp(6), 0, dp(6))
		}

		// Declared before the button lambdas that dismiss it.
		lateinit var dialog: AlertDialog

		val genButton = Button(activity).apply {
			text = "Generate Patch"
			setTextSize(14f)
			setOnClickListener {
				val finalText = edit.text.toString().trim()
				if (finalText.isEmpty()) {
					toast("Say something first")
				} else {
					dialog.dismiss()
					generateAndLoadPatch(finalText)
				}
			}
		}

		val copyButton = Button(activity).apply {
			text = "Copy"
			setTextSize(14f)
			setOnClickListener {
				copyToClipboard(edit.text.toString())
				toast("Copied")
				dialog.dismiss()
			}
		}

		val cancelButton = Button(activity).apply {
			text = "Cancel"
			setTextSize(14f)
			setOnClickListener { dialog.dismiss() }
		}

		btnRow.addView(genButton)
		btnRow.addView(copyButton)
		btnRow.addView(cancelButton)
		col.addView(btnRow)

		dialog = AlertDialog.Builder(activity).create()
		dialog.setView(ScrollView(activity).apply { addView(col) })
		dialog.window?.apply {
			setBackgroundDrawable(GradientDrawable().apply {
				cornerRadius = dp(24).toFloat()
				setColor(activity.glassCardColor())
				setStroke(dp(1), AppTheme.edge(18))
			})
			setDimAmount(0.4f)
		}
		runCatching { activity.trackTopWindow(dialog) }
		runCatching { activity.glassify(dialog.window) }
		dialog.show()
	}

	private fun generateAndLoadPatch(command: String) {
		val patchJson = VoicePatchGenerator.create().generatePatch(command)
		val file = savePatchFile(patchJson)
		if (file == null) {
			toast("Could not save patch file")
			return
		}
		toast("Patch generated: ${file.name}")
		loadPatchFile(file)
	}

	private fun savePatchFile(json: String): File? {
		return try {
			val patchesDir = File(activity.filesDir, "user/patches")
			if (!patchesDir.exists()) patchesDir.mkdirs()
			val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
				.format(java.util.Date())
			val file = File(patchesDir, "voice_$ts.vcv")
			file.writeText(json)
			file
		} catch (e: Exception) {
			toast("Save failed: ${e.message}")
			null
		}
	}

	private fun loadPatchFile(file: File) {
		try {
			val authority = "${activity.packageName}.fileprovider"
			val uri = FileProvider.getUriForFile(activity, authority, file)
			val intent = Intent(Intent.ACTION_VIEW).apply {
				setDataAndType(uri, "application/octet-stream")
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
				addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
				putExtra(Intent.EXTRA_TITLE, file.name)
			}
			activity.startActivity(intent)
		} catch (e: Exception) {
			toast("Load failed: ${e.message}")
		}
	}

	private fun copyToClipboard(text: String) {
		runCatching {
			val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
			cm.setPrimaryClip(ClipData.newPlainText("RackDroid voice", text))
		}
	}

	private fun toast(message: String) {
		ui.post { runCatching { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() } }
	}

	companion object {
		const val REQ_MIC = 4

		private val assistants = java.util.WeakHashMap<MainActivity, VoiceAssistant>()

		fun attachTo(activity: Activity) {
			if (activity !is MainActivity) return
			val existing = assistants[activity]
			if (existing != null) {
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

		@Suppress("unused")
		private val self = WeakReference<VoiceAssistant>(null)
	}
}
