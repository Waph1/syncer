package io.github.waph1.syncer.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.waph1.syncer.MainActivity
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.FolderTarget
import io.github.waph1.syncer.settings.VideoQuality
import io.github.waph1.syncer.settings.YouTubeSettings
import io.github.waph1.syncer.testing.FakeDocumentsProvider
import io.github.waph1.syncer.testing.FakeVideoBackend
import io.github.waph1.syncer.testing.TestSyncerApp
import io.github.waph1.syncer.youtube.PlaylistListing
import io.github.waph1.syncer.youtube.VideoEntry
import kotlinx.coroutines.runBlocking
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
import java.io.File

@RunWith(AndroidJUnit4::class)
// Default (small) screen: see PasswordsScreenTest about text fields in dialogs under Robolectric.
@Config(sdk = [35], application = TestSyncerApp::class)
class ReminderAndPlaylistsScreenTest {
    @get:Rule(order = 0)
    val tmp = TemporaryFolder()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = compose.activity.applicationContext
    private val container get() = context.appContainer

    @Before
    fun setUp() {
        FakeDocumentsProvider.root = tmp.newFolder("storage")
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun textField() = compose.onNode(hasSetTextAction())

    /**
     * Waits in real time for work on background threads (compose's waitUntil advances a virtual
     * clock under Robolectric and can time out before an IO coroutine has run).
     */
    private fun awaitReal(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "Condition not met in $timeoutMillis ms" }
            Thread.sleep(20)
        }
    }

    @Test
    fun reminderFrequencyAndPlaylistFromLink() {
        container.settings.replace(
            AppSettings(
                setupCompleted = true,
                accountName = "me@gmail.com",
                periodicSyncEnabled = false,
                passwords = FolderTarget(true, FakeDocumentsProvider.treeUri("pw").toString()),
                youtube = YouTubeSettings(enabled = true, wifiOnly = false),
            ),
        )
        FakeVideoBackend.current.playlists["PLtest"] = PlaylistListing("PLtest", "Ricette", listOf(VideoEntry("v1aaaaaaaaa", "Pasta")))
        compose.waitForIdle()
        compose.onNodeWithText("Nessuna playlist: aggiungine una.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Impostazioni").performClick()

        // Password reminder: free-text frequency, validated live.
        compose.onNodeWithText("Promemoria di backup").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(container.settings.current.passwordReminder.enabled)
        compose.onNodeWithText("Frequenza del promemoria").performScrollTo().performClick()
        textField().performTextReplacement("13 mesi")
        compose.onNodeWithText("Massimo 1 anno").assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsNotEnabled()
        textField().performTextReplacement("3 settimane e mezzo")
        compose.onNodeWithText("Ogni 24 giorni e 12 ore").assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals("3 settimane e mezzo", container.settings.current.passwordReminder.every)
        compose.onNodeWithText("Ogni 24 giorni e 12 ore").performScrollTo().assertIsDisplayed()

        // A playlist from a link: checked with YouTube, then its folder is asked.
        compose.onNodeWithText("Aggiungi playlist").performScrollTo().performClick()
        textField().performTextReplacement("https://youtube.com/playlist?list=PLtest&si=abc")
        compose.onNodeWithText("Aggiungi").performClick()
        val shadow = shadowOf(compose.activity)
        awaitReal { shadow.peekNextStartedActivityForResult() != null }
        val started = shadow.nextStartedActivityForResult
        shadow.receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(FakeDocumentsProvider.treeUri("ricette")))
        compose.waitForIdle()
        container.settings.current.youtube.playlists.single().let {
            assertEquals("PLtest", it.id)
            assertEquals("Ricette", it.title)
            assertEquals(FakeDocumentsProvider.treeUri("ricette").toString(), it.folderUri)
        }

        // Quality and check frequency (once the snackbar no longer covers the buttons).
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Playlist \"Ricette\" aggiunta (1 video): scegli la cartella").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Full HD (1080p)").performScrollTo().performClick()
        compose.onNodeWithText("Solo audio (MP3)").performClick()
        compose.waitForIdle()
        assertEquals(VideoQuality.AUDIO_MP3, container.settings.current.youtube.playlists.single().quality)
        compose.onNodeWithText("Controlla le playlist").performScrollTo().performClick()
        textField().performTextReplacement("10 minuti")
        compose.onNodeWithText("Minimo 15 minuti").assertIsDisplayed()
        textField().performTextReplacement("2 ore")
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals("2 ore", container.settings.current.youtube.every)
        assertTrue(
            WorkManager.getInstance(context).getWorkInfosForUniqueWork("youtube-periodic").get()
                .any { it.state == WorkInfo.State.ENQUEUED },
        )

        // Home: the playlist and its sync result.
        compose.onNodeWithContentDescription("Indietro").performClick()
        runBlocking { container.playlists.run() }
        compose.waitForIdle()
        compose.onNodeWithText("1 video su 1 nella cartella · 1 scaricati").performScrollTo().assertIsDisplayed()
        assertTrue(File(FakeDocumentsProvider.root, "ricette/Pasta [v1aaaaaaaaa].mp3").exists())
        compose.onNodeWithText("Ogni 2 ore").performScrollTo().assertIsDisplayed()

        // Removing it keeps the files.
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithContentDescription("Rimuovere \"Ricette\"?").performScrollTo().performClick()
        compose.onNodeWithText("Rimuovi").performClick()
        compose.waitForIdle()
        assertTrue(container.settings.current.youtube.playlists.isEmpty())
        assertEquals(0, compose.onAllNodesWithText("Ricette").fetchSemanticsNodes().size)
        assertTrue(File(FakeDocumentsProvider.root, "ricette/Pasta [v1aaaaaaaaa].mp3").exists())
    }
}
