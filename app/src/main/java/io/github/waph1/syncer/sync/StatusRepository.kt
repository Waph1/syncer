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
data class StatusSnapshot(
    val types: Map<SyncType, TypeStatus> = emptyMap(),
    val lastBackupAt: Long? = null,
    val lastBackupFile: String? = null,
    val lastBackupError: String? = null,
    /** Identity of the last processed Takeout export, to skip unchanged exports. */
    val takeoutSignature: String? = null,
)

/** Last sync results (persisted) and currently running syncs (in memory). */
class StatusRepository(context: Context) {
    private val prefs = context.getSharedPreferences("status", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())
    private val runningState = MutableStateFlow<Set<SyncType>>(emptySet())

    val status: StateFlow<StatusSnapshot> = state.asStateFlow()
    val running: StateFlow<Set<SyncType>> = runningState.asStateFlow()
    val current: StatusSnapshot get() = state.value

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
