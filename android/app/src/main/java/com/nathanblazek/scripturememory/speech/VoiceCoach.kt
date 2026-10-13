package com.nathanblazek.scripturememory.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nathanblazek.scripturememory.data.AppDataHolder
import com.nathanblazek.scripturememory.model.Passage
import com.nathanblazek.scripturememory.practice.Coach
import com.nathanblazek.scripturememory.practice.CoachIO
import com.nathanblazek.scripturememory.practice.HeardWord
import com.nathanblazek.scripturememory.practice.SpeechEngine
import com.nathanblazek.scripturememory.practice.SpeechEvents
import com.nathanblazek.scripturememory.practice.VoiceCommands

/**
 * Runs a [Coach] over a collection with text-to-speech and the speech recognizer. The microphone is
 * off while the coach talks, so it never hears itself. Main thread only.
 */
class VoiceCoach(
    private val context: Context,
    private val collectionId: String,
    passages: List<Passage>,
    /** Where the audio comes from, e.g. the car's microphone; null for the phone's. */
    private val audioSource: () -> AudioSource? = { null },
) : CoachIO {
    private val holder = AppDataHolder.get(context)
    private val voice = Voice(context)
    private var engine: SpeechEngine? = null
    private val settings = CoachSettings(context)
    private val handler = Handler(Looper.getMainLooper())
    private val silence = Runnable { coach.onSilence() }

    val coach = Coach(passages, holder.data.voiceProfile, this)

    fun start() {
        voice.takeFocus()
        coach.start()
    }

    fun shutdown() {
        handler.removeCallbacks(silence)
        coach.shutdown()
        voice.shutdown()
    }

    override fun say(text: String, then: () -> Unit) = voice.speak(text, then)

    override fun stopSaying() = voice.stopSpeaking()

    override val continuousListening: Boolean
        get() = settings.offline && OfflineModel.state(context) == OfflineModel.State.Ready

    override fun listen(words: List<String>, position: Int) {
        stopListening()
        if (words.isEmpty()) return
        val events = object : SpeechEvents {
            override fun onHeard(candidates: List<List<HeardWord>>, final: Boolean) {
                waitForSilence()
                coach.onHeard(candidates, final)
            }
            override fun onAudioLevel(level: Int) {
                if (level >= SPEAKING_LEVEL) waitForSilence() // talking, even if no words have come through yet
            }
            override fun onFailed(message: String) {
                engine = null
                coach.onFailed(message)
            }
        }
        waitForSilence()
        try {
            engine = (
                if (continuousListening) OfflineSpeechEngine(context, words, events, audioSource(), VoiceCommands.phrases)
                else AndroidSpeechEngine(context, words, position, events, audioSource(), VoiceCommands.phrases)
            ).also { it.start() }
        } catch (e: Exception) {
            engine = null
            coach.onFailed(e.message ?: "Speech recognition isn't available.")
        }
    }

    override fun setPosition(position: Int) {
        engine?.setExpectedPosition(position)
    }

    /** (Re)starts the wait for the reciter going quiet. */
    private fun waitForSilence() {
        handler.removeCallbacks(silence)
        val seconds = settings.promptAfterSeconds
        if (seconds > 0) handler.postDelayed(silence, seconds * 1000L)
    }

    override fun stopListening() {
        handler.removeCallbacks(silence)
        val e = engine ?: return
        engine = null
        e.shutdown()
    }

    override fun mastered(passage: Passage) {
        runCatching { holder.setMastered(collectionId, passage.id) }
    }

    override fun profileChanged() {
        runCatching { holder.save() }
    }

    override fun blip() {
        if (settings.blips) voice.softBlip(settings.soundVolume)
    }

    override fun chime() {
        if (settings.chimes) voice.softChime(settings.soundVolume)
    }

    /** Plays the blip and the chime at [volume], to try a setting. */
    fun sample(volume: Int) = voice.sampleSounds(volume)

    override fun log(line: String) = ListeningLog.append(context, line)

    override fun practiced(passage: Passage) {
        runCatching { holder.recordPractice(collectionId, passage.id) }
    }

    private companion object {
        /** Audio level (0..100) that means the reciter is talking. */
        const val SPEAKING_LEVEL = 45
    }
}
