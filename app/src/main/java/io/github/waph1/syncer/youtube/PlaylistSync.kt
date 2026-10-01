package io.github.waph1.syncer.youtube

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import io.github.waph1.syncer.format.FileNames
import io.github.waph1.syncer.settings.PlaylistTarget
import io.github.waph1.syncer.settings.SettingsRepository
import io.github.waph1.syncer.storage.FolderAccessException
import io.github.waph1.syncer.storage.SafFolder
import io.github.waph1.syncer.sync.Notifier
import io.github.waph1.syncer.sync.PlaylistProgress
import io.github.waph1.syncer.sync.Problem
import io.github.waph1.syncer.sync.StatusRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Locale

/** A playlist problem the user has to fix (folder, sign-in). */
class PlaylistProblem(val problem: Problem, message: String) : Exception(message)

/**
 * One-way mirror of YouTube playlists into folders: videos added to a playlist are downloaded
 * ("<title> [<id>].<ext>"), videos removed from it are deleted from the folder. Only files the
 * app downloaded (tracked in a private manifest) are ever deleted, and files already in the
 * folder with the video id in their name are adopted instead of downloaded again.
 */
class PlaylistSync(
    private val context: Context,
    private val settings: SettingsRepository,
    private val status: StatusRepository,
    private val notifier: Notifier,
    private val account: YouTubeAccount,
    private val backend: VideoBackend,
    private val isMetered: () -> Boolean = {
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
    },
) {
    private val mutex = Mutex()

    @Serializable
    private data class Manifest(
        val folderUri: String,
        /** Video id → file name. */
        val files: Map<String, String> = emptyMap(),
        /** Consecutive syncs that found the playlist empty. */
        val emptyListings: Int = 0,
    )

    suspend fun run() = mutex.withLock {
        val s = settings.current
        if (!s.setupCompleted || !s.youtube.enabled || s.youtube.playlists.isEmpty()) return@withLock
        status.setPlaylistRunning(true)
        try {
            withContext(Dispatchers.IO) {
                updateDownloaderIfDue()
                val metered = s.youtube.wifiOnly && isMetered()
                for (configured in s.youtube.playlists) {
                    // The settings may change during a long run.
                    val youtube = settings.current.youtube
                    if (!youtube.enabled) break
                    val playlist = youtube.playlists.firstOrNull { it.id == configured.id } ?: continue
                    syncPlaylist(playlist, metered)
                }
            }
        } finally {
            status.setPlaylistRunning(false)
        }
    }

    /** Updates yt-dlp now; returns a message for the user. */
    suspend fun updateDownloader(): String = withContext(Dispatchers.IO) {
        val updated = backend.update()
        val version = updated ?: backend.version()
        status.recordYtDlp(version, System.currentTimeMillis())
        if (updated != null) "yt-dlp aggiornato alla versione $updated" else "yt-dlp è già aggiornato (versione $version)"
    }

    /** The signed-in account's playlists. */
    suspend fun myPlaylists(): List<PlaylistInfo> = withContext(Dispatchers.IO) {
        account.withCookies { cookies ->
            cookies ?: throw VideoException("Accedi a YouTube per vedere le tue playlist", needsSignIn = true)
            backend.listMyPlaylists(cookies)
        }
    }

    /** Reads a playlist (to check a pasted link and get its title). */
    suspend fun lookup(playlistId: String): PlaylistListing = withContext(Dispatchers.IO) {
        account.withCookies { cookies -> backend.listPlaylist(playlistId, cookies) }
    }

    private suspend fun updateDownloaderIfDue() {
        val st = status.current
        val now = System.currentTimeMillis()
        val checked = st.ytDlpUpdateCheckedAt
        if (st.ytDlpVersion != null && checked != null && now - checked in 0 until UPDATE_EVERY_MS) return
        val version = try {
            backend.update() ?: backend.version()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp update failed", e)
            runCatching { backend.version() }.getOrNull()
        }
        status.recordYtDlp(version, now)
    }

    private suspend fun syncPlaylist(p: PlaylistTarget, metered: Boolean) {
        val now = System.currentTimeMillis()
        try {
            val folderUri = p.folderUri ?: throw PlaylistProblem(Problem.FOLDER, "Nessuna cartella selezionata")
            if (p.isWatchLater && !account.signedIn.value) {
                throw PlaylistProblem(Problem.AUTHORIZATION, "Accedi a YouTube nelle impostazioni: \"Guarda più tardi\" è visibile solo con il tuo account")
            }
            val folder = SafFolder.of(context, folderUri)
            val listing = account.withCookies { cookies -> backend.listPlaylist(p.id, cookies) }
            val title = if (p.isWatchLater) WATCH_LATER_TITLE else listing.title.ifBlank { p.title }
            val manifestFile = manifestFile(p.id)
            val manifest = loadManifest(manifestFile, folder.key)
            val files = folder.list().filter { !it.isDirectory }.associateBy { it.name.lowercase(Locale.ROOT) }

            // Videos already in the folder: tracked in the manifest, or named "… [<id>].<ext>".
            val present = linkedMapOf<String, String>()
            for (entry in listing.entries) {
                val tracked = manifest.files[entry.id]?.takeIf { it.lowercase(Locale.ROOT) in files }
                val found = tracked ?: files.values.firstOrNull { "[${entry.id}]." in it.name }?.name
                if (found != null) present[entry.id] = found
            }

            // Videos removed from the playlist. An empty playlist is trusted only when the next
            // sync confirms it, so that a glitch never wipes the folder.
            val wanted = listing.entries.mapTo(HashSet()) { it.id }
            val emptyListings = if (wanted.isEmpty()) manifest.emptyListings + 1 else 0
            val confirmed = wanted.isNotEmpty() || emptyListings >= 2
            val removed = manifest.files.filterKeys { it !in wanted }
            val kept = linkedMapOf<String, String>()
            var deleted = 0
            if (removed.isNotEmpty()) {
                val shared = if (confirmed) namesUsedByOtherPlaylists(p, folder.key) else emptySet()
                for ((id, name) in removed) {
                    val file = files[name.lowercase(Locale.ROOT)] ?: continue // already gone
                    when {
                        !confirmed -> kept[id] = name
                        name.lowercase(Locale.ROOT) in shared -> Unit // still wanted by another playlist
                        folder.delete(file.uri) -> deleted++
                        else -> kept[id] = name
                    }
                }
            }
            fun save() = saveManifest(manifestFile, Manifest(folder.key, present + kept, emptyListings))
            save()

            // Videos to download, in playlist order.
            val missing = listing.entries.filter { it.id !in present && it.available }
            val unavailable = listing.entries.count { it.id !in present && !it.available }
            val failures = mutableListOf<String>()
            var downloaded = 0
            var needsSignIn = false
            var stoppedEarly = false
            if (!metered) {
                var consecutiveFailures = 0
                for ((index, entry) in missing.withIndex()) {
                    if (settings.current.youtube.playlists.none { it.id == p.id }) break // removed meanwhile
                    val progress = PlaylistProgress(p.id, title, index + 1, missing.size, entry.title, null)
                    status.setPlaylistProgress(progress)
                    try {
                        present[entry.id] = download(p, entry, folder) { percent ->
                            status.setPlaylistProgress(progress.copy(percent = percent))
                        }
                        downloaded++
                        consecutiveFailures = 0
                        save()
                    } catch (e: VideoException) {
                        Log.w(TAG, "Download of ${entry.id} failed", e)
                        failures += "${entry.title}: ${e.message}"
                        needsSignIn = needsSignIn || (e.needsSignIn && !account.signedIn.value)
                        // Do not insist when YouTube is throttling or refusing everything.
                        if (e.rateLimited || ++consecutiveFailures >= 3) {
                            stoppedEarly = true
                            break
                        }
                    } catch (e: FolderAccessException) {
                        throw e
                    } catch (e: IOException) {
                        Log.w(TAG, "Copy of ${entry.id} failed", e)
                        failures += "${entry.title}: ${e.message ?: "errore di scrittura"}"
                        stoppedEarly = true
                        break
                    }
                }
                status.setPlaylistProgress(null)
            }

            val notInFolder = listing.entries.count { it.id !in present }
            val message = buildList {
                add("${present.size} video su ${listing.entries.size} nella cartella")
                if (downloaded > 0) add("$downloaded scaricati")
                if (deleted > 0) add("$deleted eliminati")
                if (metered && missing.isNotEmpty()) add("${missing.size} in attesa del Wi-Fi")
                if (unavailable > 0) add("$unavailable non disponibili")
                if (!confirmed && removed.isNotEmpty()) add("playlist vuota: i file saranno eliminati se lo è ancora alla prossima verifica")
                if (failures.isNotEmpty()) add("${failures.size} non scaricati (${failures.first()})")
            }.joinToString(" · ")
            // A video that cannot be downloaded (members only, removed...) is reported, not an
            // error; a sync stopped by repeated failures or throttling is.
            val ok = !stoppedEarly
            val problem = when {
                ok -> null
                needsSignIn -> Problem.AUTHORIZATION
                else -> Problem.TRANSIENT
            }
            status.recordPlaylist(p.id) {
                it.copy(
                    lastRunAt = now,
                    lastSuccessAt = if (ok) now else it.lastSuccessAt,
                    ok = ok,
                    message = message,
                    problem = problem,
                    title = title,
                    videos = present.size,
                    missing = notInFolder,
                )
            }
            if (problem == Problem.AUTHORIZATION) notifier.showPlaylistProblem(p.id, title, failures.first()) else notifier.clearPlaylistProblem(p.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val problem = when (e) {
                is PlaylistProblem -> e.problem
                is FolderAccessException -> Problem.FOLDER
                is VideoException -> if (e.needsSignIn) Problem.AUTHORIZATION else Problem.TRANSIENT
                else -> Problem.TRANSIENT
            }
            val message = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "Playlist ${p.id} failed", e)
            status.recordPlaylist(p.id) { it.copy(lastRunAt = now, ok = false, message = message, problem = problem) }
            // A playlist still waiting for its folder is shown in the app, without notification.
            if (problem != Problem.TRANSIENT && p.folderUri != null) notifier.showPlaylistProblem(p.id, p.title, message)
        }
    }

    /** Downloads into the cache, then copies into the folder; returns the file name. */
    private suspend fun download(p: PlaylistTarget, entry: VideoEntry, folder: SafFolder, onProgress: (Float) -> Unit): String {
        val dir = File(context.cacheDir, "youtube/${entry.id}")
        dir.deleteRecursively()
        try {
            val file = try {
                backend.download(entry.id, p.quality, dir, null, onProgress)
            } catch (e: VideoException) {
                // Age-restricted videos and bot checks may pass with the account.
                if (!e.needsSignIn || !account.signedIn.value) throw e
                dir.deleteRecursively()
                account.withCookies { cookies -> backend.download(entry.id, p.quality, dir, cookies, onProgress) }
            }
            val name = "${FileNames.sanitize(entry.title).ifEmpty { entry.id }} [${entry.id}].${file.extension}"
            val uri = folder.createFile(name)
            try {
                folder.writeFrom(uri, file)
            } catch (e: Throwable) {
                folder.delete(uri)
                throw e
            }
            return folder.displayName(uri) ?: name
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun namesUsedByOtherPlaylists(p: PlaylistTarget, folderKey: String): Set<String> =
        settings.current.youtube.playlists.filter { it.id != p.id }
            .flatMap { loadManifest(manifestFile(it.id), folderKey).files.values }
            .mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    private fun manifestFile(playlistId: String) =
        File(context.noBackupFilesDir, "manifests/youtube-${playlistId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    private fun loadManifest(file: File, folderKey: String): Manifest {
        val manifest = runCatching { json.decodeFromString(Manifest.serializer(), file.readText()) }.getOrNull()
        // A manifest of another folder must not cause deletions here.
        return manifest?.takeIf { it.folderUri == folderKey } ?: Manifest(folderKey)
    }

    private fun saveManifest(file: File, manifest: Manifest) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Manifest.serializer(), manifest))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val WATCH_LATER_TITLE = "Guarda più tardi"
        private const val TAG = "Syncer"
        private const val UPDATE_EVERY_MS = 24 * 3600 * 1000L
        private val json = Json { ignoreUnknownKeys = true }
    }
}
