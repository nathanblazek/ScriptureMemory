package com.nathanblazek.scripturememory.practice

import com.nathanblazek.scripturememory.model.VoiceProfile

enum class PracticeMode { Full, FirstLetter, Blur }

enum class RevealKind {
    Hidden,
    /** Revealed by saying it correctly. */
    Spoken,
    /** Revealed by tapping it. */
    Peeked,
    /** Skipped over while speaking (the next word was said instead). */
    Missed,
}

/** A recognized word with the recognizer's confidence (0..1). */
data class HeardWord(val text: String, val confidence: Float)

/** What the recognizer reports back to the practice session. Called on the main thread. */
interface SpeechEvents {
    /** Words heard in the current utterance: candidate word lists, best first. [final] ends the utterance. */
    fun onHeard(candidates: List<List<HeardWord>>, final: Boolean)

    /** Microphone input level, 0–100. */
    fun onAudioLevel(level: Int)

    /** The recognizer can't continue; [message] says why. */
    fun onFailed(message: String)
}

/** A running speech recognizer, steered toward the words the reciter is expected to say next. */
interface SpeechEngine {
    fun start()

    /** Tells the recognizer where the reciter is now (an index into the passage's speakable words). */
    fun setExpectedPosition(position: Int)

    fun shutdown()
}

/**
 * Creates a [SpeechEngine] for the passage's speakable words, starting at [start]. Throws with a
 * user-facing message if speech isn't available. [calibrate] means the user is reading the visible text.
 */
typealias SpeechEngineFactory = (words: List<String>, start: Int, calibrate: Boolean, events: SpeechEvents) -> SpeechEngine

data class Banner(val title: String, val message: String)

/** Everything the practice screen draws. */
data class PracticeState(
    val mode: PracticeMode,
    val reveal: List<RevealKind>,
    val readMark: List<Boolean>,
    /** Index into the words of the word the reciter should say next, while listening. */
    val currentWord: Int?,
    val listening: Boolean,
    val calibrating: Boolean,
    val micLevel: Int,
    val status: String,
    val stats: String,
    val banner: Banner?,
)

/**
 * The practice logic of the Windows app's PracticeView, without any UI: hiding words, tap to peek,
 * reciting out loud, and voice calibration.
 */
class PracticeController(
    passageText: String,
    private val profile: VoiceProfile?,
    /**
     * Hands-free practice (driving): an utterance that matches nothing counts as a wrong word, and
     * the word that was expected is marked missed so the reciter can carry on after it.
     */
    private val strictMisses: Boolean = false,
    private val engineFactory: SpeechEngineFactory,
) : SpeechEvents {
    /** All tokens, in order (words, verse numbers, paragraph breaks). */
    val tokens: List<WordToken> = Tokenizer.tokenize(passageText)

    /** For each token, its index among the words, or -1 if it isn't a word. */
    val wordIndexOfToken: IntArray

    private val words: List<WordToken>

    /** Every speakable word part in reading order ("well-pleased" contributes two): (word index, key). */
    private val parts: List<Pair<Int, String>>

    private var mode = PracticeMode.Full
    private val reveal: Array<RevealKind>
    private val readMark: BooleanArray
    private var speech: SpeechEngine? = null

    private var cursor = 0                // index into parts of the next part the reciter should say
    private var utteranceStart: Int? = null // cursor when the current utterance began
    private var spoken = 0
    private var peeked = 0
    private var missed = 0
    private var completed = false
    private var micLevel = 0
    private var status = ""
    private var stats = ""
    private var banner: Banner? = null
    private var current: Int? = null

    // Calibration (reading the visible text aloud)
    private var calibrating = false
    private val calibratedKeys = LinkedHashSet<String>()

    var onChanged: (PracticeState) -> Unit = {}
    var onPracticeCompleted: () -> Unit = {}

    /** Calibration has updated the profile and it should be saved. */
    var onProfileChanged: () -> Unit = {}

    /** A spoken word was skipped or said wrong while reciting; [display] is the word as written. */
    var onWordMissed: (display: String) -> Unit = {}

    init {
        val w = mutableListOf<WordToken>()
        val p = mutableListOf<Pair<Int, String>>()
        wordIndexOfToken = IntArray(tokens.size) { -1 }
        tokens.forEachIndexed { i, token ->
            if (token.kind == TokenKind.Word) {
                wordIndexOfToken[i] = w.size
                for (key in token.matchParts) p += w.size to key
                w += token
            }
        }
        words = w
        parts = p
        reveal = Array(words.size) { RevealKind.Hidden }
        readMark = BooleanArray(words.size)
        setMode(PracticeMode.Full)
    }

    val state: PracticeState
        get() = PracticeState(
            mode = mode,
            reveal = reveal.toList(),
            readMark = readMark.toList(),
            currentWord = current,
            listening = speech != null && !calibrating,
            calibrating = calibrating,
            micLevel = micLevel,
            status = status,
            stats = stats,
            banner = banner,
        )

    fun isConcealed(word: Int): Boolean =
        mode != PracticeMode.Full && words[word].hasLetters && reveal[word] == RevealKind.Hidden

    private fun revealWord(word: Int, kind: RevealKind): Boolean {
        if (!isConcealed(word)) return false
        reveal[word] = kind
        return true
    }

    // ---------- Modes ----------

    fun setMode(mode: PracticeMode) {
        this.mode = mode
        reveal.fill(RevealKind.Hidden)
        readMark.fill(false)
        spoken = 0; peeked = 0; missed = 0
        cursor = 0
        utteranceStart = null
        completed = false
        banner = null

        if (mode == PracticeMode.Full) {
            stopSpeech()
            status = "Pick “First letters” or “Blur” to hide the words. Tap any hidden word to peek at it."
        } else if (speech == null) {
            status = "Tap a hidden word to reveal it, or press Speak and recite the passage out loud."
        }
        updateProgress()
    }

    fun reset() = setMode(mode)

    // ---------- Revealing ----------

    fun wordTapped(word: Int) {
        if (!isConcealed(word)) return
        revealWord(word, RevealKind.Peeked)
        peeked++
        updateProgress()
    }

    /** The first still-hidden word at or after the cursor (what the reciter should say next). */
    private fun currentWord(): Int? {
        for (i in cursor until parts.size) if (isConcealed(parts[i].first)) return parts[i].first
        return null
    }

    private fun updateProgress() {
        current = when {
            calibrating -> if (cursor < parts.size) parts[cursor].first else null
            speech != null -> currentWord()
            else -> null
        }

        val total = words.count { it.hasLetters }
        when {
            calibrating -> stats = "Read ${minOf(cursor, parts.size)}/${parts.size} words"
            mode == PracticeMode.Full -> stats = "$total words"
            else -> {
                val hidden = words.indices.count { isConcealed(it) }
                stats = "${total - hidden}/$total revealed · spoken $spoken · peeked $peeked" +
                    if (missed > 0) " · missed $missed" else ""

                if (hidden == 0 && total > 0 && !completed) {
                    completed = true
                    stopSpeech()
                    banner = Banner(
                        "Passage complete!",
                        if (spoken > 0) "You recited $spoken of $total words (${spoken * 100 / total}%). Press Reset to go again."
                        else "All words revealed. Press Reset to go again.",
                    )
                    onPracticeCompleted()
                }
            }
        }
        emit()
    }

    private fun emit() = onChanged(state)

    // ---------- Speech ----------

    private fun passageWords(): List<String> = tokens.filter { it.isSpeakable }.flatMap { it.speechParts }

    fun startSpeech() {
        if (speech != null || mode == PracticeMode.Full) return

        // Pick up where the reciter left off.
        cursor = parts.indexOfFirst { isConcealed(it.first) }.let { if (it < 0) parts.size else it }
        utteranceStart = null

        val engine = try {
            createEngine(calibrate = false)
        } catch (e: Exception) {
            status = e.message ?: "Speech recognition isn't available."
            emit()
            return
        }
        speech = engine
        engine.start()
        status = "Listening. Recite at your own pace; words appear as you say them."
        updateProgress()
    }

    private fun createEngine(calibrate: Boolean): SpeechEngine {
        // Same order and count as parts, so positions line up.
        val words = passageWords()
        if (words.isEmpty()) throw IllegalStateException("This passage has no words that can be spoken.")
        return engineFactory(words, cursor, calibrate, this)
    }

    fun stopSpeech() {
        if (calibrating) {
            // Leaving mid-calibration keeps what was read so far.
            finishCalibration(save = true)
            return
        }
        speech?.shutdown()
        speech = null
        utteranceStart = null
        micLevel = 0
        current = null
        emit()
    }

    /** Stops the microphone; call when leaving the screen. */
    fun shutdown() = stopSpeech()

    override fun onAudioLevel(level: Int) {
        if (speech == null) return
        micLevel = level
        emit()
    }

    override fun onFailed(message: String) {
        if (speech == null) return
        stopSpeech()
        status = message
        emit()
    }

    /**
     * Aligns what was heard in the current utterance against the passage starting where the
     * utterance began. Partial results are re-aligned as they grow, so words appear while the user is
     * still speaking; the final result also fills in words that were skipped over.
     */
    override fun onHeard(candidates: List<List<HeardWord>>, final: Boolean) {
        if (speech == null) return

        val start = utteranceStart ?: cursor.also { utteranceStart = it }
        if (final) utteranceStart = null
        if (candidates.isEmpty() || candidates.all { it.isEmpty() }) return

        status = "Heard: " + candidates[0].joinToString(" ") { it.text }

        val match = alignHeard(candidates, start, if (calibrating) null else profile)
        if (match == null) {
            if (final && strictMisses && !calibrating) missCurrentWord() else emit()
            return
        }

        if (calibrating) applyCalibration(match, start, final) else applyPractice(match, start, final)

        // Point the recognizer at what should come next.
        if (final) speech?.setExpectedPosition(cursor)
        updateProgress()

        if (calibrating && final && cursor >= parts.size) finishCalibration(save = true)
    }

    private class HeardMatch(val alignment: Aligner.Alignment, val windowStart: Int, val confidence: List<Float>) {
        fun partIndex(target: Int) = windowStart + target
        val lastPart get() = windowStart + alignment.lastMatchedTarget
    }

    /** Chooses whichever of the recognizer's guesses fits the passage best. */
    private fun alignHeard(candidates: List<List<HeardWord>>, start: Int, profile: VoiceProfile?): HeardMatch? {
        val windowStart = maxOf(0, start - LOOK_BACK)
        val freeSkips = start - windowStart
        val longest = candidates.maxOf { it.size }
        // Never let an utterance reach further ahead than the number of words said, plus a couple of skips.
        val windowEnd = minOf(parts.size, start + longest + MAX_SKIP_AHEAD)
        if (windowEnd <= windowStart) return null

        val targets = parts.subList(windowStart, windowEnd).map { it.second }

        var best: HeardMatch? = null
        for (heardWords in candidates) {
            val heard = heardWords.map { Tokenizer.key(it.text) to it.confidence }.filter { it.first.isNotEmpty() }
            val confidence = heard.map { it.second }
            val a = Aligner.align(heard.map { it.first }, targets, freeSkips, confidence, profile)
            if (a != null && (best == null || a.score > best.alignment.score)) best = HeardMatch(a, windowStart, confidence)
        }
        return best
    }

    /** Practice: reveal the words that were said. */
    private fun applyPractice(match: HeardMatch, start: Int, final: Boolean) {
        // Words with at least one matched part were spoken.
        val spokenWords = match.alignment.matchedTargets.map { parts[match.partIndex(it)].first }.toSet()
        for (w in spokenWords) if (revealWord(w, RevealKind.Spoken)) spoken++

        val lastMatched = match.lastPart

        // On the final result, reveal anything skipped between where the utterance started and the last
        // matched word. Words the recognizer tends to drop ("the", "of", or ones calibration found it
        // rarely hears in this voice) count as spoken; others as missed.
        if (final) {
            for (i in start until lastMatched) {
                val (w, key) = parts[i]
                if (!isConcealed(w) || w in spokenWords) continue
                val generous = Aligner.isEasySkip(key, profile)
                if (revealWord(w, if (generous) RevealKind.Spoken else RevealKind.Missed)) {
                    if (generous) spoken++ else {
                        missed++
                        onWordMissed(words[w].display)
                    }
                }
            }
        }

        cursor = maxOf(cursor, lastMatched + 1)
        revealUnspeakableBefore(cursor)
    }

    /** Strict mode: what was heard didn't fit the passage, so the word expected next was said wrong. */
    private fun missCurrentWord() {
        val w = currentWord()
        if (w == null) {
            emit()
            return
        }
        revealWord(w, RevealKind.Missed)
        missed++
        cursor = parts.indexOfLast { it.first == w } + 1
        revealUnspeakableBefore(cursor)
        speech?.setExpectedPosition(cursor)
        onWordMissed(words[w].display)
        updateProgress()
    }

    // ---------- Calibration ----------

    fun startCalibration() {
        stopSpeech()
        setMode(PracticeMode.Full)
        calibratedKeys.clear()
        cursor = 0
        utteranceStart = null

        val engine = try {
            createEngine(calibrate = true)
        } catch (e: Exception) {
            status = e.message ?: "Speech recognition isn't available."
            emit()
            return
        }

        calibrating = true
        speech = engine
        engine.start()
        banner = null
        status = "Read the passage aloud at your normal reciting pace. Words turn green as they're heard."
        updateProgress()
    }

    /** Calibration: mark words as read and record how well each one was heard. */
    private fun applyCalibration(match: HeardMatch, start: Int, final: Boolean) {
        val matchedParts = HashSet<Int>()
        for ((spokenIdx, target) in match.alignment.matches) {
            val part = match.partIndex(target)
            matchedParts += part
            readMark[parts[part].first] = true

            // Record final results only, and only words at/after where this utterance began
            // (look-back words were already recorded).
            if (final && part >= start && profile != null) {
                profile.recordHeard(parts[part].second, match.confidence[spokenIdx].toDouble())
                calibratedKeys += parts[part].second
            }
        }

        if (final) {
            // Words between that the recognizer didn't pick up, even though the user was reading them.
            for (i in start until match.lastPart) {
                if (i in matchedParts) continue
                readMark[parts[i].first] = true
                if (profile != null) {
                    profile.recordMissed(parts[i].second)
                    calibratedKeys += parts[i].second
                }
            }
        }

        cursor = maxOf(cursor, match.lastPart + 1)
    }

    fun finishCalibration(save: Boolean) {
        if (!calibrating) return
        calibrating = false

        speech?.shutdown()
        speech = null
        utteranceStart = null
        micLevel = 0

        if (save && calibratedKeys.isNotEmpty()) onProfileChanged()

        if (calibratedKeys.isEmpty()) {
            status = "Calibration stopped. Nothing was heard, so nothing was changed."
            updateProgress()
            return
        }

        val hard = calibratedKeys
            .filter { profile != null && profile.difficulty(it) >= 0.35 }
            .sortedByDescending { profile!!.difficulty(it) }
            .take(10)
            .map(::displayWord)

        val n = calibratedKeys.size
        banner = Banner(
            "Calibration saved",
            "Learned how $n word${if (n == 1) "" else "s"} sound in your voice. " +
                if (hard.isNotEmpty()) "Extra leeway for: ${hard.joinToString(", ")}." else "Every word was heard clearly.",
        )
        status = "Reading it once or twice more improves calibration further."
        updateProgress()
    }

    private fun displayWord(key: String): String {
        val part = parts.firstOrNull { it.second == key } ?: return key
        val word = words[part.first].display.filter { it.isLetter() || it == '\'' || it == '’' || it == '-' }
        return word.ifEmpty { key }
    }

    /** Numerals and similar can't be recognized; reveal them once the reciter has passed them. */
    private fun revealUnspeakableBefore(cursor: Int) {
        val limit = if (cursor < parts.size) parts[cursor].first else words.size
        for (i in 0 until limit) {
            if (!words[i].isSpeakable && revealWord(i, RevealKind.Missed)) missed++
        }
    }

    private companion object {
        /** How many already-passed words the reciter may back up and repeat. */
        const val LOOK_BACK = 6

        /** How many words beyond what was actually spoken the alignment may reach (skipped words). */
        const val MAX_SKIP_AHEAD = 2
    }
}
