package com.nathanblazek.scripturememory.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignerTest {
    private fun keys(s: String) = s.split(" ")

    @Test
    fun exactMatch() {
        val a = Aligner.align(keys("for god so loved"), keys("for god so loved the world"), 0)!!
        assertEquals(listOf(0 to 0, 1 to 1, 2 to 2, 3 to 3), a.matches)
    }

    @Test
    fun forgivesDroppedFunctionWordsAndNearMisses() {
        val a = Aligner.align(keys("god so love world"), keys("for god so loved the world"), 0)!!
        assertEquals(listOf(1, 2, 3, 5), a.matchedTargets)
    }

    @Test
    fun soundsAlike() {
        assertTrue(Aligner.similarity("to", "too") >= 0.62)
        assertTrue(Aligner.similarity("there", "their") >= 0.62)
    }

    @Test
    fun nothingMatches() {
        assertNull(Aligner.align(keys("banana"), keys("for god so loved"), 0))
    }
}
