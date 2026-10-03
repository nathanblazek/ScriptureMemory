package com.nathanblazek.scripturememory.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.nathanblazek.scripturememory.practice.Aligner
import com.nathanblazek.scripturememory.practice.HeardWord
import com.nathanblazek.scripturememory.practice.SpeechEngine
import com.nathanblazek.scripturememory.practice.SpeechEvents
import com.nathanblazek.scripturememory.practice.Tokenizer

/**
 * Continuous recitation on top of Android's [SpeechRecognizer], which hears one utterance at a time:
 * each time an utterance ends, listening starts again. Partial results reveal words while the user is
 * still speaking. On Android 13+ the recognizer is biased toward the passage's upcoming words.
 *
 * Must be created and used on the main thread; events arrive on the main thread.
 */
class AndroidSpeechEngine(
    context: Context,
    private val passageWords: List<String>,
    start: Int,
    private val events: SpeechEvents,
) : SpeechEngine {
    private val recognizer: SpeechRecognizer
    private val handler = Handler(Looper.getMainLooper())
    private var expectedPosition = start
    private var running = false
    private var consecutiveErrors = 0
    private var heardPartial = false

    init {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw IllegalStateException(
                "Speech recognition isn't available on this device. Install or enable a speech service such as Speech Recognition & Synthesis from Google."
            )
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(Listener())
    }

    override fun start() {
        if (running) return
        running = true
        listen()
    }

    override fun setExpectedPosition(position: Int) {
        expectedPosition = position // used for biasing from the next utterance on
    }

    override fun shutdown() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer.cancel() }
        runCatching { recognizer.destroy() }
    }

    private fun listen() {
        if (!running) return
        heardPartial = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, biasingStrings())
            }
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            fail("Couldn't start the speech recognizer. (${e.message})")
        }
    }

    /** The upcoming stretch of the passage as one phrase, plus its distinct words. */
    private fun biasingStrings(): ArrayList<String> {
        val from = (expectedPosition - LOOK_BACK).coerceIn(0, passageWords.size)
        val to = (expectedPosition + AHEAD).coerceAtMost(passageWords.size)
        val upcoming = passageWords.subList(from, to)
        val result = ArrayList<String>()
        if (upcoming.isNotEmpty()) result += upcoming.joinToString(" ")
        upcoming.filterNot { Aligner.isFunctionWord(Tokenizer.key(it)) }.distinct().forEach { result += it }
        return result
    }

    private fun restart(delayMs: Long) {
        if (!running) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ listen() }, delayMs)
    }

    private fun fail(message: String) {
        if (!running) return
        shutdown()
        events.onFailed(message)
    }

    private fun words(text: String, confidence: Float): List<HeardWord> =
        text.split(Regex("""\s+""")).filter { it.isNotBlank() }.map { HeardWord(it, confidence) }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (!running) return
            // Recognizers report roughly -2..10 dB.
            events.onAudioLevel((((rmsdB + 2f) / 12f) * 100f).toInt().coerceIn(0, 100))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!running) return
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val candidates = texts.map { words(it, PARTIAL_CONFIDENCE) }.filter { it.isNotEmpty() }
            if (candidates.isEmpty()) return
            heardPartial = true
            events.onHeard(candidates, final = false)
        }

        override fun onResults(results: Bundle?) {
            if (!running) return
            consecutiveErrors = 0
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val candidates = texts.mapIndexed { i, text ->
                // Not every recognizer reports confidence; when it's missing, trust the top guess more.
                val score = scores?.getOrNull(i)?.takeIf { it > 0f } ?: if (i == 0) 0.8f else 0.5f
                words(text, score)
            }.filter { it.isNotEmpty() }
            events.onHeard(candidates, final = true)
            restart(0)
        }

        override fun onError(error: Int) {
            if (!running) return
            // Close the utterance so the next one is aligned from the reciter's current position.
            if (heardPartial) events.onHeard(emptyList(), final = true)
            if (!running) return

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(0)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    fail("Microphone permission is needed to recite out loud. Allow it in the app's settings.")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    runCatching { recognizer.cancel() }
                    restart(300)
                }
                else -> {
                    if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        fail(
                            when (error) {
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                    "Speech recognition needs an internet connection on this device (or an offline English speech pack)."
                                else -> "Speech recognition stopped working (error $error). Try again in a moment."
                            }
                        )
                    } else {
                        restart(500)
                    }
                }
            }
        }
    }

    private companion object {
        const val LOOK_BACK = 3
        const val AHEAD = 30
        const val PARTIAL_CONFIDENCE = 0.6f
        const val MAX_CONSECUTIVE_ERRORS = 5
    }
}
