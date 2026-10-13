package com.nathanblazek.scripturememory.speech

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the voice coach heard and decided, kept on the phone so a session that went wrong can be
 * shared and turned into a test. Only the most recent part is kept.
 */
object ListeningLog {
    private const val MAX_BYTES = 200_000L
    private const val KEEP_BYTES = 100_000
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun file(context: Context) = File(context.filesDir, "listening-log.txt")

    fun append(context: Context, line: String) {
        runCatching {
            val f = file(context)
            f.appendText("${time.format(Date())} $line\n")
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.indexOf('\n', text.length - KEEP_BYTES) + 1))
            }
        }
    }

    fun read(context: Context): String = runCatching { file(context).readText() }.getOrDefault("")
}
