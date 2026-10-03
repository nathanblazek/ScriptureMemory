package com.nathanblazek.scripturememory.car

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.nathanblazek.scripturememory.data.AppDataHolder
import com.nathanblazek.scripturememory.model.Passage
import com.nathanblazek.scripturememory.practice.PracticeController
import com.nathanblazek.scripturememory.practice.PracticeMode
import com.nathanblazek.scripturememory.practice.SpeechEvents
import com.nathanblazek.scripturememory.speech.AndroidSpeechEngine
import com.nathanblazek.scripturememory.speech.AudioSource

/**
 * Hands-free practice, one verse at a time: the verse is read aloud, then the driver recites it
 * back. A skipped or wrong word plays a blip and shows that word large. The screen otherwise shows
 * only which verse they're on.
 */
class DrivePracticeScreen(
    carContext: CarContext,
    private val collectionId: String,
    private val passage: Passage,
) : Screen(carContext) {
    private enum class Phase { Reading, Reciting, Done, Error }

    private val holder = AppDataHolder.get(carContext)
    private val verses = Verses.split(passage)
    private val voice = CarVoice(carContext)
    private val handler = Handler(Looper.getMainLooper())

    private var index = 0
    private var phase = Phase.Reading
    private var missedWord: String? = null
    private var error = ""
    private var controller: PracticeController? = null
    private var totalMissed = 0

    private val clearMissed = Runnable {
        missedWord = null
        invalidate()
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                voice.takeFocus()
                if (phase == Phase.Reading || phase == Phase.Reciting) startVerse()
            }

            override fun onStop(owner: LifecycleOwner) {
                stopVerse()
                voice.releaseFocus()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                stopVerse()
                voice.shutdown()
            }
        })
    }

    // ---------- Flow ----------

    private fun startVerse() {
        stopVerse()
        if (verses.isEmpty()) {
            showError("This passage has no text.")
            return
        }
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMic()
            return
        }
        phase = Phase.Reading
        invalidate()
        voice.speak(verses[index].spoken) { if (phase == Phase.Reading) startReciting() }
    }

    private fun startReciting() {
        val verse = verses[index]
        val c = PracticeController(verse.text, holder.data.voiceProfile, strictMisses = true) { words, start, _, events ->
            AndroidSpeechEngine(carContext, words, start, Failures(events), carMicOrNull())
        }
        c.onWordMissed = { word -> handler.post { onMissed(word) } }
        c.onPracticeCompleted = { handler.post { onVerseDone() } }
        controller = c
        c.setMode(PracticeMode.FirstLetter)
        c.startSpeech()
        if (!c.state.listening) {
            showError(c.state.status)
            return
        }
        phase = Phase.Reciting
        invalidate()
    }

    private fun stopVerse() {
        handler.removeCallbacksAndMessages(null)
        voice.stopSpeaking()
        controller?.shutdown()
        controller = null
        missedWord = null
    }

    private fun onMissed(word: String) {
        if (phase != Phase.Reciting) return
        totalMissed++
        voice.blip()
        missedWord = word
        invalidate()
        handler.removeCallbacks(clearMissed)
        handler.postDelayed(clearMissed, MISSED_WORD_MS)
    }

    private fun onVerseDone() {
        if (phase != Phase.Reciting) return
        controller?.shutdown()
        controller = null
        index++
        if (index < verses.size) {
            // Leave a moment so a word missed at the very end can still be seen.
            handler.postDelayed({ startVerse() }, if (missedWord != null) MISSED_WORD_MS else 600L)
        } else {
            finishPassage()
        }
    }

    private fun finishPassage() {
        phase = Phase.Done
        missedWord = null
        runCatching { holder.recordPractice(collectionId, passage.id) }
        invalidate()
        voice.speak(
            if (totalMissed == 0) "Passage complete. No missed words." else "Passage complete. $totalMissed missed word${if (totalMissed == 1) "" else "s"}."
        ) {}
    }

    private fun restart() {
        index = 0
        totalMissed = 0
        startVerse()
    }

    private fun showError(message: String) {
        stopVerse()
        phase = Phase.Error
        error = message
        invalidate()
    }

    private fun requestMic() {
        showError("Allow microphone access on your phone to recite.")
        carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
            if (Manifest.permission.RECORD_AUDIO in granted) startVerse()
        }
    }

    /** The car's microphone where Android Auto and the recognizer support it; otherwise the phone's default input. */
    private fun carMicOrNull(): AudioSource? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_5) return null
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching { CarMicSource(carContext) }.getOrNull()
    }

    /** Surfaces recognizer failures on the car screen. */
    private inner class Failures(private val events: SpeechEvents) : SpeechEvents by events {
        override fun onFailed(message: String) {
            events.onFailed(message)
            handler.post { showError(message) }
        }
    }

    // ---------- Template ----------

    override fun onGetTemplate(): Template {
        val label = if (verses.isEmpty()) passage.reference else verses[index.coerceAtMost(verses.size - 1)].label
        val missed = missedWord

        val (title, message) = when {
            phase == Phase.Error -> "Can't practice right now" to error
            phase == Phase.Done -> "Passage complete" to passage.reference
            missed != null -> "Missed word · $label" to missed
            phase == Phase.Reading -> "Listen" to label
            else -> "Your turn" to label
        }

        val builder = MessageTemplate.Builder(message)
            .setTitle(title)
            .setHeaderAction(Action.BACK)

        when (phase) {
            Phase.Done -> builder.addAction(action("Again") { restart() })
            Phase.Error -> builder.addAction(action("Try again") { startVerse() })
            else -> {
                builder.addAction(action("Repeat") { startVerse() })
                builder.addAction(action("Next verse") {
                    stopVerse()
                    if (index + 1 < verses.size) {
                        index++
                        startVerse()
                    } else {
                        finishPassage()
                    }
                })
            }
        }
        return builder.build()
    }

    private fun action(title: String, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).setOnClickListener(onClick).build()

    private companion object {
        const val MISSED_WORD_MS = 3000L
    }
}
