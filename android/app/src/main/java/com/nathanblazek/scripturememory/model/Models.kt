package com.nathanblazek.scripturememory.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// JSON property names match the Windows app's data.json (System.Text.Json's PascalCase), so the
// same file can be exported from one app and imported into the other.

@Serializable
data class Passage(
    @SerialName("Id") val id: String = UUID.randomUUID().toString(),
    /** Canonical reference returned by the ESV API, e.g. "John 3:16–18". */
    @SerialName("Reference") val reference: String = "",
    /** The query the user typed/selected. */
    @SerialName("Query") val query: String = "",
    /** Passage text with inline verse numbers like "[16]". */
    @SerialName("Text") val text: String = "",
    @SerialName("AddedOn") val addedOn: String = Dates.now(),
    @SerialName("LastPracticed") val lastPracticed: String? = null,
    @SerialName("PracticeCount") val practiceCount: Int = 0,
    @SerialName("Mastered") val mastered: Boolean = false,
    /** The ESV section heading, when the passage is one section of a longer one. */
    @SerialName("Title") val title: String? = null,
) {
    val preview: String
        get() {
            val t = text.replace(Regex("""\[\d+]\s*"""), "").replace(Regex("""\s+"""), " ").trim()
            return if (t.length > 180) t.take(180).trimEnd() + "…" else t
        }

    val status: String
        get() {
            val last = lastPracticed?.let(Dates::parse) ?: return "Not practiced yet"
            val times = if (practiceCount == 1) "time" else "times"
            return "Completed $practiceCount $times · last on ${last.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))}"
        }
}

@Serializable
data class VerseCollection(
    @SerialName("Id") val id: String = UUID.randomUUID().toString(),
    @SerialName("Name") val name: String = "",
    @SerialName("Passages") val passages: List<Passage> = emptyList(),
)

@Serializable
data class AppData(
    @SerialName("Collections") val collections: List<VerseCollection> = emptyList(),
    @SerialName("VoiceProfile") val voiceProfile: VoiceProfile = VoiceProfile(),
)

/** How the recognizer has heard one word when the user read it aloud during calibration. */
@Serializable
class WordStat(
    /** Times the word was recognized (decayed so recent readings count more). */
    @SerialName("Heard") var heard: Double = 0.0,
    /** Sum of recognizer confidence over those recognitions. */
    @SerialName("ConfidenceSum") var confidenceSum: Double = 0.0,
    /** Times the word was read but the recognizer didn't pick it up. */
    @SerialName("Missed") var missed: Double = 0.0,
)

/**
 * Per-word calibration learned from the user reading passages aloud. Words the recognizer struggles
 * with in this user's voice get extra leeway during practice; words it hears clearly stay strict.
 */
@Serializable
class VoiceProfile(
    @SerialName("Words") val words: MutableMap<String, WordStat> = mutableMapOf(),
    /**
     * Words the recognizer has turned into other words in this user's voice ("faith" heard as
     * "fate"), learned from recitations that turned out right: expected word → heard word → times.
     */
    @SerialName("HeardAs") val heardAs: MutableMap<String, MutableMap<String, Double>> = mutableMapOf(),
) {
    fun recordHeardAs(expected: String, heard: String) {
        if (expected == heard || expected.isEmpty() || heard.isEmpty()) return
        val m = heardAs.getOrPut(expected) { mutableMapOf() }
        m[heard] = (m[heard] ?: 0.0) + 1.0
    }

    /** The recognizer has heard [expected] as [heard] before, so the two count as the same word. */
    fun isHeardAs(expected: String, heard: String): Boolean = (heardAs[expected]?.get(heard) ?: 0.0) >= 1.0

    fun recordHeard(key: String, confidence: Double) {
        val s = get(key)
        s.heard++
        s.confidenceSum += confidence.coerceIn(0.0, 1.0)
        decay(s)
    }

    fun recordMissed(key: String) {
        val s = get(key)
        s.missed++
        decay(s)
    }

    /** 0..1: how hard this word is for the recognizer to hear in this user's voice. */
    fun difficulty(key: String): Double {
        val s = words[key] ?: return 0.0
        val samples = s.heard + s.missed
        if (samples <= 0) return 0.0
        val avgConfidence = if (s.heard > 0) s.confidenceSum / s.heard else 0.0
        val missRate = s.missed / samples
        val difficulty = ((0.75 - avgConfidence) / 0.75).coerceIn(0.0, 1.0) * 0.6 + missRate * 0.4
        return difficulty * minOf(1.0, samples / 2) // trust it more after a couple of readings
    }

    /** How much to lower the match threshold for this word. */
    fun thresholdBonus(key: String): Double = difficulty(key) * MAX_THRESHOLD_BONUS

    /** The recognizer usually fails to pick this word up at all, so don't penalize it when it's not heard. */
    fun isHardToHear(key: String): Boolean {
        val s = words[key] ?: return false
        val total = s.heard + s.missed
        return total >= 2 && s.missed / total >= 0.5
    }

    private fun get(key: String): WordStat = words.getOrPut(key) { WordStat() }

    private fun decay(s: WordStat) {
        val total = s.heard + s.missed
        if (total <= MAX_SAMPLES) return
        val f = MAX_SAMPLES / total
        s.heard *= f
        s.confidenceSum *= f
        s.missed *= f
    }

    private companion object {
        const val MAX_SAMPLES = 12.0 // older readings fade out after this many
        const val MAX_THRESHOLD_BONUS = 0.25
    }
}

/** Timestamps are stored as ISO 8601 strings with an offset, the way .NET writes DateTime.Now. */
object Dates {
    // Seven fractional digits: .NET's DateTime precision, and the most System.Text.Json reads back.
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSxxx")

    fun now(): String = ZonedDateTime.now().format(format)

    fun parse(s: String): LocalDateTime? =
        runCatching { OffsetDateTime.parse(s).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime() }
            .recoverCatching { LocalDateTime.parse(s) }
            .getOrNull()
}
