package io.github.waph1.syncer.youtube

import io.github.waph1.syncer.settings.VideoQuality
import java.io.File

/** A video of a playlist; [available] is false for private or deleted videos still listed. */
data class VideoEntry(val id: String, val title: String, val available: Boolean = true)

data class PlaylistListing(val id: String, val title: String, val entries: List<VideoEntry>)

/** A playlist of the signed-in account ("Le mie playlist"). */
data class PlaylistInfo(val id: String, val title: String)

/**
 * A download or listing failure with a user-facing message. [needsSignIn]: YouTube asked for an
 * account (bot check, age restriction, private playlist); [rateLimited]: YouTube is throttling.
 */
class VideoException(
    message: String,
    val needsSignIn: Boolean = false,
    val rateLimited: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/** What the playlist sync needs from the downloader (yt-dlp on the device, a fake in tests). */
interface VideoBackend {
    /** Version of the downloader (initializing it on first use). */
    suspend fun version(): String?

    /** Updates the downloader; returns the new version, or null when already up to date. */
    suspend fun update(): String?

    suspend fun listPlaylist(playlistId: String, cookies: File?): PlaylistListing

    suspend fun listMyPlaylists(cookies: File): List<PlaylistInfo>

    /** Downloads [videoId] into the empty folder [dir] and returns the resulting file. Cancellable. */
    suspend fun download(videoId: String, quality: VideoQuality, dir: File, cookies: File?, onProgress: (Float) -> Unit): File
}
