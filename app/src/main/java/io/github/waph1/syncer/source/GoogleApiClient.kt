package io.github.waph1.syncer.source

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(val code: Int, message: String) : IOException(message)

/** Tiny authenticated JSON GET client for Google REST APIs (no extra HTTP library needed). */
class GoogleApiClient(
    private val auth: GoogleAuth,
    private val accountName: String,
    private val scope: String,
) {
    private var token: String? = null

    internal fun <T> get(url: String, serializer: KSerializer<T>): T =
        ApiJson.decodeFromString(serializer, getText(url))

    private fun getText(url: String): String {
        repeat(2) { attempt ->
            val current = token ?: auth.token(accountName, scope).also { token = it }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $current")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED && attempt == 0) {
                    // Expired or revoked token: drop it from Play services' cache and retry once.
                    auth.invalidate(current)
                    token = null
                    return@repeat
                }
                if (code !in 200..299) {
                    val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw ApiException(code, describeError(code, body))
                }
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw ApiException(HttpURLConnection.HTTP_UNAUTHORIZED, "Accesso negato da Google (401)")
    }

    private fun describeError(code: Int, body: String): String {
        val message = runCatching {
            ApiJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        val hint = when (code) {
            403 -> if (message?.contains("has not been used", ignoreCase = true) == true ||
                message?.contains("disabled", ignoreCase = true) == true
            ) " — abilita l'API nel progetto Google Cloud" else ""
            else -> ""
        }
        return "Errore API Google $code: ${message ?: "risposta non valida"}$hint"
    }

    companion object {
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
