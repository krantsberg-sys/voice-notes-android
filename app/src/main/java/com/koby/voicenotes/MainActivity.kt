package com.koby.voicenotes

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * המסך הראשי. ההקלטה והתמלול רצים ב-RecordingService (ברקע), והמסך רק מציג ושולט.
 * סיכום ותרגום: Claude דרך ה-API של Anthropic.
 */
class MainActivity : Activity() {

    companion object {
        // אם המודל הזה לא עובד לך, אפשר לשנות אותו בהגדרות האפליקציה (למשל claude-haiku-5-5 לזול יותר).
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        const val REQ_PERMS = 1
        const val REQ_PICK_AUDIO = 2
        // ברירת מחדל: Groq (חינמי עם מגבלות). למעבר ל-OpenAI: כתובת https://api.openai.com/v1 ומודל whisper-1
        const val DEFAULT_TRANSCRIBE_BASE = "https://api.groq.com/openai/v1"
        const val DEFAULT_TRANSCRIBE_MODEL = "whisper-large-v3-turbo"
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var langTag = "he-IL"
    private var askedNotifications = false
    private var syncing = false

    private lateinit var recBtn: Button
    private lateinit var extendBtn: Button
    private lateinit var timerView: TextView
    private lateinit var warnView: TextView
    private lateinit var liveView: TextView
    private lateinit var audioInfo: TextView
    private lateinit var audioRow: LinearLayout
    private lateinit var lastTransBtn: Button
    private lateinit var pickTransBtn: Button
    private lateinit var transcript: EditText
    private lateinit var summaryView: TextView
    private lateinit var translationView: TextView
    private lateinit var sumBtn: Button
    private lateinit var toEnBtn: Button
    private lateinit var toHeBtn: Button
    private lateinit var sourceSpinner: Spinner

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("voicenotes", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // כותרת + הגדרות
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply { text = "תמלול וסיכום"; textSize = 22f; setTextColor(Color.BLACK) }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("הגדרות") { showSettings() })
        root.addView(header)

        // שפת הדיבור
        root.addView(label("שפת הדיבור"))
        val langSpinner = Spinner(this)
        langSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("עברית", "English"))
        langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                langTag = if (position == 0) "he-IL" else "en-US"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(langSpinner)

        // הקלטה
        recBtn = Button(this).apply {
            text = "● התחל הקלטה"
            textSize = 18f
            setOnClickListener { if (RecState.recording) stopRecording() else startRecording() }
        }
        root.addView(recBtn, topMargin(12))
        timerView = TextView(this).apply { text = "00:00"; textSize = 16f; gravity = Gravity.CENTER }
        root.addView(timerView)
        warnView = TextView(this).apply {
            textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#C0392B")); visibility = View.GONE
        }
        root.addView(warnView)
        extendBtn = Button(this).apply {
            text = "הארך ב-30 דקות"
            visibility = View.GONE
            setOnClickListener { extendRecording() }
        }
        root.addView(extendBtn)
        liveView = TextView(this).apply { setTextColor(Color.GRAY); textSize = 14f; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        root.addView(liveView)

        // קובץ השמע שנשמר
        audioInfo = TextView(this).apply { textSize = 13f; setTextColor(Color.DKGRAY); visibility = View.GONE }
        root.addView(audioInfo)
        audioRow = row(
            smallButton("נגן שמע") { playAudio() },
            smallButton("שתף שמע") { shareAudio() }
        ).apply { visibility = View.GONE }
        root.addView(audioRow)

        // תמלול קובץ שמע דרך OpenAI (לא Claude)
        root.addView(label("תמלול מקובץ שמע (Whisper)"))
        lastTransBtn = Button(this).apply {
            text = "תמלל את ההקלטה האחרונה"
            setOnClickListener {
                val u = RecState.audioUri
                if (u == null) toast("אין הקלטה שמורה") else transcribeAudio(Uri.parse(u))
            }
        }
        pickTransBtn = Button(this).apply {
            text = "תמלל קובץ שמע אחר…"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                }
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_PICK_AUDIO)
            }
        }
        root.addView(lastTransBtn)
        root.addView(pickTransBtn)

        // תמלול
        root.addView(label("תמלול (אפשר לערוך)"))
        transcript = EditText(this).apply {
            hint = "התמלול יופיע כאן. אפשר גם להקליד או להדביק טקסט."
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!syncing) RecState.transcript = s?.toString() ?: ""
                }
            })
        }
        root.addView(transcript)
        root.addView(row(
            smallButton("העתק") { copy(transcript.text.toString()) },
            smallButton("שתף") { share(transcript.text.toString()) },
            smallButton("נקה") { clearAll() }
        ))

        // סיכום
        root.addView(label("סיכום לדיון"))
        sumBtn = Button(this).apply { text = "סכם"; setOnClickListener { doSummary() } }
        root.addView(sumBtn)
        summaryView = outputView("הסיכום יופיע כאן")
        root.addView(summaryView)
        root.addView(row(
            smallButton("העתק") { copy(summaryView.text.toString()) },
            smallButton("שתף") { share(summaryView.text.toString()) }
        ))

        // תרגום
        root.addView(label("תרגום (עברית ↔ אנגלית)"))
        sourceSpinner = Spinner(this)
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("לתרגם את התמלול", "לתרגם את הסיכום"))
        root.addView(sourceSpinner)
        toEnBtn = Button(this).apply { text = "תרגם לאנגלית"; setOnClickListener { doTranslate("English") } }
        toHeBtn = Button(this).apply { text = "תרגם לעברית"; setOnClickListener { doTranslate("Hebrew") } }
        root.addView(row(toEnBtn, toHeBtn))
        translationView = outputView("התרגום יופיע כאן")
        root.addView(translationView)
        root.addView(row(
            smallButton("העתק") { copy(translationView.text.toString()) },
            smallButton("שתף") { share(translationView.text.toString()) }
        ))

        setContentView(ScrollView(this).apply { addView(root) })

        // שחזור מצב (אם המסך נסגר וההקלטה המשיכה ברקע)
        syncTranscriptFromState()
        summaryView.text = RecState.summary
        translationView.text = RecState.translation
        if (RecState.audioUri == null) {
            RecState.audioUri = prefs.getString("lastAudio", null)
            RecState.audioName = prefs.getString("lastAudioName", null)
        }

        if (prefs.getString("key", "").isNullOrBlank()) showSettings()
    }

    override fun onStart() {
        super.onStart()
        RecState.listener = { onRecChanged() }
        onRecChanged()
        handler.post(uiTicker)
    }

    override fun onStop() {
        RecState.listener = null
        handler.removeCallbacks(uiTicker)
        super.onStop()
    }

    private val uiTicker = object : Runnable {
        override fun run() {
            refreshRecUi()
            handler.postDelayed(this, 500)
        }
    }

    private fun onRecChanged() {
        if (transcript.text.toString() != RecState.transcript) syncTranscriptFromState()
        liveView.text = RecState.live
        RecState.message?.let { toast(it); RecState.message = null }
        refreshRecUi()
    }

    private fun syncTranscriptFromState() {
        syncing = true
        transcript.setText(RecState.transcript)
        transcript.setSelection(transcript.text.length)
        syncing = false
    }

    private fun refreshRecUi() {
        if (RecState.audioUri != null) {
            audioInfo.visibility = View.VISIBLE
            audioRow.visibility = View.VISIBLE
            audioInfo.text = "שמע שמור: ${RecState.audioName} (תיקיית Music/VoiceNotes)"
        } else {
            audioInfo.visibility = View.GONE
            audioRow.visibility = View.GONE
        }
        if (RecState.recording) {
            val now = System.currentTimeMillis()
            val left = RecState.deadline - now
            recBtn.text = "■ עצור הקלטה"
            timerView.text = "מוקלט ${RecState.mmss(now - RecState.startedAt)}  ·  נותרו ${RecState.mmss(left)}"
            extendBtn.visibility = View.VISIBLE
            if (left <= RecordingService.WARN_MS) {
                warnView.visibility = View.VISIBLE
                warnView.text = "ההקלטה תיפסק בעוד ${RecState.mmss(left)}"
            } else {
                warnView.visibility = View.GONE
            }
        } else {
            recBtn.text = "● התחל הקלטה"
            extendBtn.visibility = View.GONE
            warnView.visibility = View.GONE
        }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.DKGRAY)
        layoutParams = topMargin(16)
    }

    private fun outputView(empty: String) = TextView(this).apply {
        hint = empty
        textSize = 16f
        setTextIsSelectable(true)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(Color.parseColor("#F0F0F0"))
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        minLines = 3
    }

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun topMargin(d: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(d) }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun copy(text: String) {
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        toast("הועתק")
    }

    private fun share(text: String) {
        if (text.isBlank()) return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, null))
    }

    private fun clearAll() {
        if (RecState.recording) stopRecording()
        RecState.transcript = ""; RecState.summary = ""; RecState.translation = ""; RecState.live = ""
        syncTranscriptFromState()
        summaryView.text = ""
        translationView.text = ""
        liveView.text = ""
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val keyEdit = EditText(this).apply {
            hint = "מפתח API של Anthropic (sk-ant-...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val modelEdit = EditText(this).apply {
            hint = "שם מודל"
            setText(prefs.getString("model", DEFAULT_MODEL))
        }
        val liveCheck = CheckBox(this).apply {
            text = "תמלול חי בזמן ההקלטה (אם הוא מפריע לשמע, הוא יכבה אוטומטית)"
            isChecked = prefs.getBoolean("live", true)
        }
        val tbaseEdit = EditText(this).apply {
            hint = "כתובת שירות התמלול"
            setText(prefs.getString("tbase", DEFAULT_TRANSCRIBE_BASE))
        }
        val openaiKeyEdit = EditText(this).apply {
            hint = "מפתח API של שירות התמלול (Groq: gsk_...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("openai_key", ""))
        }
        val tmodelEdit = EditText(this).apply {
            hint = "מודל תמלול"
            setText(prefs.getString("tmodel", DEFAULT_TRANSCRIBE_MODEL))
        }
        box.addView(keyEdit)
        box.addView(modelEdit)
        box.addView(tbaseEdit)
        box.addView(openaiKeyEdit)
        box.addView(tmodelEdit)
        box.addView(liveCheck)
        AlertDialog.Builder(this)
            .setTitle("הגדרות")
            .setMessage("המפתח נשמר רק בטלפון הזה. אפשר להפיק מפתח באתר console.anthropic.com")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("שמור") { _, _ ->
                prefs.edit()
                    .putString("key", keyEdit.text.toString().trim())
                    .putString("model", modelEdit.text.toString().trim().ifEmpty { DEFAULT_MODEL })
                    .putBoolean("live", liveCheck.isChecked)
                    .putString("tbase", tbaseEdit.text.toString().trim().ifEmpty { DEFAULT_TRANSCRIBE_BASE })
                    .putString("openai_key", openaiKeyEdit.text.toString().trim())
                    .putString("tmodel", tmodelEdit.text.toString().trim().ifEmpty { DEFAULT_TRANSCRIBE_MODEL })
                    .apply()
            }
            .setNegativeButton("סגור", null)
            .show()
    }

    // ---------------------------------------------------------------- Recording control (the work is done by RecordingService)

    private fun startRecording() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("אין שירות זיהוי דיבור במכשיר. ודא ש-Google מותקן ומעודכן.")
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            askedNotifications = true
            requestPermissions(need.toTypedArray(), REQ_PERMS)
            return
        }
        val i = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_LANG, langTag)
        startForegroundService(i)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                toast("בלי הרשאת התראות לא תופיע התראת ההקלטה והתראת הסיום. מומלץ לאשר בהגדרות המכשיר.")
            }
            startRecording()
        } else {
            toast("בלי הרשאת מיקרופון אי אפשר להקליט. אפשר עדיין להקליד טקסט.")
        }
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    private fun extendRecording() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_EXTEND))
        toast("ההקלטה הוארכה ב-30 דקות")
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_AUDIO && resultCode == RESULT_OK) {
            data?.data?.let { transcribeAudio(it) }
        }
    }

    /** תמלול קובץ שמע דרך OpenAI. הקובץ עולה לשרתי OpenAI. */
    private fun transcribeAudio(uri: Uri) {
        val key = prefs.getString("openai_key", "")?.trim().orEmpty()
        if (key.isEmpty()) { toast("חסר מפתח לשירות התמלול. הוסף אותו בהגדרות."); showSettings(); return }
        val model = prefs.getString("tmodel", DEFAULT_TRANSCRIBE_MODEL)?.ifBlank { DEFAULT_TRANSCRIBE_MODEL }
            ?: DEFAULT_TRANSCRIBE_MODEL
        val base = prefs.getString("tbase", DEFAULT_TRANSCRIBE_BASE)?.ifBlank { DEFAULT_TRANSCRIBE_BASE }
            ?: DEFAULT_TRANSCRIBE_BASE
        val lang = if (langTag == "he-IL") "he" else "en"

        val oldLast = lastTransBtn.text
        val oldPick = pickTransBtn.text
        lastTransBtn.isEnabled = false
        pickTransBtn.isEnabled = false

        Thread {
            val result = runCatching {
                Transcriber.run(applicationContext, uri, lang, base, key, model) { p ->
                    runOnUiThread { lastTransBtn.text = p }
                }
            }
            runOnUiThread {
                lastTransBtn.isEnabled = true
                pickTransBtn.isEnabled = true
                lastTransBtn.text = oldLast
                pickTransBtn.text = oldPick
                result.onSuccess { deliverTranscript(it) }
                result.onFailure { toast(it.message ?: "שגיאה בתמלול") }
            }
        }.start()
    }

    private fun deliverTranscript(text: String) {
        if (text.isBlank()) { toast("לא התקבל טקסט מהתמלול"); return }
        fun set(t: String) { RecState.transcript = t; syncTranscriptFromState() }
        val cur = transcript.text.toString().trim()
        if (cur.isEmpty()) { set(text); return }
        AlertDialog.Builder(this)
            .setTitle("התמלול מוכן")
            .setMessage("כבר יש טקסט בשדה התמלול. מה לעשות?")
            .setPositiveButton("החלף") { _, _ -> set(text) }
            .setNeutralButton("הוסף בסוף") { _, _ -> set(cur + "\n\n" + text) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun playAudio() {
        val u = RecState.audioUri ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(u), "audio/aac")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (e: Exception) {
            toast("לא נמצאה אפליקציה שמנגנת את הקובץ. הוא שמור בתיקייה Music/VoiceNotes.")
        }
    }

    private fun shareAudio() {
        val u = RecState.audioUri ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "audio/aac"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(u))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, null))
    }

    // ---------------------------------------------------------------- Claude

    private fun doSummary() {
        val text = transcript.text.toString().trim()
        if (text.isEmpty()) { toast("אין תמלול לסכם"); return }
        val system = "You summarize recorded discussions and meetings for the person who recorded them. " +
            "The transcript comes from automatic speech recognition: it may contain recognition errors and missing punctuation; " +
            "silently fix what is clear from context. " +
            "Write the summary in the same language as the transcript (Hebrew or English). " +
            "Plain text only, no markdown symbols. Use this structure, with section titles in the summary's language and " +
            "'•' for bullets, and skip any section that would be empty: " +
            "a one-line topic; main points; decisions; action items (with the owner and deadline if mentioned); open questions."
        callClaude(system, "<transcript>\n$text\n</transcript>", sumBtn, summaryView)
    }

    private fun doTranslate(target: String) {
        val src = if (sourceSpinner.selectedItemPosition == 0) transcript.text.toString() else summaryView.text.toString()
        if (src.isBlank()) { toast("אין טקסט לתרגום"); return }
        val btn = if (target == "English") toEnBtn else toHeBtn
        val system = "Translate the user's text into $target. Preserve meaning, tone, names and structure. " +
            "If the source is already in $target, just return it cleaned up. " +
            "Return only the translation as plain text, with no notes and no markdown."
        callClaude(system, src, btn, translationView)
    }

    private fun callClaude(system: String, user: String, button: Button, out: TextView) {
        val key = prefs.getString("key", "")?.trim().orEmpty()
        if (key.isEmpty()) { toast("חסר מפתח API. הוסף אותו בהגדרות."); showSettings(); return }
        val model = prefs.getString("model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL

        val oldText = button.text
        button.isEnabled = false
        button.text = "עובד…"
        out.text = ""

        Thread {
            val result = runCatching {
                val body = JSONObject()
                    .put("model", model)
                    .put("max_tokens", 2500)
                    .put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))

                val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 20000
                c.readTimeout = 120000
                c.doOutput = true
                c.setRequestProperty("x-api-key", key)
                c.setRequestProperty("anthropic-version", "2023-06-01")
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (code !in 200..299) {
                    val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault(raw)
                    throw Exception("שגיאה $code: $msg")
                }
                val content = JSONObject(raw).getJSONArray("content")
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") == "text") sb.append(block.getString("text"))
                }
                sb.toString().trim()
            }
            runOnUiThread {
                button.isEnabled = true
                button.text = oldText
                result.onSuccess {
                    out.text = it
                    if (out === summaryView) RecState.summary = it else RecState.translation = it
                }
                result.onFailure { toast(it.message ?: "שגיאה לא ידועה") }
            }
        }.start()
    }
}
