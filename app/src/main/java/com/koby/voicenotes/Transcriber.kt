package com.koby.voicenotes

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * תמלול קובץ שמע דרך OpenAI (Whisper / gpt-4o-transcribe). לא קשור ל-Claude.
 *
 * ה-API מוגבל ל-25MB לקובץ ולא מכיר קובצי AAC גולמיים (ADTS), אז:
 *  1. הקובץ נחתך לקטעים של 20 דקות ונארז מחדש כ-m4a (בלי קידוד מחדש, אז זה מהיר ובלי אובדן איכות).
 *  2. כל קטע נשלח בנפרד, והתמלולים מחוברים.
 * אם החיתוך נכשל (למשל קובץ mp3 שנבחר ידנית), ושהקובץ קטן מ-24MB, הוא נשלח כמו שהוא.
 */
object Transcriber {

    private const val SEGMENT_US = 20L * 60 * 1_000_000
    private const val MAX_DIRECT_BYTES = 24L * 1024 * 1024

    /**
     * baseUrl: כתובת בסיס של שירות תואם-OpenAI, למשל https://api.groq.com/openai/v1 (חינמי עם מגבלות)
     * או https://api.openai.com/v1 (בתשלום).
     */
    fun run(
        ctx: Context,
        uri: Uri,
        langCode: String,
        baseUrl: String,
        key: String,
        model: String,
        onProgress: (String) -> Unit
    ): String {
        val endpoint = baseUrl.trim().trimEnd('/') + "/audio/transcriptions"
        onProgress("מכין את הקובץ…")
        val segments: List<File>? = try { split(ctx, uri) } catch (e: Exception) { null }
        val parts = ArrayList<String>()
        try {
            if (segments != null) {
                segments.forEachIndexed { i, f ->
                    onProgress("מתמלל קטע ${i + 1} מתוך ${segments.size}…")
                    parts.add(upload(endpoint, key, model, langCode, "audio.m4a", "audio/mp4", f.inputStream()))
                }
            } else {
                val (name, size) = nameAndSize(ctx, uri)
                if (size > MAX_DIRECT_BYTES) {
                    throw Exception("הקובץ גדול מ-24MB ואי אפשר לפצל אותו. נסה קובץ AAC/M4A.")
                }
                onProgress("מתמלל…")
                val input = ctx.contentResolver.openInputStream(uri) ?: throw Exception("לא ניתן לפתוח את הקובץ")
                parts.add(upload(endpoint, key, model, langCode, name, mimeFor(name), input))
            }
        } finally {
            segments?.forEach { it.delete() }
        }
        return parts.joinToString(" ").trim()
    }

    // ------------------------------------------------------------ split ADTS/AAC into m4a segments

    private fun split(ctx: Context, uri: Uri): List<File> {
        val ex = MediaExtractor()
        val out = ArrayList<File>()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) { track = i; break }
            }
            if (track < 0) throw Exception("אין שמע בקובץ")
            val format = ex.getTrackFormat(track)
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) {
                throw Exception("not aac")
            }
            ex.selectTrack(track)

            val buf = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            var segStart = 0L
            var trackIdx = -1
            var n = 0
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (muxer == null || t - segStart >= SEGMENT_US) {
                    muxer?.stop()
                    muxer?.release()
                    val f = File(ctx.cacheDir, "seg_${n++}.m4a")
                    out.add(f)
                    val m = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    trackIdx = m.addTrack(format)
                    m.start()
                    muxer = m
                    segStart = t
                }
                info.set(0, size, t - segStart, ex.sampleFlags)
                muxer!!.writeSampleData(trackIdx, buf, info)
                ex.advance()
            }
            muxer?.stop()
            muxer?.release()
            muxer = null
            if (out.isEmpty()) throw Exception("הקובץ ריק")
            return out
        } catch (e: Exception) {
            try { muxer?.release() } catch (_: Exception) {}
            out.forEach { it.delete() }
            throw e
        } finally {
            ex.release()
        }
    }

    // ------------------------------------------------------------ upload

    private fun upload(
        endpoint: String, key: String, model: String, lang: String,
        fileName: String, mime: String, input: InputStream
    ): String {
        val boundary = "----voicenotes" + System.currentTimeMillis()
        val c = URL(endpoint).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30000
        c.readTimeout = 600000
        c.setChunkedStreamingMode(0)
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        DataOutputStream(c.outputStream).use { o ->
            fun field(name: String, value: String) {
                o.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            field("model", model)
            field("language", lang)
            field("response_format", "json")
            o.writeBytes(
                "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
                    "Content-Type: $mime\r\n\r\n"
            )
            input.use { it.copyTo(o) }
            o.writeBytes("\r\n--$boundary--\r\n")
            o.flush()
        }

        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault(raw)
            throw Exception("שגיאה $code משירות התמלול: $msg")
        }
        return JSONObject(raw).getString("text").trim()
    }

    // ------------------------------------------------------------ helpers

    private fun nameAndSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = "audio.m4a"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        return Pair(name, size)
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3", "mpeg", "mpga" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }
}
