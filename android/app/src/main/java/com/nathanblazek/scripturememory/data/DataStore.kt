package com.nathanblazek.scripturememory.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nathanblazek.scripturememory.model.AppData
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persists collections and the voice profile to data.json in the app's private files. */
class DataStore(private val context: Context) {
    private val file get() = File(context.filesDir, "data.json")

    fun load(): AppData {
        val f = file
        if (!f.exists()) return AppData()
        return try {
            AppJson.decode(f.readText())
        } catch (e: Exception) {
            // Keep the unreadable file around rather than silently overwriting the user's data.
            val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
            runCatching { f.copyTo(File(f.path + ".corrupt-$stamp"), overwrite = true) }
            AppData()
        }
    }

    fun save(data: AppData) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(AppJson.encode(data))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}

/** The ESV API key, encrypted with a key kept in the Android Keystore. */
class ApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(): String {
        val stored = prefs.getString(PREF, null) ?: return ""
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun set(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(PREF).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(PREF, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREF = "esv_api_key"
        const val ALIAS = "esv_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
