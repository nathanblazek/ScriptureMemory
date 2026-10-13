package com.nathanblazek.scripturememory.practice

enum class TokenKind { Word, VerseNumber, ParagraphBreak }

class WordToken(
    val kind: TokenKind,
    /** Text exactly as shown, including punctuation (e.g. "“For", "world,"). */
    val display: String = "",
    /** First-letter hint, e.g. "Jesus" → "J____", "world," → "w____,". */
    val hint: String = "",
    /** True if the token has letters/digits and so can be hidden. */
    val hasLetters: Boolean = false,
    /** Words for the speech recognizer ("well-pleased" → ["well", "pleased"]). */
    val speechParts: List<String> = emptyList(),
    /** Comparison keys for [speechParts] (lowercase, letters only). */
    val matchParts: List<String> = emptyList(),
) {
    val isSpeakable: Boolean get() = kind == TokenKind.Word && matchParts.isNotEmpty()
}

object Tokenizer {
    private val leadingVerseNumber = Regex("""^\[(\d+)](.*)$""")
    private val paragraphSplit = Regex("""\n\s*\n""")
    private val whitespace = Regex("""\s+""")
    private val dashPieces = Regex("[^—]+—*|—+")

    fun tokenize(text: String): List<WordToken> {
        val tokens = mutableListOf<WordToken>()
        for (paragraph in text.replace("\r", "").split(paragraphSplit)) {
            if (paragraph.isBlank()) continue
            if (tokens.isNotEmpty()) tokens += WordToken(TokenKind.ParagraphBreak)

            for (raw in paragraph.trim().split(whitespace)) {
                var rest = raw
                val m = leadingVerseNumber.find(rest)
                if (m != null) {
                    tokens += WordToken(TokenKind.VerseNumber, display = m.groupValues[1])
                    rest = m.groupValues[2]
                }
                if (rest.isEmpty()) continue

                // ESV joins em-dash clauses without spaces ("said—and"); split them into separate words.
                for (piece in dashPieces.findAll(rest)) tokens += makeWord(piece.value)
            }
        }
        return tokens
    }

    fun key(s: String): String = buildString(s.length) {
        for (ch in s) if (ch.isLetterOrDigit()) append(ch.lowercaseChar())
    }

    private fun makeWord(display: String): WordToken {
        val parts = mutableListOf<String>()
        for (part in display.split('-', '‐', '–')) {
            val sb = StringBuilder()
            for (ch in part) {
                if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
                else if (ch == '\'' || ch == '’') sb.append('\'')
            }
            val core = sb.toString().trim('\'')
            if (core.isNotEmpty()) parts += core
        }

        // Digits (e.g. "144,000") don't come back from the recognizer as written; those words are tap-to-reveal only.
        val speakable = parts.isNotEmpty() && parts.all { p -> p.all { it.isLetter() || it == '\'' } }

        return WordToken(
            kind = TokenKind.Word,
            display = display,
            hint = makeHint(display),
            hasLetters = parts.isNotEmpty(),
            speechParts = if (speakable) parts else emptyList(),
            matchParts = if (speakable) parts.map(::key) else emptyList(),
        )
    }

    private fun makeHint(display: String): String = buildString(display.length) {
        var first = true
        for (ch in display) {
            if (ch.isLetterOrDigit()) {
                append(if (first) ch else '_')
                first = false
            } else {
                append(ch)
            }
        }
    }
}
