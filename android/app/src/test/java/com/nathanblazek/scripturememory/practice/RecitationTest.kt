package com.nathanblazek.scripturememory.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecitationTest {
    private val text = "[8] For by grace you have been saved through faith. And this is not your own doing; it is the gift of God,"

    private fun said(vararg utterances: String) = Recitation(text, null).apply {
        for (u in utterances) add(listOf(u.split(" ").map { HeardWord(it, 0.9f) }))
    }

    @Test
    fun perfectRecitation() {
        val r = said("for by grace you have been saved through faith", "and this is not your own doing it is the gift of god")
        val g = r.grade()
        assertTrue(g.perfect)
        assertEquals("Perfect.", r.feedback(g))
    }

    @Test
    fun leftOutWords() {
        val r = said("for by you have been saved through faith and this is not your own doing it is the gift of god")
        val g = r.grade()
        assertTrue(g.reachedEnd)
        assertEquals(listOf(Mistake(Mistake.Kind.Missed, 2, 2)), g.mistakes)
        assertEquals("You left out “grace”. It goes: For by grace you have.", r.feedback(g))
    }

    @Test
    fun wordsOutOfOrder() {
        val r = said("for by grace you have been saved through faith and this is not your own doing it is the gift of god")
        assertTrue(r.grade().perfect)

        val swapped = said("for by grace you have been through saved faith and this is not your own doing it is the gift of god")
        val g = swapped.grade()
        assertTrue(g.reachedEnd)
        assertEquals(1, g.mistakes.size)
        assertEquals(Mistake.Kind.OutOfOrder, g.mistakes[0].kind)
        assertEquals("You said “saved” out of order. It goes: you have been saved through faith.", swapped.feedback(g))
    }

    @Test
    fun stoppingPartway() {
        val r = said("for by grace you have been saved")
        val g = r.grade()
        assertFalse(g.reachedEnd)
        assertTrue(g.mistakes.isEmpty())
        assertTrue(r.feedback(g).contains("You stopped after “have been saved”"))
        assertEquals("through faith. And this", r.hint())
    }

    @Test
    fun wordsLostBetweenUtterancesAreForgiven() {
        // "grace you" fell in the gap while the recognizer restarted.
        val r = said("for by", "have been saved through faith and this is not your own doing it is the gift of god")
        assertTrue(r.grade().perfect)
    }

    @Test
    fun misheardWordsAreForgiven() {
        val r = said("for by race you have been saved through fate and this is not your own doing it is the gift of god")
        assertTrue(r.grade().perfect)
    }

    @Test
    fun wrongWordsAreNotForgiven() {
        val r = said("for by love you have been saved through faith and this is not your own doing it is the gift of god")
        assertEquals(listOf(Mistake(Mistake.Kind.Missed, 2, 2)), r.grade().mistakes)
    }

    @Test
    fun nothingHeard() {
        val r = said("hello there")
        assertFalse(r.grade().heardAnything)
    }

    @Test
    fun commandsAreFound() {
        fun words(s: String) = listOf(s.split(" ").map { HeardWord(it, 0.9f) })
        assertEquals(VoiceCommand.Review, VoiceCommands.find(words("verse review"))?.command)
        val f = VoiceCommands.find(words("the gift of god versus yes"))!!
        assertEquals(VoiceCommand.Yes, f.command)
        assertEquals(4, f.before.size)
        assertEquals(VoiceCommand.All, VoiceCommands.find(words("Verse all."))?.command)
        assertNull(VoiceCommands.find(words("for by grace you have been saved")))
    }
}
