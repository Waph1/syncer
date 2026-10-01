package io.github.waph1.syncer.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.waph1.syncer.MainActivity
import io.github.waph1.syncer.R
import io.github.waph1.syncer.settings.SyncType

/**
 * Notifications: sync problems that need the user (permissions, authorization, folders), the
 * password backup reminder and the progress of playlist downloads.
 */
class Notifier(private val context: Context) {

    fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROBLEMS,
                context.getString(R.string.notification_channel_problems),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_problems_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_reminders_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                context.getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notification_channel_downloads_description) },
        )
    }

    /** Reminds to re-import the Google passwords; [lastImport] is the last successful import, if any. */
    fun showPasswordReminder(lastImport: Long?) {
        if (!canNotify()) return
        val text = if (lastImport == null) {
            context.getString(R.string.reminder_text_never)
        } else {
            context.getString(
                R.string.reminder_text,
                DateUtils.formatDateTime(context, lastImport, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
            )
        }
        val open = PendingIntent.getActivity(
            context, REQUEST_REMINDER_OPEN, mainIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val import = PendingIntent.getActivity(
            context,
            REQUEST_REMINDER_IMPORT,
            mainIntent().setAction(MainActivity.ACTION_IMPORT_PASSWORDS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_import_now), import)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // checked in canNotify()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_REMINDER, notification)
    }

    fun clearPasswordReminder() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_REMINDER)

    /** Ongoing notification of the playlist worker (also used as its foreground service notification). */
    fun downloadNotification(progress: PlaylistProgress?): Notification {
        val open = PendingIntent.getActivity(
            context, REQUEST_DOWNLOADS, mainIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        if (progress == null) {
            builder.setContentTitle(context.getString(R.string.youtube_notification_checking)).setProgress(0, 0, true)
        } else {
            builder
                .setContentTitle(context.getString(R.string.youtube_notification_downloading, progress.index, progress.total, progress.playlistTitle))
                .setContentText(progress.videoTitle)
                .setProgress(100, progress.percent?.toInt() ?: 0, progress.percent == null)
        }
        return builder.build()
    }

    fun updateDownloadNotification(progress: PlaylistProgress?) {
        if (!canNotify()) return
        @Suppress("MissingPermission") // checked in canNotify()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_DOWNLOADS, downloadNotification(progress))
    }

    /** A playlist sync needs the user (folder, sign-in...). */
    fun showPlaylistProblem(playlistId: String, playlistTitle: String, message: String) {
        if (!canNotify()) return
        val pending = PendingIntent.getActivity(
            context, REQUEST_PLAYLIST, mainIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title_error, playlistTitle))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        @Suppress("MissingPermission") // checked in canNotify()
        NotificationManagerCompat.from(context).notify(playlistTag(playlistId), NOTIFICATION_PLAYLIST, notification)
    }

    fun clearPlaylistProblem(playlistId: String) =
        NotificationManagerCompat.from(context).cancel(playlistTag(playlistId), NOTIFICATION_PLAYLIST)

    private fun playlistTag(id: String) = "playlist:$id"

    private fun mainIntent() = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun showProblem(type: SyncType, message: String, problem: Problem) {
        if (!canNotify()) return
        val pending = PendingIntent.getActivity(
            context, type.ordinal, mainIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.getString(
            when (problem) {
                Problem.AUTHORIZATION -> R.string.notification_title_auth
                Problem.PERMISSION -> R.string.notification_title_permission
                Problem.FOLDER -> R.string.notification_title_folder
                else -> R.string.notification_title_error
            },
            context.getString(labelOf(type)),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        @Suppress("MissingPermission") // checked in canNotify()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + type.ordinal, notification)
    }

    fun clearProblem(type: SyncType) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_BASE + type.ordinal)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    companion object {
        private const val CHANNEL_PROBLEMS = "sync_problems"
        private const val CHANNEL_REMINDERS = "reminders"
        private const val CHANNEL_DOWNLOADS = "downloads"
        private const val NOTIFICATION_BASE = 100
        private const val NOTIFICATION_REMINDER = 200
        private const val NOTIFICATION_PLAYLIST = 300
        const val NOTIFICATION_DOWNLOADS = 400
        private const val REQUEST_REMINDER_OPEN = 20
        private const val REQUEST_REMINDER_IMPORT = 21
        private const val REQUEST_DOWNLOADS = 22
        private const val REQUEST_PLAYLIST = 23

        fun labelOf(type: SyncType): Int = when (type) {
            SyncType.CALENDAR -> R.string.type_calendar
            SyncType.TASKS -> R.string.type_tasks
            SyncType.NOTES -> R.string.type_notes
            SyncType.CONTACTS -> R.string.type_contacts
        }
    }
}
