package com.nathanblazek.scripturememory.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenizerTest {
    @Test
    fun versesWordsAndParagraphs() {
        val tokens = Tokenizer.tokenize("[16] “For God so loved the world,\n\n[17] For God did not")
        assertEquals(TokenKind.VerseNumber, tokens[0].kind)
        assertEquals("16", tokens[0].display)
        assertEquals("“For", tokens[1].display)
        assertEquals("“F__", tokens[1].hint)
        assertEquals(listOf("for"), tokens[1].matchParts)
        assertEquals("w____,", tokens[6].hint)
        assertEquals(TokenKind.ParagraphBreak, tokens[7].kind)
        assertEquals("17", tokens[8].display)
    }

    @Test
    fun splitsEmDashesAndHyphens() {
        val tokens = Tokenizer.tokenize("said—and well-pleased")
        assertEquals(listOf("said—", "and", "well-pleased"), tokens.map { it.display })
        assertEquals(listOf("well", "pleased"), tokens[2].speechParts)
    }

    @Test
    fun numbersAreNotSpeakable() {
        val t = Tokenizer.tokenize("144,000 don’t")
        assertTrue(t[0].hasLetters)
        assertFalse(t[0].isSpeakable)
        assertEquals(listOf("don't"), t[1].speechParts)
        assertEquals(listOf("dont"), t[1].matchParts)
    }
}
