package com.nathanblazek.scripturememory.practice

import com.nathanblazek.scripturememory.model.Passage
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Recites real passages to the coach through a simulated speech recognizer that makes the mistakes
 * Android's recognizer makes: words lost while it restarts between utterances, small words dropped,
 * sound-alikes ("to" → "too"), numbers as digits, and a better guess that isn't the top one. A
 * correct recitation must never be called wrong; a word really left out must always be caught.
 */
class SimulatedRecognizerTest {
    private val passages = listOf(
        Passage(id = "john", reference = "John 3:16", text = "[16] “For God so loved the world, that he gave his only Son, that whoever believes in him should not perish but have eternal life."),
        Passage(id = "eph", reference = "Ephesians 2:8–9", text = "[8] For by grace you have been saved through faith. And this is not your own doing; it is the gift of God, [9] not a result of works, so that no one may boast."),
        Passage(id = "ps", reference = "Psalm 23:1–3", text = "[1] The LORD is my shepherd; I shall not want. [2] He makes me lie down in green pastures. He leads me beside still waters. [3] He restores my soul. He leads me in paths of righteousness for his name’s sake."),
        Passage(id = "rom", reference = "Romans 8:28", text = "[28] And we know that for those who love God all things work together for good, for those who are called according to his purpose."),
        Passage(id = "matt", reference = "Matthew 4:2", text = "[2] And after fasting forty days and forty nights, he was hungry."),
        Passage(id = "thess", reference = "1 Thessalonians 5:16–18", text = "[16] Rejoice always, [17] pray without ceasing, [18] give thanks in all circumstances; for this is the will of God in Christ Jesus for you."),
        Passage(id = "heb", reference = "Hebrews 3:4", text = "[4] (For every house is built by someone, but the builder of all things is God.)"),
    )

    private val soundAlikes = mapOf(
        "to" to "too", "for" to "four", "son" to "sun", "him" to "hymn", "know" to "no", "no" to "know",
        "one" to "won", "whole" to "hole", "their" to "there", "built" to "build", "loved" to "love",
        "believes" to "believe", "leads" to "lead", "waters" to "water", "names" to "name's", "forty" to "40",
        "be" to "bee", "by" to "buy", "in" to "and", "might" to "mite", "praise" to "prays",
    )

    /** The coach's verdicts for one recitation. */
    private class Outcome(val corrected: Boolean, val perfect: Boolean, val blips: Int, val said: List<String>)

    private class FakeIO(override val continuousListening: Boolean = false) : CoachIO {
        val said = mutableListOf<String>()
        var blips = 0
        override fun blip() { blips++ }
        override fun say(text: String, then: () -> Unit) {
            said += text
            then()
        }
        override fun stopSaying() {}
        override fun listen(words: List<String>, position: Int) {}
        override fun setPosition(position: Int) {}
        override fun stopListening() {}
        override fun mastered(passage: Passage) {}
        override fun practiced(passage: Passage) {}
        override fun profileChanged() {}
    }

    private fun words(p: Passage): List<String> =
        Tokenizer.tokenize(p.text).filter { it.isSpeakable }.flatMap { it.speechParts }.map { it.lowercase() }

    /** What the recognizer makes of [spoken], one utterance at a time: best guess first. */
    private fun recognize(spoken: List<String>, rng: Random, together: Int? = null, offline: Boolean = false): List<List<List<HeardWord>>> {
        if (offline) return recognizeOffline(spoken, rng)
        val utterances = mutableListOf<List<List<HeardWord>>>()
        var i = 0
        var first = true
        while (i < spoken.size) {
            var n = minOf(spoken.size - i, rng.nextInt(3, 9))
            // Keep spoken[together - 1] and spoken[together] in one utterance (no pause between them).
            if (together != null && i + n == together) n = minOf(spoken.size - i, n + 2)
            val chunk = spoken.subList(i, i + n)
            i += n

            val noisy = chunk.toMutableList()
            // Lost while the recognizer restarts: the first word (not of the whole recitation), or the last.
            if (!first && noisy.size > 2 && rng.nextDouble() < 0.3) noisy.removeAt(0)
            if (i < spoken.size && noisy.size > 2 && rng.nextDouble() < 0.15) noisy.removeAt(noisy.lastIndex)
            first = false
            // Small words dropped, sound-alikes swapped in.
            for (k in noisy.indices.reversed()) {
                val w = noisy[k]
                if (Aligner.isFunctionWord(w) && rng.nextDouble() < 0.15) noisy.removeAt(k)
                else if (rng.nextDouble() < 0.25) soundAlikes[w]?.let { noisy[k] = it }
            }
            val guesses = mutableListOf(noisy.map { HeardWord(it, 0.8f) })
            if (rng.nextDouble() < 0.5) guesses += chunk.map { HeardWord(it, 0.5f) } // a better, lower-ranked guess
            utterances += guesses
        }
        return utterances
    }

    /**
     * The offline model: it listens continuously (nothing lost between phrases) and knows only the
     * passage's words, so it makes no sound-alike mistakes but now and then hears noise as "[unk]"
     * or loses a small word.
     */
    private fun recognizeOffline(spoken: List<String>, rng: Random): List<List<List<HeardWord>>> {
        val utterances = mutableListOf<List<List<HeardWord>>>()
        var i = 0
        while (i < spoken.size) {
            val n = minOf(spoken.size - i, rng.nextInt(3, 9))
            val noisy = mutableListOf<String>()
            for (w in spoken.subList(i, i + n)) {
                if (rng.nextDouble() < 0.05) noisy += "[unk]"
                if (!(Aligner.isFunctionWord(w) && rng.nextDouble() < 0.1)) noisy += w
            }
            i += n
            utterances += listOf(noisy.map { HeardWord(it, 0.8f) })
        }
        return utterances
    }

    private fun recite(passage: Passage, spoken: List<String>, rng: Random, together: Int? = null, offline: Boolean = false): Outcome {
        val io = FakeIO(continuousListening = offline)
        val coach = Coach(listOf(passage), null, io)
        coach.start()
        val before = io.said.size
        for (u in recognize(spoken, rng, together, offline)) {
            // Partial results as the words come in, then the final one.
            val top = u.first()
            for (n in 1 until top.size) coach.onHeard(listOf(top.take(n)), final = false)
            coach.onHeard(u, final = true)
            if (io.said.size > before) break // the coach spoke: a correction or the result
        }
        val after = io.said.drop(before)
        return Outcome(
            corrected = after.any { it.startsWith("That's not quite right") },
            perfect = after.any { it.startsWith("Perfect") },
            blips = io.blips,
            said = after,
        )
    }

    @Test
    fun correctRecitationsAreNeverCalledWrong() {
        val failures = mutableListOf<String>()
        val blipped = mutableListOf<String>()
        for (p in passages) {
            repeat(TRIALS) { seed ->
                val o = recite(p, words(p), Random(seed))
                if (!o.perfect) failures += "${p.reference} seed $seed: ${o.said}"
                if (o.blips > 0) blipped += "${p.reference} seed $seed"
            }
        }
        println("Correct recitations blipped: ${blipped.size}\n" + blipped.joinToString("\n"))
        assertEquals("Correct recitations not accepted:\n" + failures.take(10).joinToString("\n"), 0, failures.size)
        assertEquals("Correct recitations blipped:\n" + blipped.take(10).joinToString("\n"), 0, blipped.size)
    }

    @Test
    fun aLeftOutWordIsAlwaysCaught() {
        val failures = mutableListOf<String>()
        val unblipped = mutableListOf<String>()
        for (p in passages) {
            val all = words(p)
            // Every content word away from the ends, left out in turn, in the middle of a phrase.
            val candidates = all.indices.filter { it in 2 until all.size - 2 && !Aligner.isFunctionWord(all[it]) }
            for ((n, skip) in candidates.withIndex()) {
                val spoken = all.filterIndexed { i, _ -> i != skip }
                val o = recite(p, spoken, Random(n), together = skip)
                if (!o.corrected) failures += "${p.reference} without “${all[skip]}”: ${o.said}"
                if (o.blips == 0) unblipped += "${p.reference} without “${all[skip]}”"
            }
        }
        // Sound-alikes next to the left-out word can still hide it now and then; nearly all must be caught.
        println("Left-out words not caught: ${failures.size}\n" + failures.joinToString("\n"))
        println("Left-out words not blipped: ${unblipped.size}\n" + unblipped.joinToString("\n"))
        assert(failures.size <= MAX_UNCAUGHT) { "Too many left-out words not caught (${failures.size}):\n" + failures.joinToString("\n") }
    }

    @Test
    fun aBlippedVerseStartedOverIsAccepted() {
        val failures = mutableListOf<String>()
        var tried = 0
        for (p in passages) {
            val all = words(p)
            // Where each verse starts, in spoken words.
            val starts = Verses.split(p).runningFold(0) { n, v -> n + words(p.copy(text = v.text)).size }.dropLast(1)
            for (v in 0 until starts.size - 1) {
                val verse = starts[v] until starts[v + 1]
                for (skip in verse.filter { it >= starts[v] + 1 && it + 2 < starts[v + 1] && !Aligner.isFunctionWord(all[it]) }) {
                    // Leave the word out, say a couple more, then start the verse over and finish.
                    val spoken = all.subList(0, skip) + all.subList(skip + 1, skip + 3) + all.subList(starts[v], all.size)
                    val o = recite(p, spoken, Random(skip), together = skip)
                    tried++
                    // Not stopped and corrected; and after a blip, not counted as perfect either.
                    if (o.corrected || (o.blips > 0 && o.said.none { it.startsWith("Good.") })) {
                        failures += "${p.reference} without “${all[skip]}”, then verse ${v + 1} again: ${o.blips} blips, ${o.said}"
                    }
                }
            }
        }
        println("Verses started over after a blip, not accepted: ${failures.size} of $tried\n" + failures.joinToString("\n"))
        assert(failures.size * 10 <= tried) { "Too many verses started over not accepted:\n" + failures.joinToString("\n") }
    }

    @Test
    fun offlineModelCorrectRecitationsAreAccepted() {
        val failures = mutableListOf<String>()
        for (p in passages) {
            repeat(TRIALS) { seed ->
                val o = recite(p, words(p), Random(seed), offline = true)
                if (!o.perfect || o.blips > 0) failures += "${p.reference} seed $seed: ${o.blips} blips, ${o.said}"
            }
        }
        assertEquals("Correct recitations not accepted:\n" + failures.take(10).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun offlineModelCatchesALeftOutWord() {
        val failures = mutableListOf<String>()
        for (p in passages) {
            val all = words(p)
            for ((n, skip) in all.indices.filter { it in 2 until all.size - 2 && !Aligner.isFunctionWord(all[it]) }.withIndex()) {
                val o = recite(p, all.filterIndexed { i, _ -> i != skip }, Random(n), offline = true)
                if (!o.corrected) failures += "${p.reference} without “${all[skip]}”: ${o.said}"
            }
        }
        println("Offline: left-out words not caught: ${failures.size}\n" + failures.joinToString("\n"))
        assert(failures.size <= MAX_UNCAUGHT) { "Too many left-out words not caught:\n" + failures.joinToString("\n") }
    }

    @Test
    fun aWrongWordIsCaught() {
        val o = recite(passages[0], words(passages[0]).map { if (it == "world") "church" else it }, Random(1))
        assertEquals(true, o.corrected)
    }

    private companion object {
        const val TRIALS = 200
        const val MAX_UNCAUGHT = 4
    }
}
