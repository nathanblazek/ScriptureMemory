package com.nathanblazek.scripturememory.practice

import com.nathanblazek.scripturememory.model.Passage
import com.nathanblazek.scripturememory.model.VoiceProfile

/** Speech in and out for the [Coach]. Everything happens on the main thread. */
interface CoachIO {
    /** Speaks [text] (with the microphone off), then calls [then]. */
    fun say(text: String, then: () -> Unit)

    /** Stops speaking without calling the pending [say] callback. */
    fun stopSaying()

    /**
     * Starts the microphone. Heard words go to [Coach.onHeard]; [words] and [position] steer the
     * recognizer toward the passage. A failure goes to [Coach.onFailed].
     */
    fun listen(words: List<String>, position: Int)

    /** Where in [listen]'s words the reciter is now. */
    fun setPosition(position: Int)

    fun stopListening()

    /** Recited perfectly enough times in a row to count as memorized. */
    fun mastered(passage: Passage)

    /** Recited perfectly once. */
    fun practiced(passage: Passage)
}

enum class CoachMode {
    /** One passage at a time, each until it's mastered. */
    Learn,

    /** The whole collection from the top, each passage once. */
    All,
}

enum class CoachPhase { Idle, Speaking, Reciting, Waiting, Finished, Failed }

/** Everything a coach screen shows. */
data class CoachState(
    val mode: CoachMode,
    val phase: CoachPhase,
    /** 0-based position in the collection and the collection's size. */
    val index: Int,
    val count: Int,
    val reference: String,
    /** The ESV section heading, if any. */
    val title: String?,
    val passageText: String,
    /** Perfect recitations in a row of this passage, out of [masteryGoal]. */
    val streak: Int,
    val masteryGoal: Int,
    /** What the coach said last. */
    val said: String,
    /** What the recognizer is hearing right now. */
    val heard: String,
    val error: String,
)

/**
 * A hands-free, voice-driven memorization session over a collection. By default it works through
 * the passages one at a time: it reads a passage, the user recites it, and it says what was left out
 * or out of order and asks them to try again, until the passage is recited perfectly [masteryGoal]
 * times in a row. Then it moves on. Spoken commands ("verse yes", "verse speak", ...) steer it; see
 * [VoiceCommand].
 */
class Coach(
    private val passages: List<Passage>,
    private val profile: VoiceProfile?,
    private val io: CoachIO,
    private val masteryGoal: Int = 2,
) {
    var onChanged: (CoachState) -> Unit = {}

    /** The session ended with "verse stop". */
    var onStopped: () -> Unit = {}

    private var mode = CoachMode.Learn
    private var phase = CoachPhase.Idle
    private var index = 0
    private var streak = 0
    private var recitation = recitationFor(0)
    private var said = ""
    private var heard = ""
    private var error = ""
    private var lastFeedback = ""

    /** Where to go back to when a command interrupts (help, speak, hint). */
    private var resumePhase = CoachPhase.Reciting

    private val masteredIds = passages.filter { it.mastered }.map { it.id }.toMutableSet()

    val state: CoachState
        get() = CoachState(
            mode = mode,
            phase = phase,
            index = index,
            count = passages.size,
            reference = passages.getOrNull(index)?.reference.orEmpty(),
            title = passages.getOrNull(index)?.title,
            passageText = passages.getOrNull(index)?.let { recitation.spokenText }.orEmpty(),
            streak = streak,
            masteryGoal = masteryGoal,
            said = said,
            heard = heard,
            error = error,
        )

    private val passage get() = passages[index]

    private fun recitationFor(i: Int) = Recitation(passages.getOrNull(i)?.text.orEmpty(), profile)

    private fun emit() = onChanged(state)

    // ---------- Starting and stopping ----------

    fun start() {
        if (passages.isEmpty()) {
            say("This collection has no passages yet.") { finish() }
            return
        }
        val first = passages.indexOfFirst { it.id !in masteredIds }
        if (first < 0) {
            startAll("Every passage in this collection is mastered, so let's recite them all from the top.")
            return
        }
        mode = CoachMode.Learn
        goTo(first, "Let's learn")
    }

    /** Stops speaking and listening without a word, e.g. when leaving the screen. */
    fun shutdown() {
        io.stopSaying()
        io.stopListening()
        phase = CoachPhase.Idle
        emit()
    }

    // ---------- Flow ----------

    private fun goTo(i: Int, lead: String) {
        index = i
        streak = 0
        recitation = recitationFor(i)
        lastFeedback = ""
        when (mode) {
            CoachMode.Learn -> say("$lead ${named(passage)}. Listen first. ${recitation.spokenText} Now you say it.") { beginAttempt() }
            CoachMode.All -> say("$lead ${named(passage)}.") { beginAttempt() }
        }
    }

    /** "John 8:1–11, The Woman Caught in Adultery" */
    private fun named(p: Passage) = p.title?.let { "${p.reference}, $it" } ?: p.reference

    private fun startAll(lead: String = "From the top.") {
        mode = CoachMode.All
        goTo(0, lead)
    }

    private fun advance() {
        if (index + 1 < passages.size) goTo(index + 1, "Next,")
        else finishCollection()
    }

    private fun finishCollection() {
        val text = when {
            mode == CoachMode.All -> "That's the whole collection. Say verse all to go again."
            passages.all { it.id in masteredIds } ->
                "You've mastered every passage in this collection. Say verse all to recite them all from the top."
            else -> "That's the end of the collection. Say verse all to recite them all from the top."
        }
        say(text) { finish() }
    }

    private fun finish() {
        phase = CoachPhase.Finished
        heard = ""
        if (passages.isNotEmpty()) io.listen(recitation.speechWords, 0)
        emit()
    }

    /** A fresh recitation of the current passage. */
    private fun beginAttempt() {
        recitation.clear()
        listenFor(CoachPhase.Reciting)
    }

    private fun listenFor(p: CoachPhase) {
        phase = p
        heard = ""
        io.listen(recitation.speechWords, if (p == CoachPhase.Reciting) recitation.position() else 0)
        emit()
    }

    private fun finishAttempt() {
        io.stopListening()
        val grade = recitation.grade()
        if (!grade.heardAnything) {
            say("I haven't heard any of ${passage.reference} yet. Go ahead and say it.") { beginAttempt() }
            return
        }
        lastFeedback = recitation.feedback(grade)

        if (grade.perfect) {
            streak++
            io.practiced(passage)
            when {
                mode == CoachMode.All -> {
                    lastFeedback = "Perfect."
                    if (index + 1 < passages.size) goTo(index + 1, "Perfect. Next,") else say("Perfect.") { finishCollection() }
                }
                streak >= masteryGoal -> {
                    masteredIds += passage.id
                    io.mastered(passage)
                    say("Perfect. You've mastered ${passage.reference}.") { advance() }
                }
                else -> say("Perfect. Say it once more to lock it in.") { beginAttempt() }
            }
        } else {
            streak = 0
            val ask = if (mode == CoachMode.All) "Say verse yes to try again, or verse next to move on." else "Say verse yes to try again."
            say("$lastFeedback $ask") { listenFor(CoachPhase.Waiting) }
        }
    }

    // ---------- Speech ----------

    private fun say(text: String, then: () -> Unit) {
        io.stopListening()
        if (phase != CoachPhase.Speaking) resumePhase = phase
        phase = CoachPhase.Speaking
        said = text
        heard = ""
        emit()
        io.say(text, then)
    }

    /** Goes back to what was happening before an interruption, keeping what was recited so far. */
    private fun resume() {
        when (resumePhase) {
            CoachPhase.Reciting -> listenFor(CoachPhase.Reciting)
            CoachPhase.Waiting -> listenFor(CoachPhase.Waiting)
            CoachPhase.Finished -> finish()
            else -> beginAttempt()
        }
    }

    /** Words from the recognizer: candidate word lists, best first. [final] ends the utterance. */
    fun onHeard(candidates: List<List<HeardWord>>, final: Boolean) {
        if (phase != CoachPhase.Reciting && phase != CoachPhase.Waiting && phase != CoachPhase.Finished) return
        heard = candidates.firstOrNull()?.joinToString(" ") { it.text }.orEmpty()
        if (!final) {
            emit()
            return
        }

        val found = VoiceCommands.find(candidates)
        if (found != null) {
            if (phase == CoachPhase.Reciting && found.before.isNotEmpty()) recitation.add(listOf(found.before))
            command(found.command)
            return
        }

        if (phase == CoachPhase.Reciting) {
            recitation.add(candidates)
            io.setPosition(recitation.position())
            if (recitation.grade().reachedEnd) {
                finishAttempt()
                return
            }
        }
        emit()
    }

    fun onFailed(message: String) {
        io.stopListening()
        phase = CoachPhase.Failed
        error = message
        emit()
    }

    // ---------- Commands ----------

    /** Runs a command, whether spoken or tapped. */
    fun command(c: VoiceCommand) {
        if (passages.isEmpty()) return
        if (phase == CoachPhase.Speaking) {
            // Tapped while the coach was talking: cut it off.
            io.stopSaying()
            phase = if (resumePhase == CoachPhase.Idle) {
                // Still introducing the passage.
                recitation.clear()
                CoachPhase.Reciting
            } else {
                resumePhase
            }
        }
        if (phase == CoachPhase.Failed || phase == CoachPhase.Idle) {
            error = ""
            when (c) {
                VoiceCommand.All -> startAll()
                VoiceCommand.Stop -> stop()
                else -> beginAttempt()
            }
            return
        }

        when (c) {
            VoiceCommand.Yes -> when (phase) {
                CoachPhase.Finished -> startAll()
                else -> say("Go ahead.") { beginAttempt() }
            }

            VoiceCommand.Review -> when (phase) {
                CoachPhase.Reciting -> finishAttempt()
                else -> say(lastFeedback.ifEmpty { "Nothing to review yet." }) { resume() }
            }

            VoiceCommand.Speak -> {
                val then = phase
                say("${passage.reference}. ${recitation.spokenText}") {
                    // Having just heard it, start the recitation over.
                    if (then == CoachPhase.Reciting) beginAttempt() else resume()
                }
            }

            VoiceCommand.Hint -> when (phase) {
                CoachPhase.Reciting -> say(recitation.hint()) { resume() }
                else -> say(recitation.text(0, 3).trimEnd(',', ';', ':', '.')) { beginAttempt() }
            }

            VoiceCommand.All -> startAll()
            VoiceCommand.Next -> advance()
            VoiceCommand.Help -> say(VoiceCommands.helpText()) { resume() }
            VoiceCommand.Stop -> stop()
        }
    }

    private fun stop() {
        say("Stopping here. Good work.") {
            phase = CoachPhase.Idle
            emit()
            onStopped()
        }
    }
}
