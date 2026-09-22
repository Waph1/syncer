package io.github.waph1.syncer.ui

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.waph1.syncer.MainActivity
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.testing.FakeDocumentsProvider
import io.github.waph1.syncer.testing.TestSyncerApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestSyncerApp::class, qualifiers = "w411dp-h891dp")
class SetupWizardTest {
    @get:Rule(order = 0)
    val tmp = TemporaryFolder()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = compose.activity.applicationContext

    @Before
    fun setUp() {
        FakeDocumentsProvider.root = tmp.newFolder("storage")
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
    }

    /** Answers the next startActivityForResult (system pickers) with [data]. */
    private fun answerNextActivity(data: Intent) {
        val shadow = shadowOf(compose.activity)
        val started = shadow.nextStartedActivityForResult
        shadow.receiveResult(started.intent, Activity.RESULT_OK, data)
        compose.waitForIdle()
    }

    private fun pickFolder(path: String) =
        answerNextActivity(Intent().setData(FakeDocumentsProvider.treeUri(path)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))

    private fun next() = compose.onNodeWithText("Avanti").performClick()

    @Test
    fun setupWizardFromScratchToHome() {
        compose.onNodeWithText("Benvenuto in Syncer").assertIsDisplayed()
        next()

        // Account: required, chosen through the system account picker.
        compose.onNodeWithText("Avanti").assertIsNotEnabled()
        compose.onNodeWithText("Scegli").performClick()
        val chooser = shadowOf(compose.activity).peekNextStartedActivityForResult().intent
        assertEquals("android.accounts.ChooseTypeAndAccountActivity", chooser.component?.className)
        answerNextActivity(Intent().putExtra(AccountManager.KEY_ACCOUNT_NAME, "me@gmail.com"))
        compose.onNodeWithText("me@gmail.com").assertIsDisplayed()
        next()

        // Data: enable calendars and contacts, each with its folder.
        compose.onNodeWithText("Avanti").assertIsNotEnabled()
        compose.onNodeWithText("Calendari").performScrollTo().performClick()
        compose.onNodeWithText("Scegli").performScrollTo().performClick()
        pickFolder("Sync/Calendario")
        compose.onNodeWithText("Sync/Calendario").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Contatti").performScrollTo().performClick()
        compose.onNodeWithText("Scegli").performScrollTo().performClick()
        pickFolder("Sync/Contatti")
        compose.onNodeWithText("Avanti").assertIsEnabled()
        next()

        // Sync options: defaults.
        compose.onNodeWithText("Sincronizza alla modifica").assertIsDisplayed()
        next()

        // Settings backup: enable it and choose a folder.
        compose.onNodeWithText("Backup delle impostazioni").performClick()
        compose.onNodeWithText("Avanti").assertIsNotEnabled()
        compose.onNodeWithText("Scegli").performScrollTo().performClick()
        pickFolder("Sync/Backup")
        next()

        // Permissions, then finish.
        compose.onNodeWithText("Accesso al calendario").assertIsDisplayed()
        compose.onNodeWithText("Fine").performClick()
        compose.waitForIdle()

        val saved = context.appContainer.settings.current
        assertTrue(saved.setupCompleted)
        assertEquals("me@gmail.com", saved.accountName)
        assertTrue(saved.calendar.enabled && saved.contacts.enabled && !saved.tasks.enabled)
        assertTrue(saved.settingsBackupEnabled)

        // Home is shown (the first sync starts right away), and background work is scheduled.
        compose.waitUntil(5_000) { context.appContainer.status.running.value.isEmpty() }
        compose.onNodeWithContentDescription("Sincronizza ora").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sincronizza Calendari").assertIsDisplayed()
        // The sync reports the missing calendar permission with a button to grant it.
        compose.onAllNodesWithText("Concedi")[0].performScrollTo().assertIsDisplayed()
        val wm = WorkManager.getInstance(context)
        assertEquals(1, wm.getWorkInfosForUniqueWork("periodic-sync").get().count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(1, wm.getWorkInfosForUniqueWork("observer-calendar").get().count { !it.state.isFinished })
        assertEquals(1, wm.getWorkInfosForUniqueWork("observer-contacts").get().count { !it.state.isFinished })

        // Finishing the setup is a settings change: a backup file is written.
        compose.waitUntil(5_000) { java.io.File(FakeDocumentsProvider.root, "Sync/Backup").listFiles().orEmpty().isNotEmpty() }
    }
}
