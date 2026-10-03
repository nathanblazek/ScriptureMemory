package com.nathanblazek.scripturememory.car

import com.nathanblazek.scripturememory.model.Passage
import com.nathanblazek.scripturememory.practice.TokenKind
import com.nathanblazek.scripturememory.practice.Tokenizer

/** One verse of a passage: [label] like "John 3:16", [text] to practice, and [spoken] to read aloud. */
data class Verse(val label: String, val text: String, val spoken: String)

object Verses {
    /** Splits a passage into verses at its inline verse numbers. */
    fun split(passage: Passage): List<Verse> {
        val prefix = labelPrefix(passage.reference)
        val verses = mutableListOf<Verse>()
        var number: String? = null
        val words = mutableListOf<String>()

        fun flush() {
            if (words.isEmpty()) return
            val text = words.joinToString(" ")
            val label = number?.let { "$prefix$it" } ?: passage.reference
            verses += Verse(label, text, text.replace(Regex("[—–]+"), ", "))
            words.clear()
        }

        for (token in Tokenizer.tokenize(passage.text)) {
            when (token.kind) {
                TokenKind.VerseNumber -> {
                    flush()
                    number = token.display
                }
                TokenKind.Word -> words += token.display
                TokenKind.ParagraphBreak -> {}
            }
        }
        flush()
        return verses
    }

    /** "John 3:16–18" → "John 3:", "Jude 3-5" → "Jude ", so a verse number can be appended. */
    fun labelPrefix(reference: String): String {
        Regex("""^(.+?\s\d+):""").find(reference)?.let { return it.groupValues[1] + ":" }
        Regex("""^(.+?\s)\d+""").find(reference)?.let { return it.groupValues[1] }
        return "$reference "
    }
}
