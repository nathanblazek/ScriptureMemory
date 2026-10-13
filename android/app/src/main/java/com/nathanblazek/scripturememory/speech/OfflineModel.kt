package com.nathanblazek.scripturememory.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.vosk.Model
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The offline speech model (Vosk, small US English), downloaded once on request and kept in the
 * app's files. Main thread only; downloading and loading happen in the background.
 */
object OfflineModel {
    sealed interface State {
        data object Missing : State
        data class Downloading(val percent: Int) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private const val NAME = "vosk-model-small-en-us-0.15"
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/$NAME.zip"
    const val SIZE_MB = 40

    private val main = Handler(Looper.getMainLooper())
    private var state: State? = null
    private val watchers = mutableListOf<(State) -> Unit>()
    private var model: Model? = null
    private var loading: MutableList<(Model?) -> Unit>? = null

    private fun dir(context: Context) = File(context.filesDir, NAME)
    private fun doneMarker(context: Context) = File(dir(context), ".complete")

    fun state(context: Context): State =
        state ?: (if (doneMarker(context).exists()) State.Ready else State.Missing).also { state = it }

    fun watch(watcher: (State) -> Unit): () -> Unit {
        watchers += watcher
        return { watchers -= watcher }
    }

    private fun set(s: State) {
        state = s
        watchers.toList().forEach { it(s) }
    }

    fun download(context: Context) {
        val app = context.applicationContext
        if (state(app) is State.Downloading || state(app) == State.Ready) return
        set(State.Downloading(0))
        Thread {
            val result = runCatching { fetch(app) }
            main.post {
                set(result.fold({ State.Ready }, { State.Failed(it.message ?: "The download didn't finish.") }))
            }
        }.start()
    }

    private fun fetch(context: Context) {
        val target = dir(context)
        target.deleteRecursively()
        val connection = URL(URL_ZIP).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) error("The download failed (HTTP ${connection.responseCode}).")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: (SIZE_MB * 1_000_000L)
            var read = 0L
            var lastPercent = -1
            val counted = object : java.io.FilterInputStream(connection.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        read += n
                        val percent = (read * 100 / total).toInt().coerceAtMost(99)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            main.post { set(State.Downloading(percent)) }
                        }
                    }
                    return n
                }
            }
            ZipInputStream(counted.buffered()).use { zip ->
                val root = target.canonicalPath
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // Entries are "<NAME>/...": unpack them under the target folder.
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty()) continue
                    val out = File(target, relative)
                    if (!out.canonicalPath.startsWith(root)) error("The download was damaged.")
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            doneMarker(context).writeText("ok")
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    fun delete(context: Context) {
        // Not closed: a session may still be listening with it. It goes when the app does.
        model = null
        dir(context).deleteRecursively()
        set(State.Missing)
    }

    /** Loads the model (once) and hands it to [then] on the main thread; null if it isn't there or won't load. */
    fun load(context: Context, then: (Model?) -> Unit) {
        model?.let { return then(it) }
        if (state(context) != State.Ready) return then(null)
        loading?.let {
            it += then
            return
        }
        val waiting = mutableListOf(then).also { loading = it }
        val path = dir(context).path
        Thread {
            val m = runCatching { Model(path) }.getOrNull()
            main.post {
                model = m
                loading = null
                waiting.forEach { it(m) }
            }
        }.start()
    }
}
