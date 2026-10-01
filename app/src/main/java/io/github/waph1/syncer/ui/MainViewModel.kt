package io.github.waph1.syncer.ui

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.credentials.providerevents.exception.ImportCredentialsException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.PlaylistTarget
import io.github.waph1.syncer.settings.SyncType
import io.github.waph1.syncer.settings.VideoQuality
import io.github.waph1.syncer.source.AuthorizationRequiredException
import io.github.waph1.syncer.source.CalendarSource
import io.github.waph1.syncer.source.GooglePasswordTransfer
import io.github.waph1.syncer.source.GooglePasswordsCsv
import io.github.waph1.syncer.sync.VaultException
import io.github.waph1.syncer.sync.PlaylistProgress
import io.github.waph1.syncer.sync.StatusSnapshot
import io.github.waph1.syncer.youtube.PlaylistInfo
import io.github.waph1.syncer.youtube.PlaylistSync
import io.github.waph1.syncer.youtube.VideoException
import io.github.waph1.syncer.youtube.YtDlp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A calendar of the account as stored on the device, with its number of events. */
data class CalendarStatus(val ref: CalendarSource.CalendarRef, val events: Int)

/** "Le mie playlist" in the add-playlist dialog. */
sealed interface MyPlaylists {
    data object Idle : MyPlaylists
    data object Loading : MyPlaylists
    data class Loaded(val playlists: List<PlaylistInfo>) : MyPlaylists
    data class Failed(val message: String) : MyPlaylists
}

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

    private val calendarState = MutableStateFlow<List<CalendarStatus>?>(null)
    /** Calendars of the account on the device; null while loading or without permission. */
    val calendars: StateFlow<List<CalendarStatus>?> = calendarState.asStateFlow()

    /** True while the KeePass database is being encrypted (Argon2 takes a few seconds). */
    private val passwordBusyState = MutableStateFlow(false)
    val passwordBusy: StateFlow<Boolean> = passwordBusyState.asStateFlow()
    val hasDatabasePassword: StateFlow<Boolean> = container.vault.hasPassword

    /** CSV just imported, which the user is asked to delete (it holds passwords in clear). */
    private val csvToDeleteState = MutableStateFlow<Uri?>(null)
    val csvToDelete: StateFlow<Uri?> = csvToDeleteState.asStateFlow()

    val playlistRunning: StateFlow<Boolean> = container.status.playlistRunning
    val playlistProgress: StateFlow<PlaylistProgress?> = container.status.playlistProgress
    val youtubeSignedIn: StateFlow<Boolean> = container.youtubeAccount.signedIn

    /** True while a YouTube action of the settings runs (checking a link, updating yt-dlp...). */
    private val youtubeBusyState = MutableStateFlow(false)
    val youtubeBusy: StateFlow<Boolean> = youtubeBusyState.asStateFlow()

    private val myPlaylistsState = MutableStateFlow<MyPlaylists>(MyPlaylists.Idle)
    val myPlaylists: StateFlow<MyPlaylists> = myPlaylistsState.asStateFlow()

    /** cookies.txt just imported, which the user is asked to delete (it holds a session). */
    private val cookiesToDeleteState = MutableStateFlow<Uri?>(null)
    val cookiesToDelete: StateFlow<Uri?> = cookiesToDeleteState.asStateFlow()

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
        if (settings.value.youtube.enabled && settings.value.youtube.playlists.isNotEmpty()) container.scheduler.syncPlaylistsNow()
        message("Sincronizzazione avviata")
    }

    fun syncPlaylistsNow() {
        container.scheduler.syncPlaylistsNow()
        message("Controllo delle playlist avviato")
    }

    /** Time of the next password reminder, if any. */
    fun nextPasswordReminder(settings: AppSettings, status: StatusSnapshot): Long? = container.reminder.nextDue(settings, status)

    fun youtubeSignedIn() = message("Accesso a YouTube effettuato")

    fun signOutYouTube() {
        container.youtubeAccount.signOut()
        myPlaylistsState.value = MyPlaylists.Idle
        message("Accesso a YouTube rimosso")
    }

    fun importYouTubeCookies(uri: Uri) = youtubeJob {
        val text = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } ?: throw VideoException("Impossibile leggere il file")
        try {
            container.youtubeAccount.saveCookies(text)
        } catch (e: IllegalArgumentException) {
            throw VideoException(e.message.orEmpty())
        }
        cookiesToDeleteState.value = uri
        "Accesso a YouTube importato dal file"
    }

    fun deleteImportedCookies(delete: Boolean) {
        val uri = cookiesToDeleteState.value ?: return
        cookiesToDeleteState.value = null
        if (!delete) return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }.getOrDefault(false)
            }
            message(if (deleted) "File dei cookie eliminato" else "Non è stato possibile eliminare il file: eliminalo a mano")
        }
    }

    fun loadMyPlaylists() {
        if (myPlaylistsState.value == MyPlaylists.Loading) return
        myPlaylistsState.value = MyPlaylists.Loading
        viewModelScope.launch {
            myPlaylistsState.value = try {
                MyPlaylists.Loaded(container.playlists.myPlaylists())
            } catch (e: VideoException) {
                MyPlaylists.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                MyPlaylists.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Adds a playlist (if not already there) and returns it; the caller then asks for its folder. */
    fun addPlaylist(id: String, title: String): PlaylistTarget {
        settings.value.youtube.playlists.firstOrNull { it.id == id }?.let { return it }
        val target = PlaylistTarget(id = id, title = if (id == PlaylistTarget.WATCH_LATER) PlaylistSync.WATCH_LATER_TITLE else title)
        update { it.copy(youtube = it.youtube.copy(playlists = it.youtube.playlists + target)) }
        return target
    }

    /** Checks a pasted link with YouTube, then adds the playlist and calls [onAdded]. */
    fun addPlaylistFromLink(link: String, onAdded: (PlaylistTarget) -> Unit) = youtubeJob {
        val id = YtDlp.playlistIdFrom(link) ?: throw VideoException("Link non valido: copia il link di condivisione della playlist")
        val listing = container.playlists.lookup(id)
        val target = addPlaylist(id, listing.title.ifBlank { id })
        onAdded(target)
        "Playlist \"${target.title}\" aggiunta (${listing.entries.size} video): scegli la cartella"
    }

    fun removePlaylist(id: String) =
        update { it.copy(youtube = it.youtube.copy(playlists = it.youtube.playlists.filterNot { p -> p.id == id })) }

    fun setPlaylistQuality(id: String, quality: VideoQuality) = update {
        it.copy(youtube = it.youtube.copy(playlists = it.youtube.playlists.map { p -> if (p.id == id) p.copy(quality = quality) else p }))
    }

    fun updateYtDlp() = youtubeJob { container.playlists.updateDownloader() }

    private fun youtubeJob(block: suspend () -> String) = viewModelScope.launch {
        if (youtubeBusyState.value) return@launch
        youtubeBusyState.value = true
        try {
            message(block())
        } catch (e: VideoException) {
            message(e.message.orEmpty())
        } catch (e: Exception) {
            message("Errore: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            youtubeBusyState.value = false
        }
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

    fun refreshCalendars(account: String) = viewModelScope.launch {
        calendarState.value = withContext(Dispatchers.IO) {
            runCatching {
                val source = CalendarSource(getApplication<Application>().contentResolver)
                val refs = source.calendars(account)
                val counts = source.eventCounts(refs.map { it.id })
                refs.map { CalendarStatus(it, counts[it.id] ?: 0) }
            }.getOrNull()
        }
    }

    fun enableCalendarSync(account: String, calendar: CalendarSource.CalendarRef) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching { CalendarSource(getApplication<Application>().contentResolver).enableSync(calendar.id, account) }
                .getOrDefault(false)
        }
        message(
            if (ok) "Sincronizzazione attivata per \"${calendar.name}\": Android scaricherà gli eventi a breve"
            else "Impossibile attivare la sincronizzazione di \"${calendar.name}\"",
        )
        refreshCalendars(account)
    }

    fun importPasswordsFromGoogle(activity: Activity) = passwordJob {
        container.vault.checkReady()
        val entries = try {
            GooglePasswordTransfer.import(activity)
        } catch (e: ImportCredentialsException) {
            throw VaultException(GooglePasswordTransfer.describe(e))
        } ?: return@passwordJob "Importazione annullata"
        container.vault.save(entries, "Gestore password di Google")
    }

    fun importPasswordsFromCsv(uri: Uri) = passwordJob {
        container.vault.checkReady()
        val text = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } ?: throw VaultException("Impossibile leggere il file")
        val entries = try {
            GooglePasswordsCsv.parse(text)
        } catch (e: IllegalArgumentException) {
            throw VaultException(e.message.orEmpty())
        }
        container.vault.save(entries, "file CSV").also { csvToDeleteState.value = uri }
    }

    fun deleteImportedCsv(delete: Boolean) {
        val uri = csvToDeleteState.value ?: return
        csvToDeleteState.value = null
        if (!delete) return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }.getOrDefault(false)
            }
            message(if (deleted) "File CSV eliminato" else "Non è stato possibile eliminare il file CSV: eliminalo a mano")
        }
    }

    fun changeDatabasePassword(current: String?, new: String) = passwordJob { container.vault.changePassword(current, new) }

    fun resetDatabasePassword(new: String) = passwordJob { container.vault.resetPassword(new) }

    private fun passwordJob(block: suspend () -> String) = viewModelScope.launch {
        if (passwordBusyState.value) return@launch
        passwordBusyState.value = true
        try {
            message(block())
        } catch (e: VaultException) {
            message(e.message.orEmpty())
        } catch (e: Exception) {
            message("Errore: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            passwordBusyState.value = false
        }
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
