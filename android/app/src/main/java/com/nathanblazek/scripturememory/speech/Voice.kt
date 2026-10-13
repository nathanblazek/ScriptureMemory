package com.nathanblazek.scripturememory.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Reads text aloud and plays the wrong-word blip. Callbacks arrive on the main thread. */
class Voice(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var initialized = false
    private var ready = false
    private var pending: (() -> Unit)? = null
    private val callbacks = mutableMapOf<String, () -> Unit>()
    private var nextId = 0
    private var tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    private var softTone: ToneGenerator? = null
    private var softVolume = -1
    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status -> handler.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        initialized = true
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.US
            tts.setAudioAttributes(ATTRIBUTES)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finished(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    handler.post { callbacks.remove(utteranceId) }
                }
            })
        }
        pending?.invoke()
        pending = null
    }

    /**
     * Holds audio focus while practicing. Music that is playing is paused and picks up again
     * afterwards. Music that is already paused is told it lost focus for good, so neither the
     * recognizer's own focus requests nor the end of the session can start it playing again.
     */
    fun takeFocus() {
        if (focus != null) return
        val gain = if (audio.isMusicActive) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN
        val request = AudioFocusRequest.Builder(gain).setAudioAttributes(ATTRIBUTES).build()
        val granted = runCatching { audio.requestAudioFocus(request) }.getOrNull() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focus = request
    }

    fun releaseFocus() {
        val request = focus ?: return
        focus = null
        runCatching { audio.abandonAudioFocusRequest(request) }
    }

    /** Speaks [text], then calls [onDone] (right away if speech isn't available). */
    fun speak(text: String, onDone: () -> Unit) {
        when {
            !initialized -> pending = { speak(text, onDone) }
            !ready -> onDone()
            else -> {
                // Long passages go out in sentence-sized pieces; the engine caps each utterance.
                val pieces = split(text, TextToSpeech.getMaxSpeechInputLength() - 100)
                pieces.forEachIndexed { i, piece ->
                    val id = "u${nextId++}"
                    if (i == pieces.lastIndex) callbacks[id] = onDone
                    tts.speak(piece, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
                }
            }
        }
    }

    fun stopSpeaking() {
        callbacks.clear()
        pending = null
        if (ready) tts.stop()
    }

    fun blip() {
        tone?.startTone(ToneGenerator.TONE_PROP_NACK, 250)
    }

    /** A quiet, low blip that doesn't drown out the reciter: a word was missed. [volume] is 0..100. */
    fun softBlip(volume: Int) {
        softTone(volume)?.startTone(ToneGenerator.TONE_PROP_NACK, 120)
    }

    /** A quiet, high chime: a verse was right. [volume] is 0..100. */
    fun softChime(volume: Int) {
        softTone(volume)?.startTone(ToneGenerator.TONE_PROP_ACK, 100)
    }

    /** Plays the blip and then the chime at [volume], to try a setting. */
    fun sampleSounds(volume: Int) {
        softBlip(volume)
        handler.postDelayed({ softChime(volume) }, 400)
    }

    private fun softTone(volume: Int): ToneGenerator? {
        if (volume <= 0) return null
        if (volume != softVolume) {
            softTone?.release()
            softTone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, volume) }.getOrNull()
            softVolume = volume
        }
        return softTone
    }

    fun shutdown() {
        stopSpeaking()
        releaseFocus()
        tts.shutdown()
        tone?.release()
        tone = null
        softTone?.release()
        softTone = null
    }

    private fun finished(id: String?) {
        handler.post { callbacks.remove(id)?.invoke() }
    }

    private fun split(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.;:!?])\\s+"))) {
            if (current.isNotEmpty() && current.length + sentence.length + 1 > max) {
                pieces += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) pieces += current.toString()
        // A single sentence longer than the limit is cut wherever it falls.
        return pieces.flatMap { it.chunked(max) }
    }

    private companion object {
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
