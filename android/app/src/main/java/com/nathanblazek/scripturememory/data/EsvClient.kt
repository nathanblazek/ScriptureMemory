package com.nathanblazek.scripturememory.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

class EsvException(message: String) : Exception(message)

/** Minimal client for the ESV API text endpoint (https://api.esv.org/docs/passage-text/). */
object EsvClient {
    private const val OPTIONS =
        "&include-passage-references=false" +
            "&include-verse-numbers=true" +
            "&include-first-verse-numbers=true" +
            "&include-footnotes=false" +
            "&include-footnote-body=false" +
            "&include-headings=false" +
            "&include-short-copyright=false" +
            "&include-copyright=false" +
            "&include-selahs=true" +
            "&indent-paragraphs=0" +
            "&indent-poetry=false" +
            "&indent-declares=0" +
            "&indent-psalm-doxology=0" +
            "&line-length=0"

    /** Returns the canonical reference and the passage text. */
    suspend fun getPassage(apiKey: String, query: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val url = URL("https://api.esv.org/v3/passage/text/?q=" + URLEncoder.encode(query, "UTF-8") + OPTIONS)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Token $apiKey")

            val code = try {
                conn.responseCode
            } catch (e: SocketTimeoutException) {
                throw EsvException("The ESV API took too long to respond. Please try again.")
            } catch (e: IOException) {
                throw EsvException("Couldn't reach the ESV API. Check your internet connection. (${e.message})")
            }

            when {
                code == 401 || code == 403 ->
                    throw EsvException("The ESV API rejected your API key. Check it under \"ESV API key\".")
                code == 429 -> throw EsvException("ESV API rate limit reached. Please wait a bit and try again.")
                code !in 200..299 -> throw EsvException("ESV API error $code (${conn.responseMessage}).")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(body).jsonObject
            val canonical = (root["canonical"] as? JsonPrimitive)?.content.orEmpty()
            val passages = (root["passages"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
                .filter { it.isNotBlank() }

            if (passages.isEmpty() || canonical.isBlank())
                throw EsvException("No passage found for \"$query\". Try a reference like \"John 3:16-18\".")

            canonical to passages.joinToString("\n\n").trim()
        } finally {
            conn.disconnect()
        }
    }
}
