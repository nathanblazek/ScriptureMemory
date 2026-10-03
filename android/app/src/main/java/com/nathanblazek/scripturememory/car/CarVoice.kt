package com.nathanblazek.scripturememory.car

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

/** Reads verses aloud and plays the wrong-word blip. Callbacks arrive on the main thread. */
class CarVoice(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(ATTRIBUTES)
        .build()
    private var initialized = false
    private var ready = false
    private var pending: (() -> Unit)? = null
    private val callbacks = mutableMapOf<String, () -> Unit>()
    private var nextId = 0
    private var tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
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

    /** Pauses other audio (music, podcasts) while practicing. */
    fun takeFocus() {
        runCatching { audio.requestAudioFocus(focus) }
    }

    fun releaseFocus() {
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }

    /** Speaks [text], then calls [onDone] (right away if speech isn't available). */
    fun speak(text: String, onDone: () -> Unit) {
        when {
            !initialized -> pending = { speak(text, onDone) }
            !ready -> onDone()
            else -> {
                val id = "u${nextId++}"
                callbacks[id] = onDone
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
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

    fun shutdown() {
        stopSpeaking()
        releaseFocus()
        tts.shutdown()
        tone?.release()
        tone = null
    }

    private fun finished(id: String?) {
        handler.post { callbacks.remove(id)?.invoke() }
    }

    private companion object {
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
