package io.github.waph1.syncer.sync

import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.waph1.syncer.format.DurationText
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.SyncType
import io.github.waph1.syncer.youtube.PlaylistWorker
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Schedules syncs with WorkManager:
 * - calendars and contacts: a job triggered by changes in the Calendar/Contacts providers
 *   (JobScheduler content-URI triggers, no background service needed), re-armed after each run;
 * - everything: a periodic job every N minutes (minimum 15, an Android limit), also the only
 *   option for Google Tasks and Keep, which offer no change notifications;
 * - YouTube playlists: their own periodic job (YouTube does not notify playlist changes), only
 *   on Wi-Fi if so chosen.
 */
class SyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    /** Aligns scheduled work with the settings. Safe to call often. */
    fun apply(settings: AppSettings) {
        val active = settings.setupCompleted && settings.accountName != null
        val types = if (active) settings.enabledTypes() else emptySet()

        if (active && settings.periodicSyncEnabled && types.isNotEmpty()) {
            val minutes = settings.syncIntervalMinutes.coerceIn(AppSettings.MIN_INTERVAL_MINUTES, AppSettings.MAX_INTERVAL_MINUTES)
            val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(SyncWorker.KEY_REASON to SyncWorker.REASON_PERIODIC))
                .addTag(TAG)
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            workManager.cancelUniqueWork(PERIODIC)
        }

        for (type in OBSERVED) {
            if (active && settings.syncOnChange && type in types) {
                enqueueObserver(type, ExistingWorkPolicy.KEEP)
            } else {
                workManager.cancelUniqueWork(observerName(type))
            }
        }

        val youtube = settings.youtube
        if (settings.setupCompleted && youtube.enabled && youtube.playlists.isNotEmpty()) {
            val every = DurationText.durationOf(youtube.every, DurationText.FIFTEEN_MINUTES, DurationText.ONE_YEAR)
                ?: DEFAULT_PLAYLIST_INTERVAL
            val request = PeriodicWorkRequestBuilder<PlaylistWorker>(every.toMinutes(), TimeUnit.MINUTES)
                .setConstraints(playlistConstraints(youtube.wifiOnly))
                .addTag(TAG)
                .build()
            workManager.enqueueUniquePeriodicWork(YOUTUBE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            workManager.cancelUniqueWork(YOUTUBE_PERIODIC)
            workManager.cancelUniqueWork(YOUTUBE_MANUAL)
        }
    }

    /** Checks the playlists now (downloads wait for Wi-Fi if so chosen: the sync says so). */
    fun syncPlaylistsNow() {
        val request = OneTimeWorkRequestBuilder<PlaylistWorker>()
            .setConstraints(playlistConstraints(wifiOnly = false))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(YOUTUBE_MANUAL, ExistingWorkPolicy.KEEP, request)
    }

    private fun playlistConstraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    /** Called by the observer job itself when it finishes, to wait for the next change. */
    fun rearmObserver(type: SyncType, settings: AppSettings) {
        if (type !in OBSERVED || !settings.syncOnChange || !settings.target(type).enabled) return
        // APPEND_OR_REPLACE: the running job has the same unique name and must not be cancelled.
        enqueueObserver(type, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    fun syncNow(types: Set<SyncType> = SyncType.entries.toSet()) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(
                workDataOf(
                    SyncWorker.KEY_REASON to SyncWorker.REASON_MANUAL,
                    SyncWorker.KEY_TYPES to types.map { it.name }.toTypedArray(),
                    SyncWorker.KEY_FORCE to true,
                ),
            )
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(MANUAL, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun enqueueObserver(type: SyncType, policy: ExistingWorkPolicy) {
        val uri: Uri = when (type) {
            SyncType.CALENDAR -> CalendarContract.CONTENT_URI
            SyncType.CONTACTS -> ContactsContract.AUTHORITY_URI
            else -> return
        }
        val constraints = Constraints.Builder()
            .addContentUriTrigger(uri, true)
            // Wait for edits to settle (the sync adapter writes in bursts), but not too long.
            .setTriggerContentUpdateDelay(Duration.ofSeconds(15))
            .setTriggerContentMaxDelay(Duration.ofMinutes(2))
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    SyncWorker.KEY_REASON to SyncWorker.REASON_OBSERVER,
                    SyncWorker.KEY_TYPES to arrayOf(type.name),
                ),
            )
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(observerName(type), policy, request)
    }

    private fun observerName(type: SyncType) = "observer-${type.name.lowercase()}"

    companion object {
        private const val TAG = "syncer"
        private const val PERIODIC = "periodic-sync"
        private const val MANUAL = "manual-sync"
        private const val YOUTUBE_PERIODIC = "youtube-periodic"
        private const val YOUTUBE_MANUAL = "youtube-manual"
        val DEFAULT_PLAYLIST_INTERVAL: Duration = Duration.ofHours(6)
        private val OBSERVED = listOf(SyncType.CALENDAR, SyncType.CONTACTS)
    }
}
