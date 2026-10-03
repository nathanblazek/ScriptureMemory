package com.nathanblazek.scripturememory.practice

import com.nathanblazek.scripturememory.model.VoiceProfile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Forgiving alignment of what the recognizer heard against the upcoming words of the passage.
 * Tolerates dropped words, extra words, and near-misses ("love" vs "loved", "to" vs "too").
 */
object Aligner {
    private const val MATCH_THRESHOLD = 0.62

    // When the recognizer wasn't sure what it heard, lean toward the word we expected: the match
    // threshold drops by up to this much as confidence falls to zero (never below MIN_MATCH_THRESHOLD).
    private const val LOW_CONFIDENCE_LEEWAY = 0.3
    private const val MIN_MATCH_THRESHOLD = 0.35
    private const val EXTRA_SPOKEN_COST = 0.5
    // Higher than the gain from one match, so a lone match never justifies skipping two real words.
    private const val SKIP_WORD_COST = 1.2
    private const val SKIP_FUNCTION_WORD_COST = 0.25

    /** Short words the recognizer often drops; skipping them is cheap and not counted as a miss. */
    private val functionWords = hashSetOf(
        "a", "an", "the", "and", "of", "to", "in", "on", "for", "is", "it", "that", "he", "his", "i", "you",
        "be", "as", "by", "but", "or", "so", "not", "we", "my", "me", "was", "are", "at", "with", "from",
        "who", "this", "will", "shall", "have", "has", "had", "him", "her", "them", "they", "their", "your",
        "our", "us", "all", "if", "o", "into", "unto", "which", "do", "no", "nor", "then", "than", "there",
    )

    fun isFunctionWord(key: String): Boolean = key in functionWords

    /** Words it's fine for the recognizer to drop: small function words, and words calibration found it rarely hears. */
    fun isEasySkip(key: String, profile: VoiceProfile?): Boolean =
        isFunctionWord(key) || (profile?.isHardToHear(key) ?: false)

    /** [matches] holds (spoken index, target index) for each matched word, in order. */
    class Alignment(val score: Double, val matches: List<Pair<Int, Int>>) {
        val matchedTargets: List<Int> get() = matches.map { it.second }
        val lastMatchedTarget: Int get() = matches.last().second
    }

    /**
     * Aligns [spoken] against [targets]. The first [freeSkips] targets are look-back words that may
     * be skipped at no cost (the reciter may restart a few words back). [confidence] (0..1 per spoken
     * word, optional) loosens matching for words the recognizer was unsure about, and [profile]
     * loosens it for words calibration found hard to hear in this voice. Returns null if nothing matched.
     */
    fun align(
        spoken: List<String>,
        targets: List<String>,
        freeSkips: Int,
        confidence: List<Float>? = null,
        profile: VoiceProfile? = null,
    ): Alignment? {
        val m = spoken.size
        val n = targets.size
        if (m == 0 || n == 0) return null

        val spokenLeeway = DoubleArray(m) { i ->
            val conf = confidence?.let { it[i].coerceIn(0f, 1f).toDouble() } ?: 1.0
            LOW_CONFIDENCE_LEEWAY * (1 - conf)
        }
        val targetLeeway = DoubleArray(n) { j -> profile?.thresholdBonus(targets[j]) ?: 0.0 }
        val skipCost = DoubleArray(n) { j ->
            when {
                j < freeSkips -> 0.0
                isEasySkip(targets[j], profile) -> SKIP_FUNCTION_WORD_COST
                else -> SKIP_WORD_COST
            }
        }

        val dp = Array(m + 1) { DoubleArray(n + 1) { Double.NEGATIVE_INFINITY } }
        val back = Array(m + 1) { ByteArray(n + 1) } // 1 = extra spoken, 2 = skipped target, 3 = match
        dp[0][0] = 0.0

        fun relax(i: Int, j: Int, value: Double, move: Byte) {
            if (value > dp[i][j]) {
                dp[i][j] = value
                back[i][j] = move
            }
        }

        for (i in 0..m) {
            for (j in 0..n) {
                val cur = dp[i][j]
                if (cur == Double.NEGATIVE_INFINITY) continue

                if (j < n) relax(i, j + 1, cur - skipCost[j], 2)
                if (i < m) relax(i + 1, j, cur - EXTRA_SPOKEN_COST, 1)
                if (i < m && j < n) {
                    val sim = similarity(spoken[i], targets[j])
                    val threshold = max(MIN_MATCH_THRESHOLD, MATCH_THRESHOLD - spokenLeeway[i] - targetLeeway[j])
                    if (sim >= threshold) relax(i + 1, j + 1, cur + 1 + sim, 3)
                }
            }
        }

        var bestJ = 0
        for (j in 1..n) if (dp[m][j] > dp[m][bestJ] + 1e-9) bestJ = j

        val matched = mutableListOf<Pair<Int, Int>>()
        var bi = m
        var bj = bestJ
        while (bi > 0 || bj > 0) {
            when (back[bi][bj].toInt()) {
                3 -> { matched += (bi - 1) to (bj - 1); bi--; bj-- }
                2 -> bj--
                else -> bi--
            }
        }
        if (matched.isEmpty()) return null
        matched.reverse()
        return Alignment(dp[m][bestJ], matched)
    }

    /** 0..1 similarity between two normalized words. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val longer = max(a.length, b.length)
        var score = 1.0 - levenshtein(a, b).toDouble() / longer

        // Same stem: "believe" / "believes", "love" / "loved"
        val (s, l) = if (a.length <= b.length) a to b else b to a
        if (s.length >= 3 && l.startsWith(s)) score = max(score, 0.8)

        // Sounds alike: "to" / "too" / "two", "their" / "there"
        if (a[0] == b[0] && soundex(a) == soundex(b) && abs(a.length - b.length) <= 2) score = max(score, 0.75)

        return score
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun soundex(word: String): String {
        fun code(c: Char): Char = when (c) {
            'b', 'f', 'p', 'v' -> '1'
            'c', 'g', 'j', 'k', 'q', 's', 'x', 'z' -> '2'
            'd', 't' -> '3'
            'l' -> '4'
            'm', 'n' -> '5'
            'r' -> '6'
            else -> '0'
        }

        val sb = StringBuilder()
        sb.append(word[0])
        var last = code(word[0])
        var i = 1
        while (i < word.length && sb.length < 4) {
            val c = code(word[i])
            if (c != '0' && c != last) sb.append(c)
            if (word[i] != 'h' && word[i] != 'w') last = c
            i++
        }
        return sb.toString().padEnd(4, '0')
    }
}
