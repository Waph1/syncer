package io.github.waph1.syncer.youtube

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.sync.Notifier
import io.github.waph1.syncer.sync.PlaylistProgress
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [PlaylistSync]. Checking playlists is quick and silent; when there is something to
 * download the worker turns into a foreground service with a progress notification, so that
 * long downloads are not stopped after Android's 10 minutes for background work. (Android may
 * refuse this when the app is in the background and subject to battery optimization: the
 * worker then stops after 10 minutes and the next run continues where it left off.)
 */
class PlaylistWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result = coroutineScope {
        val container = applicationContext.appContainer
        val progressUpdates = launch {
            var foreground = false
            container.status.playlistProgress.collect { progress ->
                if (progress == null) return@collect
                if (!foreground) {
                    foreground = runCatching { setForeground(foregroundInfo(progress)) }.isSuccess
                }
                // Same notification id: updates the foreground notification (or a plain one).
                container.notifier.updateDownloadNotification(progress)
                delay(1_000)
            }
        }
        try {
            container.playlists.run()
        } finally {
            progressUpdates.cancel()
            NotificationManagerCompat.from(applicationContext).cancel(Notifier.NOTIFICATION_DOWNLOADS)
        }
        Result.success()
    }

    private fun foregroundInfo(progress: PlaylistProgress?): ForegroundInfo {
        val notification = applicationContext.appContainer.notifier.downloadNotification(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.NOTIFICATION_DOWNLOADS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.NOTIFICATION_DOWNLOADS, notification)
        }
    }
}
