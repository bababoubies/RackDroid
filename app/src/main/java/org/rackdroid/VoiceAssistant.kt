import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.Locale
import java.util.WeakHashMap
import java.util.Date

/** Voice input for the rack -- speech to text plus patch generation.

 A mic button floats over the bottom-right corner of the rack. Tap it, speak, and the words come back in a glass card with an editable text field and a "Generate Patch" action that turns the (possibly edited) text into a VCV .vcv patch and loads it through the app's existing patch import path.

 Patch files are written to the app's external files dir so file managers (ZArchiver, Solid Explorer, etc.) can see them. FileProvider external-path maps that directory into a user-visible URI.
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
        val button = ImageButton(activity)
        button.setImageResource(R.drawable.ic_tb_mic)
        button.imageTintList = android.content.res.ColorStateList.valueOf(AppTheme.current.accent)
        button.scaleType = ImageView.ScaleType.FIT_CENTER
        button.contentDescription = "Voice input"
        button.setPadding(dp(11), dp(11), dp(11), dp(11))
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(AppTheme.withAlpha(AppTheme.current.surface, 88))
        bg.setStroke(dp(2), AppTheme.current.accent)
        button.background = bg
        button.setOnClickListener { onMicTapped() }

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
        val rec = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setListeningUi(true)
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                setListeningUi(false)
            }

            override fun onError(error: Int) {
                setListeningUi(false)
                releaseRecognizer()
                val message = when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> "Did not catch that"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice input."
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection on this device."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy; try again in a moment."
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

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        runCatching { rec.startListening(intent) }.onFailure {
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
            if (on) AppTheme.current.danger else AppTheme.current.accent
        )
        b.alpha = if (on) 0.6f else 1f
        b.contentDescription = if (on) "Listening" else "Voice input"
    }

    // ---- Result: editable transcript plus patch generation ----

    private fun showTranscript(heardText: String) {
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(18), dp(20), dp(20))

        val header = TextView(activity)
        header.setText("Heard")
        header.setTextColor(AppTheme.current.accent)
        header.textSize = 17f
        header.typeface = Typeface.DEFAULT_BOLD
        header.setPadding(0, 0, 0, dp(8))
        col.addView(header)

        val edit = EditText(activity)
        edit.setText(heardText)
        edit.setTextColor(AppTheme.current.textPrimary)
        edit.textSize = 16f
        edit.setPadding(dp(14), dp(14), dp(14), dp(14))
        val editBg = GradientDrawable()
        editBg.cornerRadius = dp(12).toFloat()
        editBg.setColor(AppTheme.withAlpha(AppTheme.current.surfaceInset, 40))
        edit.background = editBg
        edit.setSingleLine(false)
        edit.setLines(3)
        edit.setMaxLines(8)
        col.addView(edit)

        val hint = TextView(activity)
        hint.setText("Edit anything misheard, then tap Generate Patch to build a .vcv patch and load it into the rack.")
        hint.setTextColor(AppTheme.current.textSecondary)
        hint.textSize = 12f
        hint.setPadding(0, dp(6), 0, dp(8))
        col.addView(hint)

        val btnRow = LinearLayout(activity)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.gravity = Gravity.CENTER
        btnRow.setPadding(0, dp(6), 0, dp(6))

        val dialog = AlertDialog.Builder(activity).create()

        val genButton = Button(activity)
        genButton.setText("Generate Patch")
        genButton.setTextSize(14f)
        genButton.setOnClickListener {
            val finalText = edit.text.toString().trim()
            if (finalText.isEmpty()) {
                toast("Say something first")
            } else {
                dialog.dismiss()
                generateAndLoadPatch(finalText)
            }
        }

        val copyButton = Button(activity)
        copyButton.setText("Copy")
        copyButton.setTextSize(14f)
        copyButton.setOnClickListener {
            copyToClipboard(edit.text.toString())
            toast("Copied")
            dialog.dismiss()
        }

        val cancelButton = Button(activity)
        cancelButton.setText("Cancel")
        cancelButton.setTextSize(14f)
        cancelButton.setOnClickListener { dialog.dismiss() }

        btnRow.addView(genButton)
        btnRow.addView(copyButton)
        btnRow.addView(cancelButton)
        col.addView(btnRow)

        val scroll = ScrollView(activity)
        scroll.addView(col)
        dialog.setView(scroll)

        val win = dialog.window
        if (win != null) {
            val cardBg = GradientDrawable()
            cardBg.cornerRadius = dp(24).toFloat()
            cardBg.setColor(activity.glassCardColor())
            cardBg.setStroke(dp(1), AppTheme.edge(18))
            win.setBackgroundDrawable(cardBg)
            win.setDimAmount(0.4f)
            runCatching { activity.glassify(win) }
        }
        runCatching { activity.trackTopWindow(dialog) }
        dialog.show()
    }

    private fun generateAndLoadPatch(command: String) {
        val patchJson = VoicePatchGenerator.create().generatePatch(command)
        val file = savePatchFile(patchJson)
        if (file == null) {
            toast("Could not save patch file")
            return
        }
        toast("Patch generated: " + file.name)
        loadPatchFile(file)
    }

    private fun savePatchFile(json: String): File? {
        // Write to app-specific external files dir so file managers can see it.
        // Use getExternalFilesDir first (works on all Android versions);
        // fall back to filesDir if that fails for any reason.
        val context: Context = activity.applicationContext
        val patchesDir = context.getExternalFilesDir(null)?.resolve("patches")
            ?: activity.filesDir.resolve("patches")
        if (!patchesDir.exists()) patchesDir.mkdirs()
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val file = File(patchesDir, "voice_" + ts + ".vcv")
        file.writeText(json)
        file
    }

    private fun loadPatchFile(file: File) {
        try {
            val authority = activity.packageName + ".fileprovider"
            val uri = FileProvider.getUriForFile(activity, authority, file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/octet-stream")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            intent.putExtra(Intent.EXTRA_TITLE, file.name)
            activity.startActivity(intent)
        } catch (e: Exception) {
            toast("Load failed: " + (e.message ?: "unknown"))
        }
    }

    private fun copyToClipboard(value: String) {
        runCatching {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("RackDroid voice", value))
        }
    }

    private fun toast(message: String) {
        ui.post { runCatching { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() } }
    }

    companion object {
        const val REQ_MIC = 4

        private val assistants = WeakHashMap<Activity, VoiceAssistant>()

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
    }
}