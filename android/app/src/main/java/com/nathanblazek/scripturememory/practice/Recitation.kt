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
    private val words: List<WordToken> = Tokenizer.tokenize(passageText).filter { it.kind == TokenKind.Word }

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

    private val heard = mutableListOf<HeardWord>()

    val isEmpty: Boolean get() = heard.isEmpty()

    fun clear() = heard.clear()

    /** Adds one utterance, choosing whichever of the recognizer's guesses fits the passage best. */
    fun add(candidates: List<List<HeardWord>>) {
        val usable = candidates.map { c -> c.filter { Tokenizer.key(it.text).isNotEmpty() } }.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return
        val best = usable.maxBy { align(heard + it)?.score ?: Double.NEGATIVE_INFINITY }
        heard += best
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
        addRun(Mistake.Kind.Missed, unmatched.filter { it < lastPart && it !in outOfOrder })
        addRun(Mistake.Kind.OutOfOrder, outOfOrder.toList())
        mistakes.sortBy { it.first }

        val reached = lastPart >= lastNeededPart || (lastNeededPart in outOfOrder && lastPart >= lastNeededPart - 3)
        return Grade(mistakes, lastWord = parts[lastPart].first, reachedEnd = reached)
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
        const val MAX_SPOKEN_MISTAKES = 3
        const val CONTEXT_BEFORE = 3
        const val CONTEXT_AFTER = 2
        val QUOTES = charArrayOf('“', '”', '"', '‘', '’')
    }
}
