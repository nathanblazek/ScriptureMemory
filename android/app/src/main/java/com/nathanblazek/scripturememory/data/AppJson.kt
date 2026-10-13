package com.nathanblazek.scripturememory.data

import com.nathanblazek.scripturememory.model.AppData
import kotlinx.serialization.json.Json

/** Reads and writes data.json in the same shape as the Windows app. */
object AppJson {
    private val json = Json {
        ignoreUnknownKeys = true // e.g. the Windows app's EsvApiKeyProtected, which only that PC can decrypt
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }

    fun decode(text: String): AppData = json.decodeFromString(AppData.serializer(), text.removePrefix("\uFEFF"))

    fun encode(data: AppData): String = json.encodeToString(AppData.serializer(), data)
}
