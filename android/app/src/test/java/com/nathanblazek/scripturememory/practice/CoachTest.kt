package com.nathanblazek.scripturememory.practice

import com.nathanblazek.scripturememory.model.Passage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTest {
    private val first = Passage(id = "a", reference = "John 11:35", text = "[35] Jesus wept.")
    private val second = Passage(id = "b", reference = "1 Thessalonians 5:16", text = "[16] Rejoice always,")

    /** Speaks instantly and records everything. */
    private class FakeIO : CoachIO {
        val said = mutableListOf<String>()
        var listening = false
        val mastered = mutableListOf<String>()
        override fun say(text: String, then: () -> Unit) {
            listening = false
            said += text
            then()
        }
        override fun stopSaying() {}
        override fun listen(words: List<String>, position: Int) { listening = true }
        override fun setPosition(position: Int) {}
        override fun stopListening() { listening = false }
        override fun mastered(passage: Passage) { mastered += passage.id }
        override fun practiced(passage: Passage) {}
    }

    private fun Coach.hear(text: String) = onHeard(listOf(text.split(" ").map { HeardWord(it, 0.9f) }), final = true)

    @Test
    fun learnsEachPassageUntilMastered() {
        val io = FakeIO()
        val coach = Coach(listOf(first, second), null, io)
        coach.start()
        assertEquals("Let's learn John 11:35. Listen first. Jesus wept. Now you say it.", io.said.last())
        assertEquals(CoachPhase.Reciting, coach.state.phase)
        assertTrue(io.listening)

        coach.hear("jesus wept")
        assertEquals("Perfect. Say it once more to lock it in.", io.said.last())
        coach.hear("jesus wept")
        assertEquals(listOf("a"), io.mastered)
        assertTrue(io.said.any { it == "Perfect. You've mastered John 11:35." })
        assertEquals("Next, 1 Thessalonians 5:16. Listen first. Rejoice always, Now you say it.", io.said.last())
        assertEquals(1, coach.state.index)
    }

    @Test
    fun mistakesAskToTryAgain() {
        val io = FakeIO()
        val coach = Coach(listOf(second), null, io)
        coach.start()
        coach.hear("rejoice")
        coach.hear("verse review")
        assertEquals("You stopped after “Rejoice”. Say verse yes to try again.", io.said.last())
        assertEquals(CoachPhase.Waiting, coach.state.phase)

        // Recitation is ignored until "verse yes".
        coach.hear("rejoice always")
        assertEquals(CoachPhase.Waiting, coach.state.phase)

        coach.hear("verse yes")
        assertEquals("Go ahead.", io.said.last())
        assertEquals(CoachPhase.Reciting, coach.state.phase)
        coach.hear("rejoice always")
        assertEquals(1, coach.state.streak)
    }

    @Test
    fun speakAllAndStop() {
        val io = FakeIO()
        var stopped = false
        val coach = Coach(listOf(first, second), null, io)
        coach.onStopped = { stopped = true }
        coach.start()

        coach.hear("verse speak")
        assertEquals("John 11:35. Jesus wept.", io.said.last())
        assertEquals(CoachPhase.Reciting, coach.state.phase)

        coach.hear("jesus verse all")
        assertEquals(CoachMode.All, coach.state.mode)
        assertEquals("From the top. John 11:35.", io.said.last())
        coach.hear("jesus wept")
        assertEquals("Perfect. Next, 1 Thessalonians 5:16.", io.said.last())
        coach.hear("rejoice always")
        assertEquals("That's the whole collection. Say verse all to go again.", io.said.last())
        assertEquals(CoachPhase.Finished, coach.state.phase)
        assertTrue(io.mastered.isEmpty())

        coach.hear("verse stop")
        assertTrue(stopped)
    }

    @Test
    fun allMasteredStartsFromTheTop() {
        val io = FakeIO()
        val coach = Coach(listOf(first.copy(mastered = true)), null, io)
        coach.start()
        assertEquals(CoachMode.All, coach.state.mode)
    }
}
