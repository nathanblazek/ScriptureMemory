package com.nathanblazek.scripturememory.practice

import com.nathanblazek.scripturememory.model.VoiceProfile

/** One place a recitation went wrong. [first]..[last] are word indexes into the passage. */
data class Mistake(val kind: Kind, val first: Int, val last: Int) {
    enum class Kind {
        /** Words left out. */
        Missed,

        /** Words that were said, but somewhere else in the passage. */
        OutOfOrder,
    }
}

/** How a recitation went: what was wrong, how far it got, and whether it reached the end. */
data class Grade(
    val mistakes: List<Mistake>,
    /** Index of the last word recited (in order), or -1 if nothing matched. */
    val lastWord: Int,
    val reachedEnd: Boolean,
) {
    val heardAnything: Boolean get() = lastWord >= 0
    val perfect: Boolean get() = reachedEnd && mistakes.isEmpty()
}

/**
 * A whole recitation of one passage, heard as a series of utterances and graded at the end: words
 * left out, and words said in the wrong place. Unlike [PracticeController], which reveals words as
 * they're said, this keeps everything heard so a word said out of order can be recognized as such.
 */
class Recitation(passageText: String, private val profile: VoiceProfile?) {
    private val tokens = Tokenizer.tokenize(passageText)
    private val words: List<WordToken> = tokens.filter { it.kind == TokenKind.Word }

    /** The index of the first word of each verse. */
    private val verseStarts: List<Int> = buildList {
        add(0)
        var n = 0
        for (t in tokens) {
            if (t.kind == TokenKind.VerseNumber && n > last()) add(n)
            if (t.kind == TokenKind.Word) n++
        }
    }

    /** Every speakable word part in order: (word index, key). */
    private val parts: List<Pair<Int, String>> =
        words.flatMapIndexed { i, w -> w.matchParts.map { i to it } }

    /** The passage's speakable words, for steering the recognizer. Same order and count as [parts]. */
    val speechWords: List<String> = words.flatMap { it.speechParts }

    /** The passage as it should be read aloud (no verse numbers). */
    val spokenText: String = words.joinToString(" ") { it.display }.replace(Regex("\\s*[—–]+\\s*"), ", ")

    /** The last part a reciter has to say to reach the end (trailing "the"s and "of"s may be dropped). */
    private val lastNeededPart: Int =
        parts.indexOfLast { !Aligner.isEasySkip(it.second, profile) }.let { if (it < 0) parts.size - 1 else it }

    /** One utterance: all of the recognizer's guesses, and the one in use. */
    private class Utterance(val candidates: List<List<HeardWord>>, var chosen: Int)

    private val utterances = mutableListOf<Utterance>()

    /** The chosen guess of every utterance, in order. */
    private val heard = mutableListOf<HeardWord>()

    /** Indexes into [heard] where an utterance began; the recognizer tends to lose words around these. */
    private val utteranceStarts = mutableSetOf<Int>()

    val isEmpty: Boolean get() = heard.isEmpty()

    fun clear() {
        utterances.clear()
        rebuild()
    }

    private fun rebuild() {
        heard.clear()
        utteranceStarts.clear()
        for (u in utterances) {
            utteranceStarts += heard.size
            heard += u.candidates[u.chosen]
        }
    }

    /**
     * Adds one utterance, choosing whichever of the recognizer's guesses fits the passage best. The
     * last partial result is kept as one more guess: it sometimes has words the final result dropped.
     */
    fun add(candidates: List<List<HeardWord>>, partial: List<HeardWord>? = null) {
        // A partial that's just the start of a final guess adds nothing, and would let a wrong last
        // word be explained away as lost between utterances.
        fun keys(c: List<HeardWord>) = c.map { Tokenizer.key(it.text) }.filter { it.isNotEmpty() }
        val extra = partial?.takeUnless { p -> candidates.any { c -> keys(c).take(keys(p).size) == keys(p) } }
        val usable = (candidates + listOfNotNull(extra))
            .map { c -> c.filter { Tokenizer.key(it.text).isNotEmpty() } }
            .filter { it.isNotEmpty() }
            .distinctBy { c -> c.joinToString(" ") { Tokenizer.key(it.text) } }
        if (usable.isEmpty()) return
        val best = usable.indices.maxBy { align(heard + usable[it])?.score ?: Double.NEGATIVE_INFINITY }
        utterances += Utterance(usable, best)
        rebuild()
    }

    /**
     * How the recitation stands with the utterance still in progress ([partial]) included, without
     * keeping it: the final result for that utterance replaces it.
     */
    fun gradeWith(partial: List<HeardWord>): Grade {
        val usable = partial.filter { Tokenizer.key(it.text).isNotEmpty() }
        if (usable.isEmpty()) return grade()
        utterances += Utterance(listOf(usable), 0)
        rebuild()
        try {
            return grade()
        } finally {
            utterances.removeAt(utterances.lastIndex)
            rebuild()
        }
    }

    /**
     * Before calling [verse] wrong, looks again at everything heard: tries the recognizer's other
     * guesses for each utterance, keeping any that make the verse come out right (without losing
     * ground elsewhere). Returns true if the verse is right after all.
     */
    fun reassess(verse: Int): Boolean {
        val baseline = grade()
        fun wrongIn(g: Grade): Int =
            if (g.lastWord < baseline.lastWord) Int.MAX_VALUE else mistakesIn(g, verse).size + g.mistakes.size

        var current = wrongIn(baseline)
        for (u in utterances) {
            if (mistakesIn(grade(), verse).isEmpty()) return true
            if (u.candidates.size < 2) continue
            var best = u.chosen
            for (c in u.candidates.indices) {
                if (c == u.chosen) continue
                val keep = u.chosen
                u.chosen = c
                rebuild()
                val n = wrongIn(grade())
                if (n < current) {
                    current = n
                    best = c
                }
                u.chosen = keep
                rebuild()
            }
            u.chosen = best
            rebuild()
        }
        return mistakesIn(grade(), verse).isEmpty()
    }

    val verseCount: Int get() = verseStarts.size

    /** The verse (0-based) that word [word] is in. */
    fun verseOf(word: Int): Int = verseStarts.indexOfLast { it <= word }.coerceAtLeast(0)

    /** The last word of verse [v]. */
    fun verseEnd(v: Int): Int = verseRange(v).last

    /** True if [v] has been recited to its last word with nothing wrong in it. */
    fun verseRight(grade: Grade, v: Int): Boolean = grade.lastWord >= verseEnd(v) && mistakesIn(grade, v).isEmpty()

    /**
     * Where in [heard] the reciter starts verse [v] over: its first few words, said again, at or
     * after [from]. Null if they don't.
     */
    fun restartIn(heard: List<HeardWord>, v: Int, from: Int = 0): Int? {
        val first = verseRange(v).first
        val opening = parts.filter { it.first >= first }.take(RESTART_WORDS).map { it.second }
        if (opening.isEmpty()) return null
        // One of the opening words may be lost, as long as two are heard (or all of a shorter opening).
        val needed = minOf(opening.size, 2)
        val at = heard.indices.filter { Tokenizer.key(heard[it].text).isNotEmpty() }
        val keys = at.map { Tokenizer.key(heard[it].text) }
        fun like(k: Int, w: String) = Aligner.similarity(keys[k], w) >= RESTART_SIMILARITY
        for (i in keys.indices) {
            if (at[i] < from) continue
            // The opening's words in order from here, each right after the last one found.
            val startWord = (0..1).firstOrNull { it < opening.size && like(i, opening[it]) } ?: continue
            var k = i + 1
            var matched = 1
            for (w in opening.drop(startWord + 1)) {
                if (k < keys.size && like(k, w)) {
                    matched++
                    k++
                }
            }
            if (matched >= needed) return at[i]
        }
        return null
    }

    private fun verseRange(v: Int): IntRange = verseStarts[v] until (verseStarts.getOrNull(v + 1) ?: words.size)

    private fun mistakesIn(g: Grade, verse: Int): List<Mistake> {
        val r = verseRange(verse)
        return g.mistakes.filter { it.first <= r.last && it.last >= r.first }
    }

    /** The part the reciter should say next (for steering the recognizer). */
    fun position(): Int = align(heard)?.let { it.lastMatchedTarget + 1 } ?: 0

    private fun align(spoken: List<HeardWord>): Aligner.Alignment? = Aligner.align(
        spoken.map { Tokenizer.key(it.text) },
        parts.map { it.second },
        freeSkips = 0,
        confidence = spoken.map { it.confidence },
        profile = profile,
    )

    fun grade(): Grade {
        val a = align(heard) ?: return Grade(emptyList(), lastWord = -1, reachedEnd = false)
        val matched = a.matchedTargets.toHashSet()
        val lastPart = a.lastMatchedTarget

        // Heard words that didn't line up with the passage where they were said.
        val usedSpoken = a.matches.map { it.first }.toHashSet()
        val extras = heard.indices.filter { it !in usedSpoken }.map { Tokenizer.key(heard[it].text) }

        // Unmatched parts: left out, unless one of the extra words is that word said somewhere else.
        val outOfOrder = HashSet<Int>()
        val unmatched = parts.indices.filter { it !in matched && !Aligner.isEasySkip(parts[it].second, profile) }
        val remaining = extras.toMutableList()
        for (p in unmatched) {
            val i = remaining.indexOfFirst { Aligner.similarity(it, parts[p].second) >= OUT_OF_ORDER_SIMILARITY }
            if (i >= 0) {
                outOfOrder += p
                remaining.removeAt(i)
            }
        }

        val mistakes = mutableListOf<Mistake>()
        fun addRun(kind: Mistake.Kind, partIndexes: List<Int>) {
            var runFirst = -1
            var runLast = -1
            for (p in partIndexes.sorted()) {
                val w = parts[p].first
                if (runFirst >= 0 && w <= runLast + 1) {
                    runLast = maxOf(runLast, w)
                } else {
                    if (runFirst >= 0) mistakes += Mistake(kind, runFirst, runLast)
                    runFirst = w
                    runLast = w
                }
            }
            if (runFirst >= 0) mistakes += Mistake(kind, runFirst, runLast)
        }
        // Words past where the reciter stopped weren't left out, just not reached yet.
        val gaps = unmatched.filter { it < lastPart && it !in outOfOrder }
        val forgiven = benefitOfTheDoubt(a, gaps)
        addRun(Mistake.Kind.Missed, gaps.filter { it !in forgiven })
        addRun(Mistake.Kind.OutOfOrder, outOfOrder.toList())
        mistakes.sortBy { it.first }

        // The recognizer often drops the very last word as the reciter trails off.
        val reached = lastPart >= lastNeededPart - (if (parts.size > SHORT_PASSAGE) 1 else 0) ||
            (lastNeededPart in outOfOrder && lastPart >= lastNeededPart - 3)
        return Grade(mistakes, lastWord = parts[lastPart].first, reachedEnd = reached)
    }

    /**
     * Words the reciter most likely said but the recognizer missed or misheard, so they aren't
     * counted against them: a few words lost where one utterance ended and the next began, or a word
     * heard as something that sounds like it.
     */
    private fun benefitOfTheDoubt(a: Aligner.Alignment, gaps: List<Int>): Set<Int> {
        val spokenAt = a.matches.associate { (spoken, target) -> target to spoken }
        val matchedTargets = a.matchedTargets.sorted()
        val forgiven = HashSet<Int>()
        for (p in gaps) {
            val before = matchedTargets.lastOrNull { it < p } ?: -1
            val after = matchedTargets.firstOrNull { it > p } ?: continue
            // Words that had to be said: small words the reciter may drop don't count.
            val run = (before + 1 until after).count { !Aligner.isEasySkip(parts[it].second, profile) }
            val spokenFrom = if (before >= 0) spokenAt.getValue(before) + 1 else 0
            val spokenTo = spokenAt.getValue(after) // exclusive
            val between = heard.subList(spokenFrom, spokenTo).map { Tokenizer.key(it.text) }
            // A repeated word ("together for good, for those") can line up with either copy; if it
            // could be the copy before the gap, the gap really ends where the next utterance began.
            val couldShift = (spokenTo + 1) in utteranceStarts &&
                (before + 1 until after).any { Aligner.similarity(parts[it].second, parts[after].second) >= 0.95 }
            val acrossUtterances = couldShift || utteranceStarts.any { it in spokenFrom..spokenTo }
            val atStart = before < 0

            // Within one stretch of speech the recognizer rarely drops a whole word, so a gap there
            // is a real miss; between utterances (or at the very start) it often loses a few.
            val lostBetweenUtterances = between.isEmpty() && (acrossUtterances || atStart) && run <= MAX_LOST_RUN

            val runKeys = parts.subList(before + 1, after).map { it.second }
            val misheard = between.isNotEmpty() && run <= MAX_MISHEARD_RUN && between.size <= run + 1 &&
                between.all { h -> runKeys.any { Aligner.similarity(h, it) > SOUNDS_LIKE } }

            if (lostBetweenUtterances || misheard) forgiven += p
        }
        return forgiven
    }

    /** A stretch of expected words that wasn't matched, and what was heard in its place. */
    private class Gap(val expected: List<Int>, val heardThere: List<String>)

    /** Every unmatched stretch before the last word recited. */
    private fun gaps(): List<Gap> {
        val a = align(heard) ?: return emptyList()
        val spokenAt = a.matches.associate { (spoken, target) -> target to spoken }
        val matched = a.matchedTargets.sorted()
        val result = mutableListOf<Gap>()
        var prev = -1
        for (m in matched) {
            if (m > prev + 1) {
                val from = if (prev >= 0) spokenAt.getValue(prev) + 1 else 0
                result += Gap((prev + 1 until m).toList(), heard.subList(from, spokenAt.getValue(m)).map { Tokenizer.key(it.text) })
            }
            prev = m
        }
        return result
    }

    /** Pairs the words heard in a gap with the words expected there, each with the one it sounds most like. */
    private fun learnGap(gap: Gap, profile: VoiceProfile) {
        val expected = gap.expected.map { parts[it].second }
        if (gap.heardThere.isEmpty()) {
            expected.forEach { profile.recordMissed(it) } // the recognizer didn't pick these up at all
            return
        }
        if (gap.heardThere.size == expected.size) {
            expected.zip(gap.heardThere).forEach { (e, h) -> profile.recordHeardAs(e, h) }
        } else {
            for (h in gap.heardThere) profile.recordHeardAs(expected.maxBy { Aligner.similarity(h, it) }, h)
        }
    }

    /**
     * After a recitation that came out right, learns how the recognizer mishears this voice, from
     * the words it took as sound-alikes. Returns true if anything was learned.
     */
    fun learnFromSuccess(): Boolean {
        val p = profile ?: return false
        var learned = false
        for (g in gaps()) {
            if (g.heardThere.isEmpty() || g.expected.size > MAX_MISHEARD_RUN) continue
            if (g.heardThere.all { h -> g.expected.any { Aligner.similarity(h, parts[it].second) > SOUNDS_LIKE } }) {
                learnGap(g, p)
                learned = true
            }
        }
        return learned
    }

    /**
     * The reciter says they said [verse] right even though it sounded wrong: learns how the
     * recognizer heard each expected word there, so it's recognized next time.
     */
    fun learnVerseWasRight(verse: Int) {
        val p = profile ?: return
        val r = verseRange(verse)
        for (g in gaps()) {
            if (g.expected.any { parts[it].first in r }) learnGap(g, p)
        }
    }

    /**
     * The first verse (0-based) with a mistake in it among those the reciter has finished: moved a
     * couple of words into the next verse, or reached the end. [includeCurrent] also counts the verse
     * they're in the middle of. Null if none.
     */
    fun firstWrongVerse(grade: Grade, includeCurrent: Boolean = false): Int? {
        if (!grade.heardAnything) return null
        for (v in verseStarts.indices) {
            val start = verseRange(v).first
            val end = verseRange(v).last
            val finished = grade.reachedEnd || grade.lastWord >= end + 2 || (includeCurrent && grade.lastWord >= start)
            if (!finished) break
            if (grade.mistakes.any { it.first <= end && it.last >= start }) return v
        }
        return null
    }

    /** The passage's words [first]..[last], as written but without quotation marks. */
    fun text(first: Int, last: Int): String =
        words.subList(first.coerceAtLeast(0), (last + 1).coerceAtMost(words.size))
            .joinToString(" ") { it.display.trim(*QUOTES) }
            .replace(Regex("\\s*[—–]+\\s*"), ", ")

    /** The next few words after what's been recited so far. */
    fun hint(count: Int = 4): String {
        val next = position()
        val word = if (next < parts.size) parts[next].first else return "You've reached the end."
        return text(word, word + count - 1).trimEnd(',', ';', ':', '.')
    }

    /** What to say back to the reciter: each mistake with the passage around it. */
    fun feedback(grade: Grade): String {
        if (!grade.heardAnything) return "I didn't hear any of the passage."
        if (grade.perfect) return "Perfect."

        val sb = StringBuilder()
        for (m in grade.mistakes.take(MAX_SPOKEN_MISTAKES)) {
            val said = "“${text(m.first, m.last).trimEnd(',', ';', ':', '.')}”"
            sb.append(
                when (m.kind) {
                    Mistake.Kind.Missed -> "You left out $said. "
                    Mistake.Kind.OutOfOrder -> "You said $said out of order. "
                }
            )
            sb.append("It goes: ").append(text(m.first - CONTEXT_BEFORE, m.last + CONTEXT_AFTER).trimEnd(',', ';', ':')).append(". ")
        }
        val more = grade.mistakes.size - MAX_SPOKEN_MISTAKES
        if (more > 0) sb.append("And $more more place${if (more == 1) "" else "s"}. ")
        if (!grade.reachedEnd) {
            sb.append("You stopped after “${text(grade.lastWord - 2, grade.lastWord).trimEnd(',', ';', ':', '.')}”. ")
        }
        return sb.toString().replace("..", ".").trim()
    }

    private companion object {
        const val OUT_OF_ORDER_SIMILARITY = 0.8

        /** Words of a verse's opening said again that count as starting it over. */
        const val RESTART_WORDS = 3
        const val RESTART_SIMILARITY = 0.8

        /** The longest stretch of words assumed said when nothing was heard between two utterances. */
        const val MAX_LOST_RUN = 3

        /** The longest stretch of words assumed said when something like them was heard. */
        const val MAX_MISHEARD_RUN = 2

        /** Heard words this similar to the expected one are taken as that word, misheard. */
        const val SOUNDS_LIKE = 0.5

        /** Passages up to this many words must be recited to the very last word. */
        const val SHORT_PASSAGE = 6
        const val MAX_SPOKEN_MISTAKES = 3
        const val CONTEXT_BEFORE = 3
        const val CONTEXT_AFTER = 2
        val QUOTES = charArrayOf('“', '”', '"', '‘', '’')
    }
}
