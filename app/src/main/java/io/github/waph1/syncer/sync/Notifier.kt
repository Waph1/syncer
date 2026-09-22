package io.github.waph1.syncer.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.waph1.syncer.MainActivity
import io.github.waph1.syncer.R
import io.github.waph1.syncer.settings.SyncType

/** Notifications for sync problems that need the user (permissions, authorization, folders). */
class Notifier(private val context: Context) {

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_PROBLEMS,
            context.getString(R.string.notification_channel_problems),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_channel_problems_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun showProblem(type: SyncType, message: String, problem: Problem) {
        if (!canNotify()) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, type.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
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
        private const val NOTIFICATION_BASE = 100

        fun labelOf(type: SyncType): Int = when (type) {
            SyncType.CALENDAR -> R.string.type_calendar
            SyncType.TASKS -> R.string.type_tasks
            SyncType.NOTES -> R.string.type_notes
            SyncType.CONTACTS -> R.string.type_contacts
        }
    }
}
