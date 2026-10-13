package com.nathanblazek.scripturememory.practice

/** Puts what the recognizer heard into the same shape as the passage's words. */
object HeardText {
    private val ones = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val ordinals = mapOf(
        "1st" to "first", "2nd" to "second", "3rd" to "third", "4th" to "fourth", "5th" to "fifth",
        "6th" to "sixth", "7th" to "seventh", "8th" to "eighth", "9th" to "ninth", "10th" to "tenth", "12th" to "twelfth",
    )

    /**
     * Splits hyphenated words ("well-pleased" → "well pleased", as the passage's words are split) and
     * spells out numbers the recognizer writes as digits ("40" → "forty", "3rd" → "third").
     */
    fun normalize(words: List<HeardWord>): List<HeardWord> = words.flatMap { w ->
        w.text.split('-', '‐', '–', '—').filter { it.isNotBlank() }.flatMap { piece ->
            spellOut(piece)?.map { HeardWord(it, w.confidence) } ?: listOf(HeardWord(piece, w.confidence))
        }
    }

    private fun spellOut(text: String): List<String>? {
        val core = text.trim { !it.isLetterOrDigit() }.lowercase()
        ordinals[core]?.let { return listOf(it) }
        val n = core.replace(",", "").toIntOrNull() ?: return null
        if (core.any { it.isLetter() }) return null
        return words(n)
    }

    private fun words(n: Int): List<String>? = when {
        n < 0 -> null
        n < 20 -> listOf(ones[n])
        n < 100 -> listOf(tens[n / 10]) + if (n % 10 != 0) listOf(ones[n % 10]) else emptyList()
        n < 1000 -> listOf(ones[n / 100], "hundred") + if (n % 100 != 0) words(n % 100)!! else emptyList()
        n < 1_000_000 -> words(n / 1000)!! + "thousand" + if (n % 1000 != 0) words(n % 1000)!! else emptyList()
        else -> null
    }
}
