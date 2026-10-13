package com.nathanblazek.scripturememory.speech

import android.content.Context
import android.os.ParcelFileDescriptor
import com.nathanblazek.scripturememory.practice.HeardWord
import com.nathanblazek.scripturememory.practice.SpeechEngine
import com.nathanblazek.scripturememory.practice.SpeechEvents
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.SpeechStreamService

/**
 * Recitation heard by the offline model, which only knows the passage's words (and the coach's
 * commands): anything else comes out as "[unk]". Unlike Android's recognizer it listens
 * continuously, so no words are lost between utterances. Main thread only.
 */
class OfflineSpeechEngine(
    private val context: Context,
    private val passageWords: List<String>,
    private val events: SpeechEvents,
    /** Where the audio comes from instead of the phone's microphone, e.g. the car's microphone. */
    private val audioSource: AudioSource? = null,
    /** More phrases the model should know, such as spoken commands. */
    private val extraPhrases: List<String> = emptyList(),
) : SpeechEngine {
    private var running = false
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var stream: SpeechStreamService? = null
    private var lastPartial = ""

    override fun start() {
        if (running) return
        running = true
        OfflineModel.load(context) { model ->
            if (!running) return@load
            if (model == null) {
                fail("The offline speech model couldn't be loaded. Download it again in the coach settings, or switch back to Google.")
                return@load
            }
            try {
                val rate = (audioSource?.sampleRate ?: SAMPLE_RATE).toFloat()
                val r = Recognizer(model, rate, grammar()).also { recognizer = it }
                r.setMaxAlternatives(MAX_ALTERNATIVES)
                if (audioSource != null) {
                    val input = ParcelFileDescriptor.AutoCloseInputStream(audioSource.open())
                    stream = SpeechStreamService(r, input, rate).also { it.start(Listener()) }
                } else {
                    service = SpeechService(r, rate).also { it.startListening(Listener()) }
                }
            } catch (e: Exception) {
                fail("The offline speech recognizer couldn't start. (${e.message})")
            }
        }
    }

    /** Every word the reciter might say, one per entry, so they can come in any order or be left out. */
    private fun grammar(): String {
        val words = (passageWords + extraPhrases.flatMap { it.split(' ') })
            .map { w -> w.lowercase().filter { it.isLetter() || it == '\'' }.trim('\'') }
            .filter { it.isNotEmpty() }
            .distinct()
        return JSONArray(words + "[unk]").toString()
    }

    override fun setExpectedPosition(position: Int) {} // it knows only the passage's words anyway

    override fun shutdown() {
        running = false
        runCatching { service?.stop() }
        runCatching { service?.shutdown() }
        runCatching { stream?.stop() }
        runCatching { recognizer?.close() }
        audioSource?.close()
        service = null
        stream = null
        recognizer = null
    }

    private fun fail(message: String) {
        if (!running) return
        shutdown()
        events.onFailed(message)
    }

    private fun words(text: String, confidence: Float): List<HeardWord> =
        text.split(Regex("""\s+""")).filter { it.isNotBlank() }.map { HeardWord(it, confidence) }

    private inner class Listener : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (!running) return
            val text = runCatching { JSONObject(hypothesis.orEmpty()).optString("partial") }.getOrDefault("")
            // Reported over and over while listening; only a change is news.
            if (text.isBlank() || text == lastPartial) return
            lastPartial = text
            events.onHeard(listOf(words(text, PARTIAL_CONFIDENCE)), final = false)
        }

        override fun onResult(hypothesis: String?) = result(hypothesis)

        override fun onFinalResult(hypothesis: String?) = result(hypothesis)

        private fun result(hypothesis: String?) {
            if (!running) return
            lastPartial = ""
            val json = runCatching { JSONObject(hypothesis.orEmpty()) }.getOrNull() ?: return
            val alternatives = json.optJSONArray("alternatives")
            val candidates = if (alternatives != null) {
                (0 until alternatives.length()).map { i ->
                    val a = alternatives.getJSONObject(i)
                    words(a.optString("text"), if (i == 0) TOP_CONFIDENCE else OTHER_CONFIDENCE)
                }
            } else {
                listOf(words(json.optString("text"), TOP_CONFIDENCE))
            }.filter { it.isNotEmpty() }
            if (candidates.isNotEmpty()) events.onHeard(candidates, final = true)
        }

        override fun onError(exception: Exception?) {
            fail("The offline speech recognizer stopped. (${exception?.message})")
        }

        override fun onTimeout() {}
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_ALTERNATIVES = 3
        const val PARTIAL_CONFIDENCE = 0.6f
        const val TOP_CONFIDENCE = 0.8f
        const val OTHER_CONFIDENCE = 0.5f
    }
}
