package com.nathanblazek.scripturememory.car

import com.nathanblazek.scripturememory.model.Passage
import org.junit.Assert.assertEquals
import org.junit.Test

class VersesTest {
    @Test
    fun splitsAtVerseNumbers() {
        val p = Passage(reference = "John 3:16–17", text = "[16] “For God so loved the world,\n\n[17] For God did not send his Son—")
        val verses = Verses.split(p)
        assertEquals(listOf("John 3:16", "John 3:17"), verses.map { it.label })
        assertEquals("“For God so loved the world,", verses[0].text)
        assertEquals("For God did not send his Son, ", verses[1].spoken)
    }

    @Test
    fun labelPrefixes() {
        assertEquals("Romans 8:", Verses.labelPrefix("Romans 8:28–39"))
        assertEquals("Jude ", Verses.labelPrefix("Jude 3–4"))
        assertEquals("1 John 4:", Verses.labelPrefix("1 John 4:7"))
    }
}
