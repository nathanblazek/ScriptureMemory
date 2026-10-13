package com.nathanblazek.scripturememory.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionsTest {
    private val plain = """
        [1] but Jesus went to the Mount of Olives. [2] Early in the morning he came again.

        [11] She said, “No one, Lord.” And Jesus said, “Neither do I condemn you.”

        [12] Again Jesus spoke to them, saying, “I am the light of the world.”
        [13] So the Pharisees said to him, “You are bearing witness about yourself.”
    """.trimIndent()

    private val withHeadings = """
        The Woman Caught in Adultery

        [1] but Jesus went to the Mount of Olives. [2] Early in the morning he came again.

        [11] She said, “No one, Lord.” And Jesus said, “Neither do I condemn you.”

        I Am the Light of the World

        [12] Again Jesus spoke to them, saying, “I am the light of the world.”
        [13] So the Pharisees said to him, “You are bearing witness about yourself.”
    """.trimIndent()

    @Test
    fun splitsAtHeadings() {
        val sections = Sections.split("John 8:1–13", plain, withHeadings)
        assertEquals(listOf("The Woman Caught in Adultery", "I Am the Light of the World"), sections.map { it.heading })
        assertEquals(listOf("John 8:1–11", "John 8:12–13"), sections.map { it.reference })
        assertTrue(sections[0].text.endsWith("condemn you.”"))
        assertTrue(sections[1].text.startsWith("[12]"))
    }

    @Test
    fun followsChapters() {
        val p = "[24] Last verse.\n\n[1] First verse of the next chapter. [2] Second."
        val h = "Ending\n\n[24] Last verse.\n\nBeginning\n\n[1] First verse of the next chapter. [2] Second."
        assertEquals(listOf("Romans 7:24", "Romans 8:1–2"), Sections.split("Romans 7:24–8:2", p, h).map { it.reference })
    }

    @Test
    fun oneSectionIsNotSplit() {
        assertTrue(Sections.split("John 8:12–13", plain.substringAfter("\n\n").substringAfter("\n\n"), "I Am the Light of the World\n\n" + plain.substringAfter("\n\n").substringAfter("\n\n")).isEmpty())
        assertTrue(Sections.split("John 3:16; Romans 8:28", plain, withHeadings).isEmpty())
    }
}
