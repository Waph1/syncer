package io.github.waph1.syncer.youtube

import io.github.waph1.syncer.settings.PlaylistTarget
import io.github.waph1.syncer.settings.VideoQuality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** yt-dlp command lines and output parsing (pure functions, independent of Android). */
object YtDlp {
    const val MY_PLAYLISTS_URL = "https://www.youtube.com/feed/playlists"

    private val json = Json { ignoreUnknownKeys = true }
    private val ID = Regex("[A-Za-z0-9_-]+")

    fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"

    fun videoUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    /**
     * Playlist id from a link ("…?list=PL…", including youtu.be and music.youtube.com links) or a
     * bare id. Returns null for anything else.
     */
    fun playlistIdFrom(input: String): String? {
        val text = input.trim()
        Regex("[?&]list=([A-Za-z0-9_-]+)").find(text)?.let { return it.groupValues[1] }
        if (ID.matches(text) && BARE_PREFIXES.any { text.startsWith(it) } && text.length in 2..64) return text
        return null
    }

    private val BARE_PREFIXES = listOf("PL", "OL", "UU", "FL", "LL", PlaylistTarget.WATCH_LATER)

    /** Arguments to list a playlist's videos without resolving each one. */
    fun listArgs(cookies: String?): List<String> = listOf("--flat-playlist", "--dump-single-json") + cookieArgs(cookies)

    /** Arguments to download one video into [dir] as "<id>.<ext>". */
    fun downloadArgs(quality: VideoQuality, dir: String, cookies: String?): List<String> =
        listOf(
            "--no-playlist",
            "--no-mtime",
            "--no-overwrites",
            "--embed-metadata",
            "--socket-timeout", "30",
            "-o", "$dir/%(id)s.%(ext)s",
        ) + formatArgs(quality) + cookieArgs(cookies)

    /**
     * Format selection: the best video up to the chosen height (by the shorter side, so vertical
     * videos work too), preferring H.264 + AAC in MP4 for compatibility; above 1080p YouTube only
     * has VP9/AV1, still merged into MP4. Audio-only options extract the audio track.
     */
    fun formatArgs(quality: VideoQuality): List<String> = when (quality) {
        VideoQuality.AUDIO_M4A -> listOf("-f", "ba[ext=m4a]/ba", "-x", "--audio-format", "m4a")
        VideoQuality.AUDIO_MP3 -> listOf("-f", "ba", "-x", "--audio-format", "mp3", "--audio-quality", "0")
        else -> listOf(
            "-f", "bv*+ba/b",
            "-S", (quality.height?.let { "res:$it" } ?: "res") + ",+codec:avc:m4a",
            "--merge-output-format", "mp4",
        )
    }

    private fun cookieArgs(cookies: String?) = if (cookies != null) listOf("--cookies", cookies) else emptyList()

    /** Parses `--flat-playlist --dump-single-json` output. */
    fun parseListing(output: String): PlaylistListing {
        val root = parseObject(output)
        val entries = (root["entries"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { e ->
            val id = e.string("id")?.takeIf { ID.matches(it) } ?: return@mapNotNull null
            val title = e.string("title").orEmpty()
            val unavailable = title in UNAVAILABLE_TITLES ||
                e.string("availability") in setOf("private", "premium_only", "subscriber_only", "needs_auth")
            VideoEntry(id, title.ifBlank { id }, available = !unavailable)
        }.distinctBy { it.id }
        return PlaylistListing(root.string("id").orEmpty(), root.string("title").orEmpty(), entries)
    }

    /** Parses the playlists of `feed/playlists` (entries that are playlists, not videos). */
    fun parsePlaylists(output: String): List<PlaylistInfo> {
        val root = parseObject(output)
        return (root["entries"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { e ->
            val id = e.string("url")?.let(::playlistIdFrom) ?: e.string("id")?.takeIf { it.startsWith("PL") || it == "LL" }
            id?.let { PlaylistInfo(it, e.string("title")?.takeIf { t -> t.isNotBlank() } ?: it) }
        }.distinctBy { it.id }
    }

    private fun parseObject(output: String): JsonObject {
        // The JSON is the last line; anything before it would be stray output.
        val line = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
            ?: throw VideoException("Risposta di yt-dlp non valida")
        return runCatching { json.parseToJsonElement(line) as JsonObject }
            .getOrElse { throw VideoException("Risposta di yt-dlp non valida", cause = it) }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private val UNAVAILABLE_TITLES = setOf("[Private video]", "[Deleted video]", "[Video privato]", "[Video eliminato]")

    /** Turns yt-dlp's error output into a short Italian message. */
    fun describeError(output: String): VideoException {
        val error = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("ERROR:") }
            ?.removePrefix("ERROR:")?.trim()
            ?: output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            ?: "errore sconosciuto"
        val detail = error.replace(Regex("^\\[[^]]+] [A-Za-z0-9_-]+: "), "").substringBefore(". Use --cookies").take(300)
        return when {
            "confirm you" in error && "bot" in error ->
                VideoException("YouTube chiede di confermare di non essere un bot: accedi a YouTube nelle impostazioni o riprova più tardi", needsSignIn = true)
            "confirm your age" in error || "age-restricted" in error || "inappropriate for some users" in error ->
                VideoException("Video con limiti di età: serve l'accesso a YouTube", needsSignIn = true)
            "members-only" in error || "Join this channel" in error ->
                VideoException("Video riservato agli abbonati del canale")
            "Private video" in error || "This video is private" in error ->
                VideoException("Video privato", needsSignIn = true)
            "The playlist does not exist" in error || "playlist does not exist" in error ->
                VideoException("La playlist non esiste o è privata: se è tua, accedi a YouTube nelle impostazioni", needsSignIn = true)
            "unviewable" in error || "Sign in" in error || "login" in error.lowercase() ->
                VideoException("Serve l'accesso a YouTube: $detail", needsSignIn = true)
            "429" in error || "Too Many Requests" in error ->
                VideoException("YouTube sta limitando le richieste: riprova più tardi", rateLimited = true)
            "No space left" in error ->
                VideoException("Spazio esaurito sul telefono")
            "Video unavailable" in error || "not available" in error ->
                VideoException("Video non disponibile: $detail")
            else -> VideoException("yt-dlp: $detail")
        }
    }
}
