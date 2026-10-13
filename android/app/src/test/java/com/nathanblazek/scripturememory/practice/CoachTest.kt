package com.nathanblazek.scripturememory.practice

import com.nathanblazek.scripturememory.model.Passage
import com.nathanblazek.scripturememory.model.VoiceProfile
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
        var blips = 0
        var chimes = 0
        override fun blip() { blips++ }
        override fun chime() { chimes++ }
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
        override fun profileChanged() {}
    }

    private fun Coach.hear(text: String) = onHeard(listOf(text.split(" ").map { HeardWord(it, 0.9f) }), final = true)
    private fun Coach.hearSoFar(text: String) = onHeard(listOf(text.split(" ").map { HeardWord(it, 0.9f) }), final = false)

    private val thess = Passage(
        id = "t",
        reference = "1 Thessalonians 5:16–18",
        text = "[16] Rejoice always, [17] pray without ceasing, [18] give thanks in all circumstances;",
    )

    @Test
    fun aMissedWordBlipsAndTheVerseCanBeStartedOver() {
        val io = FakeIO()
        val coach = Coach(listOf(thess), null, io)
        coach.start()
        val said = io.said.size

        // "ceasing" is left out; the blip comes once a couple more words are heard.
        coach.hearSoFar("rejoice always pray without")
        assertEquals(0, io.blips)
        coach.hearSoFar("rejoice always pray without give thanks")
        assertEquals(1, io.blips)
        coach.hearSoFar("rejoice always pray without give thanks in")
        assertEquals(1, io.blips) // once per mistake

        // Hearing it, the reciter starts verse 17 over and finishes without stopping.
        coach.hear("rejoice always pray without give thanks pray without ceasing give thanks in all circumstances")
        assertEquals(1, io.blips)
        assertEquals("Good. Now say all of 1 Thessalonians 5:16–18 from the start.", io.said.last())
        assertEquals(said + 1, io.said.size) // no spoken correction
        assertEquals(0, coach.state.streak)
    }

    @Test
    fun goingOnAfterABlipIsCorrected() {
        val io = FakeIO()
        val coach = Coach(listOf(thess), null, io)
        coach.start()
        coach.hearSoFar("rejoice always pray without give thanks")
        assertEquals(1, io.blips)
        coach.hear("rejoice always pray without give thanks in all")
        assertEquals("That's not quite right. 1 Thessalonians 5:17. pray without ceasing. Try again.", io.said.last())
    }

    @Test
    fun eachVerseRightChimes() {
        val io = FakeIO()
        val coach = Coach(listOf(thess), null, io)
        coach.start()
        coach.hear("rejoice always pray")
        assertEquals(1, io.chimes)
        coach.hearSoFar("without ceasing give")
        assertEquals(2, io.chimes)
        coach.hear("without ceasing give thanks in all circumstances")
        assertEquals(2, io.chimes) // the last verse gets spoken praise instead
        assertEquals(0, io.blips)
        assertEquals("Perfect. Say it once more to lock it in.", io.said.last())
    }

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
        assertEquals("Next, 1 Thessalonians 5:16. Listen first. Rejoice always. Now you say it.", io.said.last())
        assertEquals(1, coach.state.index)
    }

    @Test
    fun reviewMidwaySaysHowItsGoing() {
        val io = FakeIO()
        val coach = Coach(listOf(second), null, io)
        coach.start()
        coach.hear("rejoice")
        coach.hear("verse review")
        assertEquals("So far so good. Keep going.", io.said.last())
        assertEquals(CoachPhase.Reciting, coach.state.phase)
        coach.hear("always")
        assertEquals(1, coach.state.streak)
    }

    @Test
    fun aWrongVerseIsCorrectedRightAway() {
        val io = FakeIO()
        val passage = Passage(
            id = "c",
            reference = "1 Thessalonians 5:16–18",
            text = "[16] Rejoice always, [17] pray without ceasing, [18] give thanks in all circumstances;",
        )
        val coach = Coach(listOf(passage), null, io)
        coach.start()

        // Verse 16 is fine; "ceasing" is left out of verse 17, which shows once verse 18 is under way.
        coach.hear("rejoice always pray without")
        assertEquals(CoachPhase.Reciting, coach.state.phase)
        coach.hear("pray without give thanks")
        assertEquals("That's not quite right. 1 Thessalonians 5:17. pray without ceasing. Try again.", io.said.last())
        assertEquals(CoachPhase.Reciting, coach.state.phase)
        assertTrue(io.listening)

        // Going again from verse 17 to the end, then the whole passage.
        coach.hear("pray without ceasing give thanks in all circumstances")
        assertEquals("Good. Now say all of 1 Thessalonians 5:16–18 from the start.", io.said.last())
        coach.hear("rejoice always pray without ceasing give thanks in all circumstances")
        assertEquals("Perfect. Say it once more to lock it in.", io.said.last())
    }

    @Test
    fun otherGuessesAreCheckedBeforeCallingAVerseWrong() {
        val io = FakeIO()
        val passage = Passage(id = "d", reference = "1 Thessalonians 5:16–17", text = "[16] Rejoice always, [17] pray without ceasing,")
        val coach = Coach(listOf(passage), null, io)
        coach.start()
        val said = io.said.size
        // The top guess drops "always"; the recognizer's second guess has it.
        coach.onHeard(
            listOf(
                "rejoice pray without".split(" ").map { HeardWord(it, 0.9f) },
                "rejoice always pray without".split(" ").map { HeardWord(it, 0.5f) },
            ),
            final = true,
        )
        assertEquals(said, io.said.size) // no correction
        coach.hear("ceasing")
        assertEquals("Perfect. Say it once more to lock it in.", io.said.last())
    }

    @Test
    fun saidItRightLearnsTheVoice() {
        val io = FakeIO()
        val profile = VoiceProfile()
        val passage = Passage(id = "e", reference = "1 Thessalonians 5:16–17", text = "[16] Rejoice always, [17] pray without ceasing,")
        val coach = Coach(listOf(passage), profile, io)
        coach.start()
        coach.hear("rejoice hello pray without")
        assertTrue(io.said.last(), io.said.last().startsWith("That's not quite right. 1 Thessalonians 5:16."))

        coach.hear("verse correct")
        assertTrue(profile.isHeardAs("always", "hello"))
        assertEquals("Got it, I'll remember how you say that. Carry on from 1 Thessalonians 5:17.", io.said.last())

        // Next time "hello" is taken as "always".
        coach.hear("pray without ceasing")
        coach.hear("rejoice hello pray without ceasing")
        assertEquals("Perfect. Say it once more to lock it in.", io.said.last())
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
