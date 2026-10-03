package com.nathanblazek.scripturememory.practice

import com.nathanblazek.scripturememory.model.VoiceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeControllerTest {
    private class FakeEngine : SpeechEngine {
        var started = false
        var stopped = false
        var expected = -1
        override fun start() { started = true }
        override fun setExpectedPosition(position: Int) { expected = position }
        override fun shutdown() { stopped = true }
    }

    private var engine: FakeEngine? = null
    private var engineWords: List<String> = emptyList()

    private fun controller(text: String, profile: VoiceProfile? = null, strict: Boolean = false) =
        PracticeController(text, profile, strictMisses = strict) { words, _, _, _ ->
            engineWords = words
            FakeEngine().also { engine = it }
        }

    private fun heard(text: String, confidence: Float = 0.9f) =
        listOf(text.split(" ").map { HeardWord(it, confidence) })

    @Test
    fun reciteWholePassage() {
        val c = controller("[16] For God so loved the world, that he gave his only Son")
        var completed = 0
        c.onPracticeCompleted = { completed++ }
        c.setMode(PracticeMode.FirstLetter)
        c.startSpeech()
        assertTrue(engine!!.started)
        assertEquals(12, engineWords.size)

        c.onHeard(heard("for god so"), final = false)
        assertEquals(RevealKind.Spoken, c.state.reveal[2])
        assertEquals(RevealKind.Hidden, c.state.reveal[3])

        c.onHeard(heard("for god so loved the world"), final = true)
        assertEquals(6, engine!!.expected)

        c.onHeard(heard("that he gave his only son"), final = true)
        assertEquals(1, completed)
        assertTrue(engine!!.stopped)
        assertFalse(c.state.listening)
        assertNotNull(c.state.banner)
        assertTrue(c.state.reveal.all { it == RevealKind.Spoken })
    }

    @Test
    fun skippedWordsAreMissed() {
        val c = controller("For God greatly loved the world")
        c.setMode(PracticeMode.Blur)
        c.startSpeech()
        c.onHeard(heard("for god loved world"), final = true)
        // "greatly" was skipped, so it's missed; dropping "the" is forgiven.
        assertEquals(RevealKind.Missed, c.state.reveal[2])
        assertEquals(RevealKind.Spoken, c.state.reveal[4])
        assertEquals(RevealKind.Spoken, c.state.reveal[5])
    }

    @Test
    fun peekAndReset() {
        val c = controller("For God so loved")
        c.setMode(PracticeMode.FirstLetter)
        c.wordTapped(1)
        assertEquals(RevealKind.Peeked, c.state.reveal[1])
        c.reset()
        assertEquals(RevealKind.Hidden, c.state.reveal[1])
    }

    @Test
    fun calibrationRecordsWords() {
        val profile = VoiceProfile()
        val c = controller("For God so loved the world", profile)
        var saved = 0
        c.onProfileChanged = { saved++ }
        c.startCalibration()
        assertTrue(c.state.calibrating)
        c.onHeard(heard("for god so loved the world", 0.95f), final = true)
        assertFalse(c.state.calibrating)
        assertEquals(1, saved)
        assertTrue(profile.words.containsKey("loved"))
        assertEquals("Calibration saved", c.state.banner?.title)
    }

    @Test
    fun strictModeReportsSkippedAndWrongWords() {
        val c = controller("For God greatly loved the world", strict = true)
        val missedWords = mutableListOf<String>()
        c.onWordMissed = { missedWords += it }
        c.setMode(PracticeMode.FirstLetter)
        c.startSpeech()

        c.onHeard(heard("for god loved"), final = true)
        assertEquals(listOf("greatly"), missedWords)

        // A wrong word: nothing fits, so the expected word ("the") is shown as missed and reciting carries on.
        c.onHeard(heard("banana"), final = true)
        assertEquals(listOf("greatly", "the"), missedWords)
        assertEquals(RevealKind.Missed, c.state.reveal[4])

        c.onHeard(heard("world"), final = true)
        assertEquals(RevealKind.Spoken, c.state.reveal[5])
        assertFalse(c.state.listening)
    }

    @Test
    fun relaxedModeIgnoresNoise() {
        val c = controller("For God so loved")
        c.setMode(PracticeMode.FirstLetter)
        c.startSpeech()
        c.onHeard(heard("banana"), final = true)
        assertTrue(c.state.reveal.all { it == RevealKind.Hidden })
    }
}
