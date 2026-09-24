package io.github.waph1.syncer.source

import io.github.waph1.syncer.format.PasswordEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/**
 * Parses the FIDO Credential Exchange Format (CXF 1.0) received from Google Password Manager
 * through Android's credential transfer: logins ("basic-auth"), their notes and TOTP secrets.
 * Passkeys are not requested and are ignored.
 */
object CxfParser {

    fun parse(json: String): List<PasswordEntry> {
        val root = ApiJson.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("Risposta non valida")
        val accounts = when {
            root["accounts"] is JsonArray -> root.array("accounts").filterIsInstance<JsonObject>()
            root["items"] is JsonArray -> listOf(root)
            else -> emptyList()
        }
        return accounts.flatMap { account -> account.array("items").filterIsInstance<JsonObject>().mapNotNull(::entry) }
    }

    private fun entry(item: JsonObject): PasswordEntry? {
        val credentials = item.array("credentials").filterIsInstance<JsonObject>()
        val basic = credentials.firstOrNull { it.string("type") == "basic-auth" }
        val notes = credentials.filter { it.string("type") == "note" }.mapNotNull { it.field("content") }
        val totp = credentials.firstOrNull { it.string("type") == "totp" }
        if (basic == null && totp == null) return null // e.g. passkey-only items

        val scope = item["scope"] as? JsonObject
        val urls = scope?.array("urls")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val apps = scope?.array("androidApps")?.filterIsInstance<JsonObject>()?.mapNotNull { it.string("bundleId") }.orEmpty()
        val username = basic?.field("username") ?: totp?.string("username").orEmpty()
        val title = item.string("title")?.takeIf { it.isNotBlank() }
            ?: urls.firstOrNull()?.let(::hostOf)
            ?: apps.firstOrNull()
            ?: username.ifBlank { "Senza titolo" }

        val extra = linkedMapOf<String, String>()
        apps.forEachIndexed { i, app -> extra[if (i == 0) "AndroidApp" else "AndroidApp_$i"] = app }
        urls.drop(1).forEachIndexed { i, url -> extra["KP2A_URL_${i + 1}"] = url }
        item.string("subtitle")?.takeIf { it.isNotBlank() && it != title }?.let { extra["Sottotitolo"] = it }

        return PasswordEntry(
            title = title,
            username = username,
            password = basic?.field("password").orEmpty(),
            url = urls.firstOrNull() ?: apps.firstOrNull()?.let { "androidapp://$it" }.orEmpty(),
            notes = notes.joinToString("\n\n"),
            otp = totp?.let(::otpUri),
            extra = extra,
            tags = item.array("tags").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            created = item.long("creationAt")?.let(Instant::ofEpochSecond),
            modified = item.long("modifiedAt")?.let(Instant::ofEpochSecond),
        )
    }

    private fun otpUri(totp: JsonObject): String? {
        val secret = totp.string("secret")?.takeIf { it.isNotBlank() } ?: return null
        val issuer = totp.string("issuer").orEmpty()
        val user = totp.string("username").orEmpty()
        val label = listOf(issuer, user).filter { it.isNotEmpty() }.joinToString(":")
        val params = buildList {
            add("secret=" + enc(secret))
            if (issuer.isNotEmpty()) add("issuer=" + enc(issuer))
            add("period=" + (totp.long("period") ?: 30))
            add("digits=" + (totp.long("digits") ?: 6))
            add("algorithm=" + (totp.string("algorithm") ?: "sha1").uppercase(Locale.ROOT))
        }
        return "otpauth://totp/" + enc(label) + "?" + params.joinToString("&")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Value of an EditableField member (`{"fieldType": ..., "value": ...}`) or of a plain string. */
    private fun JsonObject.field(name: String): String? = when (val v = this[name]) {
        is JsonObject -> (v["value"] as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> v.contentOrNull
        else -> null
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.array(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()
}

/**
 * Parses the CSV exported by Google Password Manager ("name,url,username,password,note"),
 * the fallback when credential transfer is not available.
 */
object GooglePasswordsCsv {

    fun parse(text: String): List<PasswordEntry> {
        val rows = rows(text.removePrefix("\uFEFF"))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        fun column(vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val name = column("name", "title")
        val url = column("url", "website", "origin")
        val user = column("username", "login", "user")
        val password = column("password") ?: throw IllegalArgumentException("Il file CSV non contiene una colonna \"password\"")
        val note = column("note", "notes", "comment")
        return rows.drop(1).filter { row -> row.any { it.isNotBlank() } }.map { row ->
            fun at(i: Int?) = i?.let { row.getOrNull(it) }.orEmpty()
            val rawUrl = at(url)
            val app = androidPackage(rawUrl)
            PasswordEntry(
                title = at(name).ifBlank { app ?: hostOf(rawUrl) ?: at(user).ifBlank { "Senza titolo" } },
                username = at(user),
                password = at(password),
                url = app?.let { "androidapp://$it" } ?: rawUrl,
                notes = at(note),
                extra = app?.let { mapOf("AndroidApp" to it) }.orEmpty(),
            )
        }
    }

    /** "android://<hash>@com.example.app/" → "com.example.app". */
    internal fun androidPackage(url: String): String? =
        if (url.startsWith("android://")) url.substringAfter('@', "").trimEnd('/').takeIf { it.isNotEmpty() } else null

    /** RFC 4180: quoted fields may contain commas, quotes ("") and line breaks. */
    internal fun rows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    ',' -> { row.add(field.toString()); field.setLength(0) }
                    '\r' -> Unit
                    '\n' -> { row.add(field.toString()); field.setLength(0); rows.add(row); row = mutableListOf() }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}

internal fun hostOf(url: String): String? =
    runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }
