package com.nathanblazek.scripturememory.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.nathanblazek.scripturememory.data.AppDataHolder
import com.nathanblazek.scripturememory.practice.CoachPhase
import com.nathanblazek.scripturememory.practice.CoachState
import com.nathanblazek.scripturememory.practice.VoiceCommand
import com.nathanblazek.scripturememory.speech.VoiceCoach

/**
 * The voice coach in the car: works through a collection by voice ("verse yes", "verse speak", ...).
 * The screen shows only which passage it's on.
 */
class CoachCarScreen(carContext: CarContext, private val collectionId: String) : Screen(carContext) {
    private var session: VoiceCoach? = null
    private var shown: Pair<CoachPhase, String>? = null
    private var state: CoachState? = null
    private var error = ""

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = begin()
            override fun onStop(owner: LifecycleOwner) = end()
        })
    }

    private fun begin() {
        if (session != null) return
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "Allow microphone access on your phone to practice by voice."
            invalidate()
            carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                if (Manifest.permission.RECORD_AUDIO in granted) {
                    error = ""
                    begin()
                }
            }
            return
        }
        val passages = AppDataHolder.get(carContext).data.collections.firstOrNull { it.id == collectionId }?.passages.orEmpty()
        val s = VoiceCoach(carContext, collectionId, passages) { carMicOrNull(carContext) }
        s.coach.onChanged = { st ->
            state = st
            // Only redraw when something on screen changes; the car limits template updates.
            val now = st.phase to st.reference
            if (now != shown) {
                shown = now
                invalidate()
            }
        }
        s.coach.onStopped = { screenManager.pop() }
        session = s
        s.start()
    }

    private fun end() {
        session?.shutdown()
        session = null
    }

    override fun onGetTemplate(): Template {
        val st = state
        val (title, message) = when {
            error.isNotEmpty() -> "Voice practice" to error
            st == null -> "Voice practice" to "Starting…"
            st.phase == CoachPhase.Failed -> "Can't listen right now" to st.error
            else -> when (st.phase) {
                CoachPhase.Speaking -> "Listen"
                CoachPhase.Reciting -> "Your turn"
                CoachPhase.Waiting -> "Say “verse yes” to try again"
                CoachPhase.Finished -> "Collection done"
                else -> "Voice practice"
            } to st.reference.ifEmpty { "No passages" }
        }
        return MessageTemplate.Builder(message)
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .addAction(action("Try again") { session?.coach?.command(VoiceCommand.Yes) })
            .addAction(action("I said it right") { session?.coach?.command(VoiceCommand.Correct) })
            .build()
    }

    private fun action(title: String, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).setOnClickListener(onClick).build()
}
