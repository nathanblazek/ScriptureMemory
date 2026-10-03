package com.nathanblazek.scripturememory.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nathanblazek.scripturememory.model.AppData
import com.nathanblazek.scripturememory.model.Dates

/**
 * The one copy of the app's data in this process, shared by the phone UI and the Android Auto
 * screens so neither overwrites the other's changes. Main thread only.
 */
class AppDataHolder private constructor(context: Context) {
    private val store = DataStore(context.applicationContext)

    var data: AppData by mutableStateOf(store.load())
        private set

    /** Replaces the data and saves it. Throws if saving fails. */
    fun update(newData: AppData) {
        data = newData
        save()
    }

    /** Saves the current data (e.g. after the voice profile changed in place). */
    fun save() = store.save(data)

    fun recordPractice(collectionId: String, passageId: String) = update(
        data.copy(collections = data.collections.map { c ->
            if (c.id != collectionId) c
            else c.copy(passages = c.passages.map {
                if (it.id == passageId) it.copy(practiceCount = it.practiceCount + 1, lastPracticed = Dates.now()) else it
            })
        })
    )

    companion object {
        @Volatile private var instance: AppDataHolder? = null

        fun get(context: Context): AppDataHolder =
            instance ?: synchronized(this) { instance ?: AppDataHolder(context).also { instance = it } }
    }
}
