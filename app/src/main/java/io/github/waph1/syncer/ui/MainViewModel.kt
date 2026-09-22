package io.github.waph1.syncer.ui

import android.app.Application
import android.app.PendingIntent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.SyncType
import io.github.waph1.syncer.source.AuthorizationRequiredException
import io.github.waph1.syncer.sync.StatusSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of checking access to a Google API scope. */
sealed interface AuthState {
    data object Unknown : AuthState
    data object Checking : AuthState
    data object Granted : AuthState
    data class NeedsConsent(val pendingIntent: PendingIntent?) : AuthState
    data class Failed(val message: String) : AuthState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer

    val settings: StateFlow<AppSettings> = container.settings.settings
    val status: StateFlow<StatusSnapshot> = container.status.status
    val running: StateFlow<Set<SyncType>> = container.status.running

    private val draftState = MutableStateFlow(container.settings.current)
    /** Settings being edited by the setup wizard; saved only when the wizard completes. */
    val draft: StateFlow<AppSettings> = draftState.asStateFlow()

    private val authStates = MutableStateFlow<Map<String, AuthState>>(emptyMap())
    val auth: StateFlow<Map<String, AuthState>> = authStates.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun startSetup() {
        draftState.value = container.settings.current
    }

    fun editDraft(transform: (AppSettings) -> AppSettings) = draftState.update(transform)

    fun finishSetup() {
        container.settings.replace(draftState.value.copy(setupCompleted = true))
        container.scheduler.syncNow()
    }

    fun update(transform: (AppSettings) -> AppSettings) = container.settings.update(transform)

    fun syncNow(types: Set<SyncType> = SyncType.entries.toSet()) {
        container.scheduler.syncNow(types)
        message("Sincronizzazione avviata")
    }

    fun message(text: String) {
        messageChannel.trySend(text)
    }

    suspend fun readSettingsFile(uri: Uri): Result<AppSettings> =
        withContext(Dispatchers.IO) { runCatching { container.backup.read(uri) } }

    fun backupNow() = viewModelScope.launch {
        val error = container.backupNow()
        message(error?.let { "Backup non riuscito: $it" } ?: "Backup delle impostazioni salvato")
    }

    /** Checks (without UI) whether a token for [scope] can be obtained for [account]. */
    suspend fun checkAuthorization(account: String, scope: String): AuthState {
        authStates.update { it + (scope to AuthState.Checking) }
        val result = withContext(Dispatchers.IO) {
            try {
                container.auth.token(account, scope)
                AuthState.Granted
            } catch (e: AuthorizationRequiredException) {
                AuthState.NeedsConsent(e.pendingIntent)
            } catch (e: Exception) {
                AuthState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        authStates.update { it + (scope to result) }
        return result
    }
}
