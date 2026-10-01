package io.github.waph1.syncer.sync

import android.content.Context
import androidx.core.content.edit
import io.github.waph1.syncer.settings.SyncType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the user has to do to fix a failing sync. */
enum class Problem { PERMISSION, AUTHORIZATION, FOLDER, CONFIGURATION, TRANSIENT }

@Serializable
data class TypeStatus(
    val lastRunAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val ok: Boolean = true,
    val message: String = "",
    val problem: Problem? = null,
)

@Serializable
data class PlaylistStatus(
    val lastRunAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val ok: Boolean = true,
    val message: String = "",
    val problem: Problem? = null,
    /** Title as last read from YouTube. */
    val title: String? = null,
    /** Videos of the playlist present in the folder. */
    val videos: Int = 0,
    /** Videos of the playlist not downloaded (yet). */
    val missing: Int = 0,
)

/** Download in progress, shown in the app and in the notification. */
data class PlaylistProgress(
    val playlistId: String,
    val playlistTitle: String,
    /** 1-based position among the videos to download in this run. */
    val index: Int,
    val total: Int,
    val videoTitle: String,
    /** 0..100, or null before the download starts. */
    val percent: Float?,
)

@Serializable
data class StatusSnapshot(
    val types: Map<SyncType, TypeStatus> = emptyMap(),
    val lastBackupAt: Long? = null,
    val lastBackupFile: String? = null,
    val lastBackupError: String? = null,
    /** Identity of the last processed Takeout export, to skip unchanged exports. */
    val takeoutSignature: String? = null,
    /** Last import of Google passwords into the KeePass database. */
    val passwords: TypeStatus? = null,
    /** When the password reminder was enabled or last shown: the next one is due an interval later. */
    val passwordReminderAnchor: Long? = null,
    /** YouTube playlists by id. */
    val playlists: Map<String, PlaylistStatus> = emptyMap(),
    val ytDlpVersion: String? = null,
    val ytDlpUpdateCheckedAt: Long? = null,
)

/** Last sync results (persisted) and currently running syncs (in memory). */
class StatusRepository(context: Context) {
    private val prefs = context.getSharedPreferences("status", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())
    private val runningState = MutableStateFlow<Set<SyncType>>(emptySet())
    private val playlistRunningState = MutableStateFlow(false)
    private val progressState = MutableStateFlow<PlaylistProgress?>(null)

    val status: StateFlow<StatusSnapshot> = state.asStateFlow()
    val running: StateFlow<Set<SyncType>> = runningState.asStateFlow()
    val current: StatusSnapshot get() = state.value
    /** True while YouTube playlists are being synced. */
    val playlistRunning: StateFlow<Boolean> = playlistRunningState.asStateFlow()
    val playlistProgress: StateFlow<PlaylistProgress?> = progressState.asStateFlow()

    fun setRunning(type: SyncType, running: Boolean) =
        runningState.update { if (running) it + type else it - type }

    fun recordSuccess(type: SyncType, message: String) = edit { s ->
        val now = System.currentTimeMillis()
        s.copy(types = s.types + (type to TypeStatus(now, now, true, message, null)))
    }

    fun recordFailure(type: SyncType, message: String, problem: Problem) = edit { s ->
        val previous = s.types[type]
        s.copy(types = s.types + (type to TypeStatus(System.currentTimeMillis(), previous?.lastSuccessAt, false, message, problem)))
    }

    fun recordBackup(fileName: String?, error: String?) = edit { s ->
        if (error == null) s.copy(lastBackupAt = System.currentTimeMillis(), lastBackupFile = fileName, lastBackupError = null)
        else s.copy(lastBackupError = error)
    }

    fun recordPasswords(ok: Boolean, message: String) = edit { s ->
        val now = System.currentTimeMillis()
        s.copy(passwords = TypeStatus(now, if (ok) now else s.passwords?.lastSuccessAt, ok, message, if (ok) null else Problem.CONFIGURATION))
    }

    fun setPasswordReminderAnchor(at: Long?) = edit { it.copy(passwordReminderAnchor = at) }

    fun setPlaylistRunning(running: Boolean) {
        playlistRunningState.value = running
        if (!running) progressState.value = null
    }

    fun setPlaylistProgress(progress: PlaylistProgress?) {
        progressState.value = progress
    }

    fun recordPlaylist(id: String, transform: (PlaylistStatus) -> PlaylistStatus) = edit { s ->
        s.copy(playlists = s.playlists + (id to transform(s.playlists[id] ?: PlaylistStatus())))
    }

    /** Drops the status of playlists no longer configured. */
    fun retainPlaylists(ids: Set<String>) = edit { s ->
        if (s.playlists.keys.all { it in ids }) s else s.copy(playlists = s.playlists.filterKeys { it in ids })
    }

    fun recordYtDlp(version: String?, checkedAt: Long?) = edit { s ->
        s.copy(ytDlpVersion = version ?: s.ytDlpVersion, ytDlpUpdateCheckedAt = checkedAt ?: s.ytDlpUpdateCheckedAt)
    }

    fun setTakeoutSignature(signature: String?) = edit { it.copy(takeoutSignature = signature) }

    fun clear(type: SyncType) = edit { it.copy(types = it.types - type) }

    private fun edit(transform: (StatusSnapshot) -> StatusSnapshot) {
        synchronized(this) {
            val next = transform(state.value)
            state.value = next
            prefs.edit { putString(KEY, json.encodeToString(StatusSnapshot.serializer(), next)) }
        }
    }

    private fun load(): StatusSnapshot = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString(StatusSnapshot.serializer(), it) }.getOrNull() }
        ?: StatusSnapshot()

    private companion object {
        const val KEY = "status_json"
    }
}
