package io.github.waph1.syncer.ui

import androidx.compose.ui.test.assertIsDisplayed
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
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.FolderTarget
import io.github.waph1.syncer.settings.NotesSource
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
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestSyncerApp::class, qualifiers = "w411dp-h891dp")
class SettingsScreenTest {
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

    @Test
    fun settingsScreenChangesAreSavedAndRescheduled() {
        val container = context.appContainer
        container.settings.replace(
            AppSettings(
                setupCompleted = true,
                accountName = "me@gmail.com",
                calendar = FolderTarget(true, FakeDocumentsProvider.treeUri("cal").toString()),
                tasks = FolderTarget(true, FakeDocumentsProvider.treeUri("tasks").toString()),
                notes = FolderTarget(true, FakeDocumentsProvider.treeUri("notes").toString()),
                contacts = FolderTarget(true, FakeDocumentsProvider.treeUri("contacts").toString()),
                notesSource = NotesSource.TAKEOUT,
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Attività (Google Tasks)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Impostazioni").performClick()

        compose.onNodeWithText("Intervallo").performScrollTo().performClick()
        compose.onNodeWithText("30 min").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(30, container.settings.current.syncIntervalMinutes)

        compose.onNodeWithText("Sincronizzazione periodica").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(false, container.settings.current.periodicSyncEnabled)
        val wm = WorkManager.getInstance(context)
        assertTrue(wm.getWorkInfosForUniqueWork("periodic-sync").get().all { it.state == WorkInfo.State.CANCELLED })

        compose.onNodeWithText("Impronta SHA-1 del certificato").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rifai configurazione").performScrollTo().performClick()
        compose.onNodeWithText("Benvenuto in Syncer").assertIsDisplayed()
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithText("Impostazioni").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("Cartella degli export Takeout").fetchSemanticsNodes().size)
    }
}
