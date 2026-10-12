package com.nathanblazek.scripturememory.data

/** One titled section of a passage, as the ESV divides it ("The Woman Caught in Adultery"). */
data class Section(val heading: String, val reference: String, val text: String)

/** Splits a passage at the ESV's section headings. */
object Sections {
    private val verseNumber = Regex("""\[(\d+)]""")
    private val singleChapterBooks = setOf("Obadiah", "Philemon", "2 John", "3 John", "Jude")

    /**
     * Compares the passage [text] fetched without headings to the same passage fetched [withHeadings]:
     * the extra lines are the headings. Returns one [Section] per heading, or an empty list when the
     * passage has fewer than two sections or can't be split reliably.
     */
    fun split(canonical: String, text: String, withHeadings: String): List<Section> {
        if (';' in canonical) return emptyList() // several separate passages
        val lines = text.replace("\r", "").split('\n')

        // Headings, keyed by the index of the line of text that follows them.
        val headings = sortedMapOf<Int, String>()
        var j = nextNonBlank(lines, 0)
        for (raw in withHeadings.replace("\r", "").split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (j < lines.size && lines[j].trim() == line) {
                j = nextNonBlank(lines, j + 1)
            } else {
                if (verseNumber.containsMatchIn(line)) return emptyList() // not just a heading: give up
                headings[j] = headings[j]?.let { "$it, $line" } ?: line
            }
        }
        if (j < lines.size) return emptyList()

        // Text before the first heading belongs to the first section.
        val starts = headings.keys.toMutableList()
        if (starts.isEmpty()) return emptyList()
        if (starts[0] != nextNonBlank(lines, 0)) starts[0] = 0
        val bounds = starts.mapIndexed { i, start -> start to (starts.getOrNull(i + 1) ?: lines.size) }

        val numbering = Numbering(canonical)
        val sections = bounds.mapNotNull { (start, end) ->
            val body = lines.subList(start, end).joinToString("\n").trim()
            if (body.isEmpty()) return@mapNotNull null
            val heading = headings[start] ?: headings.values.first()
            Section(heading, numbering.reference(body) ?: return emptyList(), body)
        }
        return if (sections.size >= 2) sections else emptyList()
    }

    private fun nextNonBlank(lines: List<String>, from: Int): Int {
        var i = from
        while (i < lines.size && lines[i].isBlank()) i++
        return i
    }

    /** Works out each section's reference from its verse numbers, following chapter changes. */
    private class Numbering(canonical: String) {
        private val book: String
        private val singleChapter: Boolean
        private var chapter: Int
        private var lastVerse = 0

        init {
            val m = Regex("""^(.+?)\s(\d+)""").find(canonical)
            book = m?.groupValues?.get(1) ?: canonical
            singleChapter = book in singleChapterBooks
            chapter = if (singleChapter) 1 else m?.groupValues?.get(2)?.toIntOrNull() ?: 1
        }

        fun reference(body: String): String? {
            val verses = verseNumber.findAll(body).map { it.groupValues[1].toInt() }.toList()
            if (verses.isEmpty()) return null
            var startChapter = 0
            var startVerse = 0
            verses.forEachIndexed { i, v ->
                if (v < lastVerse) chapter++ // verse numbers start over in the next chapter
                lastVerse = v
                if (i == 0) {
                    startChapter = chapter
                    startVerse = v
                }
            }
            val endVerse = verses.last()
            return when {
                singleChapter -> if (startVerse == endVerse) "$book $startVerse" else "$book $startVerse–$endVerse"
                startChapter != chapter -> "$book $startChapter:$startVerse–$chapter:$endVerse"
                startVerse == endVerse -> "$book $chapter:$startVerse"
                else -> "$book $chapter:$startVerse–$endVerse"
            }
        }
    }
}
