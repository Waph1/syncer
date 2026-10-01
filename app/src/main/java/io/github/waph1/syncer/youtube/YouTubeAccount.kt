package io.github.waph1.syncer.youtube

import android.content.Context
import io.github.waph1.syncer.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * YouTube sign-in for yt-dlp, needed for "Guarda più tardi" and private playlists: the
 * youtube.com cookies of a web session, kept encrypted in the [SecretStore] (never in the
 * settings backups) and written to a private temporary file only while yt-dlp runs.
 */
class YouTubeAccount(private val context: Context, private val secrets: SecretStore) {
    private val signedInState = MutableStateFlow(secrets.get(KEY) != null)
    val signedIn: StateFlow<Boolean> = signedInState.asStateFlow()

    /** Saves cookies in Netscape format; throws [IllegalArgumentException] without a YouTube session. */
    fun saveCookies(netscape: String) {
        val cookies = NetscapeCookies.youtubeOnly(netscape)
        require(NetscapeCookies.hasYouTubeSession(cookies)) {
            "Il file non contiene una sessione di YouTube: esporta i cookie dopo aver effettuato l'accesso a youtube.com"
        }
        secrets.put(KEY, cookies)
        signedInState.value = true
    }

    fun signOut() {
        secrets.put(KEY, null)
        signedInState.value = false
    }

    /**
     * Runs [block] with the cookies in a temporary file (null when signed out). yt-dlp refreshes
     * the cookies in that file: the updated session is saved back afterwards.
     */
    suspend fun <T> withCookies(block: suspend (File?) -> T): T {
        val stored = secrets.get(KEY) ?: return block(null)
        val file = withContext(Dispatchers.IO) {
            File(context.noBackupFilesDir, "youtube/cookies-${UUID.randomUUID()}.txt").apply {
                parentFile?.mkdirs()
                writeText(stored)
            }
        }
        try {
            return block(file)
        } finally {
            withContext(Dispatchers.IO) {
                val updated = runCatching { NetscapeCookies.youtubeOnly(file.readText()) }.getOrNull()
                // Keep the stored session if signed out meanwhile or if yt-dlp dropped it.
                if (updated != null && updated != stored && signedInState.value && NetscapeCookies.hasYouTubeSession(updated)) {
                    secrets.put(KEY, updated)
                }
                file.delete()
            }
        }
    }

    private companion object {
        const val KEY = "youtube_cookies"
    }
}

/** Cookies in the Netscape format read by yt-dlp ("cookies.txt"). */
object NetscapeCookies {
    private const val HEADER = "# Netscape HTTP Cookie File"
    private const val HTTP_ONLY = "#HttpOnly_"

    /** Cookies proving a signed-in YouTube session (used by yt-dlp to authenticate). */
    private val SESSION_COOKIES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    /**
     * Converts a WebView "Cookie" header ("a=1; b=2") for youtube.com. The WebView does not expose
     * expiry and flags, so cookies are written as secure, valid for 400 days (the browser maximum).
     */
    fun fromCookieHeader(header: String, nowMillis: Long): String {
        val expiry = nowMillis / 1000 + 400L * 24 * 3600
        val lines = header.split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty() || '\t' in value) return@mapNotNull null
            // __Host- cookies are bound to the exact host.
            if (name.startsWith("__Host-")) "www.youtube.com\tFALSE\t/\tTRUE\t$expiry\t$name\t$value"
            else ".youtube.com\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"
        }
        return (listOf(HEADER, "") + lines).joinToString("\n", postfix = "\n")
    }

    /** Keeps only well-formed youtube.com cookies (a browser export usually has many other sites). */
    fun youtubeOnly(text: String): String {
        val lines = text.removePrefix("\uFEFF").lineSequence()
            .map { it.trimEnd('\r') }
            .filter { line ->
                val fields = line.split('\t')
                val domain = fields[0].removePrefix(HTTP_ONLY).trimStart('.')
                (!line.startsWith("#") || line.startsWith(HTTP_ONLY)) && fields.size == 7 &&
                    (domain == "youtube.com" || domain.endsWith(".youtube.com"))
            }
            .toList()
        return (listOf(HEADER, "") + lines).joinToString("\n", postfix = "\n")
    }

    fun hasYouTubeSession(text: String): Boolean = text.lineSequence().any { line ->
        val fields = line.split('\t')
        fields.size == 7 && fields[5] in SESSION_COOKIES && fields[6].isNotBlank()
    }

    fun headerHasYouTubeSession(header: String): Boolean =
        header.split(';').any { it.substringBefore('=').trim() in SESSION_COOKIES }
}
