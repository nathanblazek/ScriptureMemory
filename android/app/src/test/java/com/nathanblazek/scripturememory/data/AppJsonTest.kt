package com.nathanblazek.scripturememory.data

import com.nathanblazek.scripturememory.model.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppJsonTest {
    /** A data.json as the Windows app writes it. */
    private val windowsJson = """
        {
          "EsvApiKeyProtected": "AQAAANCMnd8BFdERjHoAwE",
          "Collections": [
            {
              "Id": "6f1c9e0a-1b2c-4d5e-8f90-123456789abc",
              "Name": "Romans Road",
              "Passages": [
                {
                  "Id": "0b8e7d6c-5a4b-4c3d-9e2f-abcdef012345",
                  "Reference": "Romans 3:23",
                  "Query": "Romans 3:23",
                  "Text": "[23] for all have sinned and fall short of the glory of God,",
                  "AddedOn": "2026-09-20T21:15:30.1234567-05:00",
                  "LastPracticed": "2026-09-24T07:02:11.5000000-05:00",
                  "PracticeCount": 3,
                  "Mastered": true
                }
              ]
            }
          ],
          "VoiceProfile": { "Words": { "sinned": { "Heard": 2, "ConfidenceSum": 1.1, "Missed": 1 } } }
        }
    """.trimIndent()

    @Test
    fun readsWindowsData() {
        val data = AppJson.decode("\uFEFF" + windowsJson)
        val p = data.collections.single().passages.single()
        assertEquals("Romans 3:23", p.reference)
        assertEquals(3, p.practiceCount)
        assertTrue(p.mastered)
        assertEquals("Completed 3 times · last on Sep 24, 2026", p.status.replace(Regex("Sep 2[45]"), "Sep 24"))
        assertEquals(2.0, data.voiceProfile.words["sinned"]!!.heard, 0.0)
    }

    @Test
    fun roundTripsInWindowsShape() {
        val text = AppJson.encode(AppJson.decode(windowsJson))
        assertTrue(text.contains("\"Collections\""))
        assertTrue(text.contains("\"PracticeCount\": 3"))
        assertEquals(AppJson.decode(windowsJson).collections, AppJson.decode(text).collections)
        assertTrue(!text.contains("EsvApiKeyProtected"))
    }

    @Test
    fun timestampsHaveSevenFractionDigits() {
        val now = Dates.now()
        assertTrue(now, Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{7}[+-]\d\d:\d\d""").matches(now))
        assertNotNull(Dates.parse(now))
    }
}
