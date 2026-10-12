package com.nathanblazek.scripturememory.speech

import android.content.Context
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

    val coach = Coach(passages, holder.data.voiceProfile, this)

    fun start() {
        voice.takeFocus()
        coach.start()
    }

    fun shutdown() {
        coach.shutdown()
        voice.shutdown()
    }

    override fun say(text: String, then: () -> Unit) = voice.speak(text, then)

    override fun stopSaying() = voice.stopSpeaking()

    override fun listen(words: List<String>, position: Int) {
        stopListening()
        if (words.isEmpty()) return
        val events = object : SpeechEvents {
            override fun onHeard(candidates: List<List<HeardWord>>, final: Boolean) = coach.onHeard(candidates, final)
            override fun onAudioLevel(level: Int) {}
            override fun onFailed(message: String) {
                engine = null
                coach.onFailed(message)
            }
        }
        try {
            engine = AndroidSpeechEngine(context, words, position, events, audioSource(), VoiceCommands.phrases).also { it.start() }
        } catch (e: Exception) {
            engine = null
            coach.onFailed(e.message ?: "Speech recognition isn't available.")
        }
    }

    override fun setPosition(position: Int) {
        engine?.setExpectedPosition(position)
    }

    override fun stopListening() {
        val e = engine ?: return
        engine = null
        e.shutdown()
    }

    override fun mastered(passage: Passage) {
        runCatching { holder.setMastered(collectionId, passage.id) }
    }

    override fun practiced(passage: Passage) {
        runCatching { holder.recordPractice(collectionId, passage.id) }
    }
}
