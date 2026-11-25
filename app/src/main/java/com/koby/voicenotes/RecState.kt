package com.koby.voicenotes

import java.util.Locale

/**
 * מצב משותף בין המסך לשירות ההקלטה (שניהם רצים באותו תהליך ובאותו thread ראשי).
 * כך המסך יכול להיסגר ולהיפתח מחדש בלי לאבד את התמלול או את מצב ההקלטה.
 */
object RecState {
    @Volatile var recording = false
    @Volatile var startedAt = 0L
    @Volatile var deadline = 0L

    var transcript = ""
    var live = ""
    var summary = ""
    var translation = ""

    /** קובץ השמע האחרון שנשמר (Uri של MediaStore) ושמו */
    var audioUri: String? = null
    var audioName: String? = null

    /** הודעה חד-פעמית להצגה למשתמש (נמחקת אחרי שהמסך הציג אותה) */
    var message: String? = null

    /** המסך רושם כאן פונקציה שנקראת בכל שינוי. null כשהמסך לא גלוי. */
    var listener: (() -> Unit)? = null

    fun changed() { listener?.invoke() }

    fun append(t: String) {
        val clean = t.trim()
        if (clean.isEmpty()) return
        val cur = transcript.trimEnd()
        transcript = if (cur.isEmpty()) clean else "$cur $clean"
        changed()
    }

    fun mmss(ms: Long): String {
        val s = (if (ms < 0) 0 else ms) / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }
}
