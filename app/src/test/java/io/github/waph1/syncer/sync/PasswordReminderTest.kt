package io.github.waph1.syncer.sync

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.waph1.syncer.MainActivity
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.FolderTarget
import io.github.waph1.syncer.settings.ReminderSettings
import io.github.waph1.syncer.testing.TestSyncerApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestSyncerApp::class)
class PasswordReminderTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val container get() = app.appContainer
    private val twoWeeks = Duration.ofDays(14).toMillis()

    private val settings = AppSettings(
        setupCompleted = true,
        passwords = FolderTarget(true, "content://folder"),
        passwordReminder = ReminderSettings(enabled = true, every = "2 settimane"),
    )

    private fun notifications(): List<Notification> =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    fun dueOneIntervalAfterTheLaterOfImportAndReminder() {
        val reminder = container.reminder
        assertNull(reminder.nextDue(settings.copy(passwordReminder = ReminderSettings(enabled = false)), StatusSnapshot()))
        assertNull(reminder.nextDue(settings.copy(passwords = FolderTarget(false)), StatusSnapshot()))
        assertNull(reminder.nextDue(settings.copy(passwordReminder = ReminderSettings(true, "boh")), StatusSnapshot()))
        assertEquals(1_000 + twoWeeks, reminder.nextDue(settings, StatusSnapshot(passwordReminderAnchor = 1_000)))
        assertEquals(
            5_000 + twoWeeks,
            reminder.nextDue(settings, StatusSnapshot(passwords = TypeStatus(lastSuccessAt = 5_000), passwordReminderAnchor = 1_000)),
        )
    }

    @Test
    fun remindsWithAnImportActionAndRestartsAfterAnImport() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val before = System.currentTimeMillis()

        // Switching the reminder on starts counting from now and schedules it two weeks later.
        container.settings.replace(settings)
        val anchor = container.status.current.passwordReminderAnchor!!
        assertTrue(anchor >= before)
        val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork("password-reminder").get().single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertTrue(work.initialDelayMillis in (twoWeeks - 60_000)..twoWeeks)

        // Not yet due: nothing. Due: a notification with "Importa ora", counting again from now.
        container.reminder.fire(now = anchor + twoWeeks - 3_600_000)
        assertTrue(notifications().isEmpty())
        container.reminder.fire(now = anchor + twoWeeks)
        val notification = notifications().single()
        assertEquals("Backup delle password", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Importa ora", notification.actions.single().title)
        assertEquals(MainActivity.ACTION_IMPORT_PASSWORDS, shadowOf(notification.actions.single().actionIntent).savedIntent.action)
        assertEquals(anchor + twoWeeks, container.status.current.passwordReminderAnchor)

        // An import removes the reminder and the next one is due two weeks after it.
        container.status.setPasswordReminderAnchor(anchor) // back from the simulated future
        container.status.recordPasswords(true, "10 password salvate")
        val deadline = System.currentTimeMillis() + 5_000
        while (notifications().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(notifications().isEmpty())
        val imported = container.status.current.passwords!!.lastSuccessAt!!
        assertEquals(imported + twoWeeks, container.reminder.nextDue())

        // Switching it off cancels the work.
        container.settings.update { it.copy(passwordReminder = it.passwordReminder.copy(enabled = false)) }
        assertTrue(
            WorkManager.getInstance(app).getWorkInfosForUniqueWork("password-reminder").get()
                .all { it.state == WorkInfo.State.CANCELLED || it.state.isFinished },
        )
    }
}
