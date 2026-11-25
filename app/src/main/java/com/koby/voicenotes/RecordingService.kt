package com.koby.voicenotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * שירות רקע (Foreground Service מסוג מיקרופון) שמקליט שמע ומתמלל גם כשעוברים לאפליקציה אחרת או כשהמסך כבוי.
 * - השמע נשמר תמיד לקובץ (Music/VoiceNotes), בלי קשר לתמלול.
 * - התמלול החי (Google) הוא בונוס: אם הוא מתנגש עם הקלטת השמע, הוא נכבה והשמע ממשיך להישמר.
 * - מגבלה: שעה מרגע ההפעלה. 5 דקות לפני הסיום: התראה עם ספירה לאחור וכפתור "הארך ב-30 דקות".
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.koby.voicenotes.START"
        const val ACTION_STOP = "com.koby.voicenotes.STOP"
        const val ACTION_EXTEND = "com.koby.voicenotes.EXTEND"
        const val EXTRA_LANG = "lang"

        const val LIMIT_MS = 60L * 60 * 1000      // שעה
        const val EXTEND_MS = 30L * 60 * 1000     // חצי שעה
        const val WARN_MS = 5L * 60 * 1000        // 5 דקות לפני הסיום
        private const val MAX_EMPTY_CYCLES = 40   // אחרי כמה ניסיונות ריקים רצופים מוותרים על תמלול חי

        private const val CH_REC = "recording"
        private const val CH_WARN = "recording_warning"
        private const val ID_REC = 1
        private const val ID_WARN = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var lang = "he-IL"
    private var wake: PowerManager.WakeLock? = null
    private var warned = false

    private var liveOn = false
    private var emptyCycles = 0

    private var recorder: MediaRecorder? = null
    private var audioPfd: ParcelFileDescriptor? = null
    private var recCallback: AudioManager.AudioRecordingCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent.getStringExtra(EXTRA_LANG) ?: "he-IL")
            ACTION_EXTEND -> extend()
            ACTION_STOP -> finish()
            else -> if (!RecState.recording) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ lifecycle

    private fun begin(l: String) {
        val fresh = !RecState.recording
        if (fresh) {
            lang = l
            val now = System.currentTimeMillis()
            RecState.recording = true
            RecState.startedAt = now
            RecState.deadline = now + LIMIT_MS
            RecState.live = ""
            warned = false
            emptyCycles = 0
        }
        // חייבים לקרוא ל-startForeground מיד אחרי startForegroundService
        createChannels()
        val n = ongoingNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(ID_REC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ID_REC, n)
        }
        if (!fresh) return

        grabWakeLock()

        // 1) קודם כל שמע: זה מה שחייב להישמר
        val audioOk = startAudio()
        if (!audioOk) RecState.message = "לא הצלחתי לשמור קובץ שמע. התמלול החי ימשיך לעבוד."

        // 2) תמלול חי (אופציונלי)
        val prefs = getSharedPreferences("voicenotes", Context.MODE_PRIVATE)
        liveOn = prefs.getBoolean("live", true)
        if (liveOn) listenOnce()

        RecState.changed()
        handler.post(tick)
    }

    private fun extend() {
        if (!RecState.recording) return
        RecState.deadline += EXTEND_MS
        warned = false
        cancelWarning()
        grabWakeLock()
        notifyManager().notify(ID_REC, ongoingNotification())
        RecState.changed()
    }

    private fun finish() {
        if (!RecState.recording) { stopSelf(); return }
        RecState.recording = false
        RecState.live = ""
        handler.removeCallbacks(tick)
        cancelWarning()
        RecState.changed()
        // נותנים לזיהוי הדיבור לסיים את המשפט האחרון, ואז סוגרים גם את קובץ השמע
        try { recognizer?.stopListening() } catch (_: Exception) {}
        handler.postDelayed({ stopAudio(); stopSelf() }, 1500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopAudio()
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        cancelWarning()
        RecState.recording = false
        RecState.live = ""
        RecState.changed()
        super.onDestroy()
    }

    /** בדיקה כל שנייה: הגענו לאזהרה? הגענו לסוף? */
    private val tick = object : Runnable {
        override fun run() {
            if (!RecState.recording) return
            val left = RecState.deadline - System.currentTimeMillis()
            if (left <= 0) {
                RecState.message = "ההקלטה הופסקה: הגעת למגבלת הזמן"
                finish()
                return
            }
            if (left <= WARN_MS && !warned) {
                warned = true
                showWarning()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun grabWakeLock() {
        try {
            if (wake?.isHeld == true) wake?.release()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicenotes:recording")
            val ms = RecState.deadline - System.currentTimeMillis() + 60_000
            wake?.acquire(ms)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ audio file

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    /**
     * שומר שמע בפורמט AAC (ADTS) לתיקייה Music/VoiceNotes.
     * הפורמט הזה נשמע גם אם האפליקציה קורסת באמצע (בניגוד ל-m4a שדורש סגירה תקינה).
     */
    private fun startAudio(): Boolean {
        return try {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val name = "voice_$stamp.aac"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/aac")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VoiceNotes")
            }
            val uri = contentResolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: return false
            val pfd = contentResolver.openFileDescriptor(uri, "w") ?: return false
            audioPfd = pfd

            val r = newRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setAudioChannels(1)
            r.setOutputFile(pfd.fileDescriptor)
            r.prepare()
            r.start()
            recorder = r

            RecState.audioUri = uri.toString()
            RecState.audioName = name
            getSharedPreferences("voicenotes", Context.MODE_PRIVATE).edit()
                .putString("lastAudio", uri.toString())
                .putString("lastAudioName", name)
                .apply()
            watchSilence()
            true
        } catch (e: Exception) {
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            try { audioPfd?.close() } catch (_: Exception) {}
            audioPfd = null
            false
        }
    }

    private fun stopAudio() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                recCallback?.let {
                    (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioRecordingCallback(it)
                }
            }
        } catch (_: Exception) {}
        recCallback = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { audioPfd?.close() } catch (_: Exception) {}
        audioPfd = null
    }

    /**
     * אם המערכת משתיקה את הקלטת השמע שלנו (כי זיהוי הדיבור של Google תפס את המיקרופון),
     * מכבים את התמלול החי כדי שהשמע ימשיך להישמר.
     */
    private fun watchSilence() {
        if (Build.VERSION.SDK_INT < 29) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                var mine: AudioRecordingConfiguration? = null
                try { mine = recorder?.activeRecordingConfiguration } catch (_: Exception) {}
                if (mine == null) return
                if (mine.isClientSilenced && liveOn) {
                    disableLive("התמלול החי הופסק כי הוא הפריע להקלטת השמע. השמע ממשיך להישמר.")
                }
            }
        }
        am.registerAudioRecordingCallback(cb, handler)
        recCallback = cb
    }

    private fun disableLive(msg: String) {
        liveOn = false
        RecState.live = ""
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        RecState.message = msg
        RecState.changed()
    }

    // ------------------------------------------------------------ speech recognition

    private fun listenOnce() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }
        recognizer?.startListening(i)
    }

    private fun restartSoon(delay: Long) {
        handler.postDelayed({ if (RecState.recording && liveOn) listenOnce() }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val l = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!l.isNullOrEmpty()) { emptyCycles = 0; RecState.live = l[0]; RecState.changed() }
        }

        override fun onResults(results: Bundle?) {
            RecState.live = ""
            val l = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!l.isNullOrEmpty()) { emptyCycles = 0; RecState.append(l[0]) } else RecState.changed()
            if (RecState.recording && liveOn) restartSoon(200)
        }

        override fun onError(error: Int) {
            RecState.live = ""
            if (!RecState.recording || !liveOn) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    emptyCycles++
                    if (emptyCycles >= MAX_EMPTY_CYCLES) {
                        disableLive("לא התקבל תמלול זמן רב, אז התמלול החי כובה. השמע ממשיך להישמר.")
                    } else restartSoon(200)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartSoon(800)
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    disableLive("התמלול החי הופסק בגלל בעיית רשת. השמע ממשיך להישמר.")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    disableLive("התמלול החי הופסק: חסרה הרשאה. השמע ממשיך להישמר.")
                else ->
                    disableLive("התמלול החי הופסק (שגיאה $error). השמע ממשיך להישמר.")
            }
        }
    }

    // ------------------------------------------------------------ notifications

    private fun notifyManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        val nm = notifyManager()
        nm.createNotificationChannel(
            NotificationChannel(CH_REC, "הקלטה פעילה", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WARN, "התראת סיום הקלטה", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    @Suppress("DEPRECATION")
    private fun ongoingNotification(): Notification {
        val endAt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(RecState.deadline))
        return Notification.Builder(this, CH_REC)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("מקליט שמע ומתמלל")
            .setContentText("ההקלטה תיפסק אוטומטית ב-$endAt")
            .setOngoing(true)
            .setWhen(RecState.startedAt)
            .setUsesChronometer(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "עצור", serviceIntent(ACTION_STOP, 10))
            .addAction(0, "הארך ב-30 דק׳", serviceIntent(ACTION_EXTEND, 11))
            .build()
    }

    @Suppress("DEPRECATION")
    private fun showWarning() {
        val n = Notification.Builder(this, CH_WARN)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("ההקלטה תיפסק בקרוב")
            .setContentText("הזמן שנותר מוצג בספירה לאחור. אפשר להאריך ב-30 דקות.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(RecState.deadline)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "הארך ב-30 דק׳", serviceIntent(ACTION_EXTEND, 12))
            .addAction(0, "עצור עכשיו", serviceIntent(ACTION_STOP, 13))
            .build()
        notifyManager().notify(ID_WARN, n)
        RecState.changed()
    }

    private fun cancelWarning() {
        try { notifyManager().cancel(ID_WARN) } catch (_: Exception) {}
    }
}
