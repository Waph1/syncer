package io.github.waph1.syncer

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.waph1.syncer.format.Kdbx
import io.github.waph1.syncer.security.KeystoreSecretStore
import io.github.waph1.syncer.security.SecretStore
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.SettingsBackup
import io.github.waph1.syncer.settings.SettingsRepository
import io.github.waph1.syncer.source.GoogleAuth
import io.github.waph1.syncer.sync.Notifier
import io.github.waph1.syncer.sync.PasswordReminder
import io.github.waph1.syncer.sync.PasswordVault
import io.github.waph1.syncer.sync.StatusRepository
import io.github.waph1.syncer.sync.SyncEngine
import io.github.waph1.syncer.sync.SyncScheduler
import io.github.waph1.syncer.youtube.PlaylistSync
import io.github.waph1.syncer.youtube.VideoBackend
import io.github.waph1.syncer.youtube.YouTubeAccount
import io.github.waph1.syncer.youtube.YtDlpBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manual dependency container, one per process. */
class AppContainer(
    private val context: Context,
    secrets: SecretStore = KeystoreSecretStore(context),
    kdfParams: Kdbx.KdfParams = Kdbx.KdfParams(),
    videoBackend: VideoBackend = YtDlpBackend(context),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val status = StatusRepository(context)
    val auth = GoogleAuth(context)
    val notifier = Notifier(context)
    val scheduler = SyncScheduler(context)
    val engine = SyncEngine(context, settings, status, auth, notifier)
    val backup = SettingsBackup(context)
    val vault = PasswordVault(context, settings, status, secrets, kdfParams)
    val reminder = PasswordReminder(context, settings, status, notifier)
    val youtubeAccount = YouTubeAccount(context, secrets)
    val playlists = PlaylistSync(context, settings, status, notifier, youtubeAccount, videoBackend)
    private val backupMutex = Mutex()

    init {
        settings.addListener { old, new ->
            scheduler.apply(new)
            reminder.onSettingsChanged(old, new)
            if (old.youtube.playlists != new.youtube.playlists) {
                status.retainPlaylists(new.youtube.playlists.mapTo(HashSet()) { it.id })
                // A playlist that just got a folder (new, or moved) is synced right away.
                val ready = { s: AppSettings -> s.youtube.playlists.filter { it.folderUri != null }.map { it.id to it.folderUri }.toSet() }
                if (new.setupCompleted && new.youtube.enabled && (ready(new) - ready(old)).isNotEmpty()) scheduler.syncPlaylistsNow()
            }
            if (shouldBackup(old, new)) scope.launch(Dispatchers.IO) { backupNow(new) }
        }
        // A successful password import restarts the reminder countdown.
        scope.launch {
            status.status.map { it.passwords?.lastSuccessAt }.distinctUntilChanged().drop(1).collect {
                reminder.schedule()
                notifier.clearPasswordReminder()
            }
        }
    }

    /** Writes a timestamped settings backup; returns the error message, or null on success. */
    suspend fun backupNow(settings: AppSettings = this.settings.current): String? = backupMutex.withLock {
        val folder = settings.settingsBackupFolderUri ?: return@withLock "Nessuna cartella di backup selezionata"
        runCatching { backup.write(settings, folder) }
            .onSuccess { status.recordBackup(it, null) }
            .onFailure {
                Log.w("Syncer", "Settings backup failed", it)
                status.recordBackup(null, it.message ?: it.javaClass.simpleName)
            }
            .exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }

    private fun shouldBackup(old: AppSettings, new: AppSettings): Boolean =
        new.setupCompleted && new.settingsBackupEnabled && new.settingsBackupFolderUri != null && old != new
}

open class SyncerApp : Application() {
    lateinit var container: AppContainer
        private set

    protected open fun createContainer() = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        container.notifier.createChannel()
        container.scheduler.apply(container.settings.current)
        container.reminder.schedule()
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as SyncerApp).container
