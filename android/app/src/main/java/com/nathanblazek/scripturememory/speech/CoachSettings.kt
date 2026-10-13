package com.nathanblazek.scripturememory.speech

import android.content.Context

/** The voice coach's sound and prompting settings, kept with the app's other preferences. */
class CoachSettings(context: Context) {
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)

    /** Seconds of quiet before the next words are prompted; 0 for never. */
    var promptAfterSeconds: Int
        get() = prefs.getInt(PROMPT_AFTER, 4)
        set(v) = prefs.edit().putInt(PROMPT_AFTER, v).apply()

    /** A blip for a missed word. */
    var blips: Boolean
        get() = prefs.getBoolean(BLIPS, true)
        set(v) = prefs.edit().putBoolean(BLIPS, v).apply()

    /** A chime for a verse right. */
    var chimes: Boolean
        get() = prefs.getBoolean(CHIMES, true)
        set(v) = prefs.edit().putBoolean(CHIMES, v).apply()

    /** Blip and chime volume, 0..100. */
    var soundVolume: Int
        get() = prefs.getInt(SOUND_VOLUME, 35)
        set(v) = prefs.edit().putInt(SOUND_VOLUME, v.coerceIn(0, 100)).apply()

    /** Hear the reciter with the offline model (once downloaded) instead of Google's recognizer. */
    var offline: Boolean
        get() = prefs.getBoolean(OFFLINE, false)
        set(v) = prefs.edit().putBoolean(OFFLINE, v).apply()

    private companion object {
        const val OFFLINE = "coachOfflineRecognizer"
        const val PROMPT_AFTER = "coachPromptAfter"
        const val BLIPS = "coachBlips"
        const val CHIMES = "coachChimes"
        const val SOUND_VOLUME = "coachSoundVolume"
    }
}
