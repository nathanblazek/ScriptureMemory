package com.nathanblazek.scripturememory.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.nathanblazek.scripturememory.data.ApiKeyStore
import com.nathanblazek.scripturememory.data.AppJson
import com.nathanblazek.scripturememory.data.AppDataHolder
import com.nathanblazek.scripturememory.data.EsvClient
import com.nathanblazek.scripturememory.data.EsvException
import com.nathanblazek.scripturememory.data.Sections
import com.nathanblazek.scripturememory.model.AppData
import com.nathanblazek.scripturememory.model.Passage
import com.nathanblazek.scripturememory.model.VerseCollection

sealed interface Screen {
    data object Collections : Screen
    data class Collection(val collectionId: String) : Screen
    data class Practice(val collectionId: String, val passageId: String) : Screen
    data class Interact(val collectionId: String) : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val holder = AppDataHolder.get(app)
    private val keys = ApiKeyStore(app)

    val data: AppData get() = holder.data

    var screen by mutableStateOf<Screen>(Screen.Collections)
        private set

    /** A one-off message for the snackbar. */
    var message by mutableStateOf<String?>(null)

    var busy by mutableStateOf(false)
        private set

    /** The add-verses flow found no API key; the UI shows the key dialog. */
    var askForApiKey by mutableStateOf(false)

    val voiceProfile get() = data.voiceProfile

    fun collection(id: String) = data.collections.firstOrNull { it.id == id }

    // ---------- Navigation ----------

    fun open(screen: Screen) {
        this.screen = screen
    }

    /** Returns false when there's nowhere to go back to. */
    fun back(): Boolean {
        screen = when (val s = screen) {
            Screen.Collections -> return false
            is Screen.Collection -> Screen.Collections
            is Screen.Practice -> Screen.Collection(s.collectionId)
            is Screen.Interact -> Screen.Collection(s.collectionId)
        }
        return true
    }

    // ---------- Saving ----------

    private fun update(newData: AppData) = saving { holder.update(newData) }

    fun save() = saving { holder.save() }

    private fun saving(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            message = "Couldn't save your data: ${e.message}"
        }
    }

    private fun updateCollection(id: String, change: (VerseCollection) -> VerseCollection) =
        update(data.copy(collections = data.collections.map { if (it.id == id) change(it) else it }))

    private fun updatePassage(collectionId: String, passageId: String, change: (Passage) -> Passage) =
        updateCollection(collectionId) { c -> c.copy(passages = c.passages.map { if (it.id == passageId) change(it) else it }) }

    // ---------- Collections ----------

    fun addCollection(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val collection = VerseCollection(name = trimmed)
        update(data.copy(collections = data.collections + collection))
        screen = Screen.Collection(collection.id)
    }

    fun renameCollection(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) updateCollection(id) { it.copy(name = trimmed) }
    }

    fun deleteCollection(id: String) {
        update(data.copy(collections = data.collections.filterNot { it.id == id }))
        screen = Screen.Collections
    }

    // ---------- Passages ----------

    val apiKey: String get() = keys.get()

    private val prefs = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)

    /** Add a passage that spans several ESV sections as one passage per section. */
    var splitSections by mutableStateOf(prefs.getBoolean(PREF_SPLIT_SECTIONS, true))
        private set

    fun updateSplitSections(split: Boolean) {
        splitSections = split
        prefs.edit().putBoolean(PREF_SPLIT_SECTIONS, split).apply()
    }

    fun setApiKey(key: String) = keys.set(key)

    /** Fetches [query] from the ESV API and adds it. Returns true if it was added. */
    suspend fun addPassage(collectionId: String, query: String): Boolean {
        val key = keys.get()
        if (key.isEmpty()) {
            askForApiKey = true
            return false
        }

        busy = true
        try {
            val (canonical, text) = EsvClient.getPassage(key, query)
            val sections = if (splitSections) {
                runCatching { Sections.split(canonical, text, EsvClient.getPassage(key, query, headings = true).second) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val current = collection(collectionId) ?: return false
            val existing = current.passages.map { it.reference }.toSet()

            if (sections.isEmpty()) {
                if (canonical in existing) {
                    message = "$canonical is already in this collection."
                    return false
                }
                updateCollection(collectionId) { it.copy(passages = it.passages + Passage(reference = canonical, query = query, text = text)) }
                message = "Added $canonical."
                return true
            }

            val added = sections.filter { it.reference !in existing }
                .map { Passage(reference = it.reference, query = query, text = it.text, title = it.heading) }
            if (added.isEmpty()) {
                message = "Every section of $canonical is already in this collection."
                return false
            }
            updateCollection(collectionId) { it.copy(passages = it.passages + added) }
            message = "Added $canonical in ${added.size} sections."
            return true
        } catch (e: EsvException) {
            message = e.message
            return false
        } catch (e: Exception) {
            message = "Something went wrong: ${e.message}"
            return false
        } finally {
            busy = false
        }
    }

    fun setMastered(collectionId: String, passageId: String, mastered: Boolean) =
        updatePassage(collectionId, passageId) { it.copy(mastered = mastered) }

    fun deletePassage(collectionId: String, passageId: String) =
        updateCollection(collectionId) { c -> c.copy(passages = c.passages.filterNot { it.id == passageId }) }

    fun recordPractice(collectionId: String, passageId: String) = saving { holder.recordPractice(collectionId, passageId) }

    // ---------- Import / export ----------

    fun exportData(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(AppJson.encode(data).toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("couldn't open the file")
            message = "Exported ${data.collections.size} collection(s)."
        } catch (e: Exception) {
            message = "Export failed: ${e.message}"
        }
    }

    /**
     * Merges a data.json (from this app or the Windows app) into the current data: new collections
     * are added, passages missing from existing collections are added, and voice calibration for
     * words not yet calibrated here is kept.
     */
    fun importData(uri: Uri) {
        val incoming = try {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("couldn't open the file")
            AppJson.decode(text)
        } catch (e: Exception) {
            message = "That file couldn't be read as Scripture Memory data. (${e.message})"
            return
        }

        var newCollections = 0
        var newPassages = 0
        val merged = data.collections.toMutableList()
        for (c in incoming.collections) {
            val i = merged.indexOfFirst { it.id == c.id }
            if (i < 0) {
                merged += c
                newCollections++
                newPassages += c.passages.size
            } else {
                val existing = merged[i]
                val known = existing.passages.map { it.id }.toSet() + existing.passages.map { it.reference }.toSet()
                val added = c.passages.filter { it.id !in known && it.reference !in known }
                merged[i] = existing.copy(passages = existing.passages + added)
                newPassages += added.size
            }
        }
        for ((word, stat) in incoming.voiceProfile.words) data.voiceProfile.words.putIfAbsent(word, stat)

        update(data.copy(collections = merged))
        message = "Imported $newCollections new collection(s) and $newPassages passage(s)."
    }

    private companion object {
        const val PREF_SPLIT_SECTIONS = "splitSections"
    }
}
