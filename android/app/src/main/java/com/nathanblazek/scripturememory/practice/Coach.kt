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

    /** The voice profile learned something and should be saved. */
    fun profileChanged()

    /** The recognizer listens without breaks between utterances, so it doesn't lose words there. */
    val continuousListening: Boolean get() = false

    /** A subtle sound while the reciter keeps going: a word was just left out or said wrong. */
    fun blip() {}

    /** A subtle, happier sound: a verse was just recited right. */
    fun chime() {}

    /** One line for the listening log: what was heard and what the coach made of it. */
    fun log(line: String) {}
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
    private var verses: List<Verse> = passages.firstOrNull()?.let(Verses::split).orEmpty()
    private var recitation = recitationFor(0)

    /** The verse the current attempt starts from: after a correction, the verse that was wrong. */
    private var fromVerse = 0

    /** A verse of this passage needed correcting since its last full recitation. */
    private var corrected = false
    private var said = ""
    private var heard = ""
    private var error = ""
    private var lastFeedback = ""

    /** The top guess of the utterance in progress, kept in case the final result drops words. */
    private var lastPartial: List<HeardWord>? = null

    /** The last verse corrected, so "verse correct" can say it was right after all. */
    private class Flagged(val recitation: Recitation, val verse: Int, val fromVerse: Int, val correctedBefore: Boolean)
    private var flagged: Flagged? = null

    /** Mistakes already blipped in this attempt, by their first word. */
    private val blipped = HashSet<Int>()

    /** Verses of this attempt already chimed as right. */
    private val chimed = HashSet<Int>()

    /** The verse (of this attempt) that was blipped, which the reciter may start over on their own. */
    private var restartVerse: Int? = null

    /** In the utterance under way, how many words had been heard when the blip came; a restart comes after. */
    private var restartAfter = 0

    /** The reciter was prompted or given a hint in this attempt, so it isn't a perfect one. */
    private var helped = false

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
            passageText = passages.getOrNull(index)?.let { recitationFor(index).spokenText }.orEmpty(),
            streak = streak,
            masteryGoal = masteryGoal,
            said = said,
            heard = heard,
            error = error,
        )

    private val passage get() = passages[index]

    private fun recitationFor(i: Int) = Recitation(passages.getOrNull(i)?.text.orEmpty(), profile, io.continuousListening)

    /** The current passage from verse [from] on. */
    private fun recitationFrom(from: Int): Recitation =
        if (from == 0 || from >= verses.size) recitationFor(index)
        else Recitation(verses.drop(from).mapIndexed { i, v -> "[${i + 1}] ${v.text}" }.joinToString(" "), profile, io.continuousListening)

    private fun emit() = onChanged(state)

    // ---------- Starting and stopping ----------

    fun start() {
        io.log("start ${passages.size} passages")
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
        verses = Verses.split(passage)
        fromVerse = 0
        corrected = false
        flagged = null
        io.log("passage ${passage.reference}")
        recitation = recitationFor(i)
        lastFeedback = ""
        when (mode) {
            CoachMode.Learn -> say("$lead ${named(passage)}. Listen first. ${sentence(recitation.spokenText)} Now you say it.") { beginAttempt() }
            CoachMode.All -> say("$lead ${named(passage)}.") { beginAttempt() }
        }
    }

    /** Ends [text] with a full stop, so what's said after it doesn't run on. */
    private fun sentence(text: String): String {
        val t = text.trimEnd(',', ';', ':', ' ', '—', '–')
        return if (t.isEmpty() || t.last() in ".!?”’\"'") t else "$t."
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

    /** A fresh recitation of the current passage, from verse [from] on. */
    private fun beginAttempt(from: Int = fromVerse) {
        startFrom(from)
        listenFor(CoachPhase.Reciting)
    }

    /** A new recitation of the current passage from verse [from], with nothing heard yet. */
    private fun startFrom(from: Int) {
        fromVerse = from
        lastPartial = null
        recitation = recitationFrom(from)
        blipped.clear()
        chimed.clear()
        restartVerse = null
        restartAfter = 0
        helped = false
    }

    /** Stops the reciter at a verse they got wrong, reads it to them, and has them go again from there. */
    private fun correct(verse: Int) {
        io.stopListening()
        flagged = Flagged(recitation, verse - fromVerse, verse, corrected)
        streak = 0
        corrected = true
        val v = verses.getOrNull(verse)
        io.log("corrected ${v?.label ?: passage.reference}")
        lastFeedback = if (v != null) "That's not quite right. ${v.label}. ${sentence(v.spoken)}"
        else "That's not quite right. ${passage.reference}. ${sentence(recitationFor(index).spokenText)}"
        say("$lastFeedback Try again.") { beginAttempt(if (v != null) verse else 0) }
    }

    private fun listenFor(p: CoachPhase) {
        phase = p
        heard = ""
        io.listen(recitation.speechWords, if (p == CoachPhase.Reciting) recitation.position() else 0)
        emit()
    }

    /** The reciter reached the end, or asked how it's going ("verse review"). */
    private fun finishAttempt() {
        io.stopListening()
        val grade = recitation.grade()
        if (!grade.heardAnything) {
            say("I haven't heard any of ${passage.reference} yet. Go ahead and say it.") { beginAttempt() }
            return
        }
        wrongVerse(includeCurrent = true)?.let {
            correct(fromVerse + it)
            return
        }
        if (!recitation.grade().reachedEnd) {
            say("So far so good. Keep going.") { resume() }
            return
        }
        if (recitation.learnFromSuccess()) io.profileChanged()
        if (blipped.isNotEmpty() || helped) {
            // A slip fixed along the way, or a prompt: good, but not a perfect recitation.
            streak = 0
            corrected = true
        }
        io.log(if (blipped.isEmpty() && !helped) "perfect ${passage.reference}" else "fixed along the way ${passage.reference}")
        segmentDone("Perfect.")
    }

    /**
     * The first verse wrong in what's been recited, after looking again at everything heard (the
     * recognizer's other guesses) before calling any verse wrong. Relative to [fromVerse].
     */
    private fun wrongVerse(includeCurrent: Boolean = false): Int? {
        while (true) {
            val v = recitation.firstWrongVerse(recitation.grade(), includeCurrent) ?: return null
            if (!recitation.reassess(v)) return v
        }
    }

    /** The reciter got to the end of the passage with every verse right. */
    private fun segmentDone(praise: String) {
        if (corrected || fromVerse > 0) {
            // The fixed verses are right now; the whole passage still has to be said in one go.
            corrected = false
            when (mode) {
                CoachMode.Learn -> say("Good. Now say all of ${passage.reference} from the start.") { beginAttempt(0) }
                CoachMode.All -> if (index + 1 < passages.size) goTo(index + 1, "Good. Next,") else say("Good.") { finishCollection() }
            }
            return
        }

        lastFeedback = praise
        streak++
        io.practiced(passage)
        when {
            mode == CoachMode.All ->
                if (index + 1 < passages.size) goTo(index + 1, "$praise Next,") else say(praise) { finishCollection() }
            streak >= masteryGoal -> {
                masteredIds += passage.id
                io.mastered(passage)
                say("$praise You've mastered ${passage.reference}.") { advance() }
            }
            else -> say("$praise Say it once more to lock it in.") { beginAttempt(0) }
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
    fun onHeard(heardCandidates: List<List<HeardWord>>, final: Boolean) {
        if (phase != CoachPhase.Reciting && phase != CoachPhase.Waiting && phase != CoachPhase.Finished) return
        val candidates = heardCandidates.map(HeardText::normalize)
        heard = candidates.firstOrNull()?.joinToString(" ") { it.text }.orEmpty()
        if (!final) {
            candidates.firstOrNull()?.takeIf { it.isNotEmpty() }?.let {
                lastPartial = it
                // While a blipped verse is being started over, the old attempt's grade means nothing.
                val restarting = restartVerse?.let { v -> recitation.restartIn(it, v, restartAfter) } != null
                if (phase == CoachPhase.Reciting && !restarting && VoiceCommands.find(listOf(it)) == null) {
                    soundOff(recitation.gradeWith(it), it.size)
                }
            }
            emit()
            return
        }
        val partial = lastPartial
        lastPartial = null

        io.log("heard " + candidates.joinToString("  |  ") { c -> c.joinToString(" ") { it.text } } +
            (partial?.let { p -> "  (partial: ${p.joinToString(" ") { it.text }})" } ?: ""))
        val found = VoiceCommands.find(candidates)
        if (found != null) {
            if (phase == CoachPhase.Reciting && found.before.isNotEmpty()) recitation.add(listOf(found.before))
            command(found.command)
            return
        }

        if (phase == CoachPhase.Reciting) {
            val restart = restartVerse?.let { v -> restartIn(candidates, v) }
            restartAfter = 0
            if (restart != null) {
                // The reciter heard the blip and started the verse over: carry on from there.
                streak = 0
                corrected = true
                startFrom(fromVerse + restartVerse!!)
                recitation.add(restart)
                io.log("started over at ${verses.getOrNull(fromVerse)?.label ?: passage.reference}")
                io.listen(recitation.speechWords, recitation.position())
            } else {
                recitation.add(candidates, partial)
                io.setPosition(recitation.position())
            }
            val wrong = wrongVerse()
            if (wrong != null) {
                correct(fromVerse + wrong)
                return
            }
            val grade = recitation.grade()
            if (grade.reachedEnd) {
                finishAttempt()
                return
            }
            soundOff(grade, 0)
        }
        emit()
    }

    /** Each guess from where it starts verse [v] over (dropping guesses that don't), or null if none do. */
    private fun restartIn(candidates: List<List<HeardWord>>, v: Int): List<List<HeardWord>>? {
        val restarted = candidates.mapNotNull { c -> recitation.restartIn(c, v, restartAfter)?.let { c.drop(it) } }
        return restarted.ifEmpty { null }
    }

    /**
     * Sounds off while the reciter keeps going: a blip once for each new mistake they've gone well
     * past (so they can start that verse over on their own), and a chime for each verse right.
     * [heardSoFar] is how many words of the utterance under way are in [grade] (0 between utterances).
     */
    private fun soundOff(grade: Grade, heardSoFar: Int) {
        // A word the recognizer is still settling on isn't called wrong: wait for BLIP_AFTER more.
        val fresh = grade.mistakes.filter { it.last + BLIP_AFTER <= grade.lastWord && it.first !in blipped }
        if (fresh.isNotEmpty()) {
            fresh.forEach { blipped += it.first }
            restartVerse = recitation.verseOf(fresh.minOf { it.first })
            restartAfter = heardSoFar
            io.log("blip at “${recitation.text(fresh.first().first, fresh.first().last)}”")
            io.blip()
            return
        }
        // The last verse gets spoken praise instead.
        for (v in 0 until recitation.verseCount - 1) {
            if (v in chimed || !recitation.verseRight(grade, v)) continue
            // Only a verse just finished: one with a blip in it is corrected, not praised.
            chimed += v
            if (grade.lastWord <= recitation.verseEnd(v) + CHIME_WITHIN && blipped.none { recitation.verseOf(it) == v }) io.chime()
        }
    }

    /** The reciter has gone quiet mid-recitation: quietly says the next couple of words. */
    fun onSilence() {
        if (phase != CoachPhase.Reciting) return
        helped = true
        io.log("prompted")
        say(recitation.hint(PROMPT_WORDS)) { resume() }
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
                else -> say("Go ahead.") { beginAttempt(0) }
            }

            VoiceCommand.Review -> when (phase) {
                CoachPhase.Reciting -> finishAttempt()
                else -> say(lastFeedback.ifEmpty { "Nothing to review yet." }) { resume() }
            }

            VoiceCommand.Speak -> {
                val then = phase
                // The verses being worked on: the whole passage, or from a corrected verse on.
                val label = verses.getOrNull(fromVerse)?.takeIf { fromVerse > 0 }?.let { "From ${it.label}" } ?: passage.reference
                say("$label. ${sentence(recitationFrom(fromVerse).spokenText)}") {
                    // Having just heard it, start the recitation over.
                    if (then == CoachPhase.Reciting) beginAttempt() else resume()
                }
            }

            VoiceCommand.Hint -> when (phase) {
                CoachPhase.Reciting -> {
                    helped = true
                    say(recitation.hint()) { resume() }
                }
                else -> say(recitation.text(0, 3).trimEnd(',', ';', ':', '.')) { beginAttempt() }
            }

            VoiceCommand.All -> startAll()
            VoiceCommand.Next -> advance()
            VoiceCommand.Help -> say(VoiceCommands.helpText()) { resume() }
            VoiceCommand.Correct -> saidItRight()
            VoiceCommand.Stop -> stop()
        }
    }

    /** "Verse correct": the last verse called wrong was right; learn how the recognizer heard it. */
    private fun saidItRight() {
        val f = flagged
        if (f == null) {
            say("There's nothing to correct right now.") { resume() }
            return
        }
        flagged = null
        io.log("said it right")
        f.recitation.learnVerseWasRight(f.verse - f.fromVerse)
        io.profileChanged()
        corrected = f.correctedBefore
        val next = f.verse + 1
        if (next < verses.size) {
            say("Got it, I'll remember how you say that. Carry on from ${verses[next].label}.") { beginAttempt(next) }
        } else {
            fromVerse = f.fromVerse
            say("Got it, I'll remember how you say that.") { segmentDone("Good.") }
        }
    }

    private fun stop() {
        say("Stopping here. Good work.") {
            phase = CoachPhase.Idle
            emit()
            onStopped()
        }
    }

    private companion object {
        /** Words that must be heard past a mistake before it's blipped. */
        const val BLIP_AFTER = 2

        /** Words said to a reciter who has gone quiet. */
        const val PROMPT_WORDS = 2

        /** A verse is chimed only if it's noticed within this many words of its end. */
        const val CHIME_WITHIN = 3
    }
}
