package com.nathanblazek.scripturememory.practice

/** Spoken commands for the coach, each said as "verse" plus a word ("verse review"). */
enum class VoiceCommand(val word: String, val description: String) {
    Yes("yes", "try again"),
    Review("review", "hear how it went"),
    Speak("speak", "hear the passage"),
    Hint("hint", "hear the next few words"),
    All("all", "recite the whole collection from the top"),
    Next("next", "skip to the next passage"),
    Help("help", "hear these commands"),
    Stop("stop", "end the session"),
}

object VoiceCommands {
    /** What the recognizer may hear for "verse". */
    private val verseWords = setOf("verse", "verses", "versus", "vers", "virus")

    /** What the recognizer may hear for each command word. */
    private val commandWords: Map<String, VoiceCommand> = buildMap {
        for (c in VoiceCommand.entries) put(c.word, c)
        listOf("yeah", "yep", "yup", "ye").forEach { put(it, VoiceCommand.Yes) }
        listOf("reviews", "preview").forEach { put(it, VoiceCommand.Review) }
        listOf("speech", "speaks").forEach { put(it, VoiceCommand.Speak) }
        listOf("hints").forEach { put(it, VoiceCommand.Hint) }
        listOf("ol", "awl").forEach { put(it, VoiceCommand.All) }
        listOf("stops").forEach { put(it, VoiceCommand.Stop) }
    }

    /** Phrases to steer the recognizer toward. */
    val phrases: List<String> = VoiceCommand.entries.map { "verse ${it.word}" }

    /** A command found in an utterance, and the words said before it. */
    data class Found(val command: VoiceCommand, val before: List<HeardWord>)

    /** Looks for a command in any of the recognizer's guesses, best guess first. */
    fun find(candidates: List<List<HeardWord>>): Found? {
        for (words in candidates) {
            val keys = words.map { Tokenizer.key(it.text) }
            for (i in 0 until keys.size - 1) {
                if (keys[i] !in verseWords) continue
                val command = commandWords[keys[i + 1]] ?: continue
                return Found(command, words.subList(0, i))
            }
        }
        return null
    }

    /** The command list, read aloud. */
    fun helpText(): String =
        "Say verse, then a command. " + VoiceCommand.entries.joinToString(". ") { "Verse ${it.word} to ${it.description}" } + "."
}
