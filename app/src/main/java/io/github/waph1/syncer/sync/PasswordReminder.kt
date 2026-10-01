package io.github.waph1.syncer.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.format.DurationText
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * Reminds the user to re-import the Google passwords (Google allows no automatic export).
 * The next reminder is due one interval after the later of the last successful import and the
 * last reminder (or the moment the reminder was switched on).
 */
class PasswordReminder(
    context: Context,
    private val settings: SettingsRepository,
    private val status: StatusRepository,
    private val notifier: Notifier,
) {
    private val workManager = WorkManager.getInstance(context)

    /** Time (epoch millis) of the next reminder, or null when no reminder applies. */
    fun nextDue(s: AppSettings = settings.current, st: StatusSnapshot = status.current): Long? {
        if (!s.setupCompleted || !s.passwords.enabled || !s.passwordReminder.enabled) return null
        val every = DurationText.durationOf(s.passwordReminder.every) ?: return null
        val anchor = maxOf(st.passwords?.lastSuccessAt ?: 0L, st.passwordReminderAnchor ?: 0L)
        return anchor + every.toMillis()
    }

    /** Starts counting from now when the reminder is switched on, then reschedules. */
    fun onSettingsChanged(old: AppSettings, new: AppSettings) {
        if (isOn(new) && !isOn(old)) status.setPasswordReminderAnchor(System.currentTimeMillis())
        schedule(new)
    }

    fun schedule(s: AppSettings = settings.current, fromWorker: Boolean = false) {
        val due = nextDue(s)
        if (due == null) {
            if (!fromWorker) workManager.cancelUniqueWork(WORK)
            return
        }
        val request = OneTimeWorkRequestBuilder<PasswordReminderWorker>()
            .setInitialDelay((due - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        // From the worker itself, REPLACE would cancel the running job: queue after it instead.
        workManager.enqueueUniqueWork(WORK, if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
    }

    /** Shows the reminder if due (WorkManager may run late, never much early), then schedules the next. */
    fun fire(now: Long = System.currentTimeMillis()) {
        val due = nextDue() ?: return
        if (now + SLACK_MS >= due) {
            notifier.showPasswordReminder(status.current.passwords?.lastSuccessAt)
            status.setPasswordReminderAnchor(now)
        }
        schedule(fromWorker = true)
    }

    private fun isOn(s: AppSettings) = s.passwords.enabled && s.passwordReminder.enabled

    private companion object {
        const val WORK = "password-reminder"
        const val SLACK_MS = 60_000L
    }
}

class PasswordReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.appContainer.reminder.fire()
        return Result.success()
    }
}
