package io.github.waph1.syncer.ui

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.waph1.syncer.MainActivity
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.format.Kdbx
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.FolderTarget
import io.github.waph1.syncer.sync.PasswordVault
import io.github.waph1.syncer.testing.FakeDocumentsProvider
import io.github.waph1.syncer.testing.TestSyncerApp
import org.junit.Assert.assertFalse
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
// Default (small) screen: under Robolectric, text fields inside dialogs never settle on larger
// qualifiers such as w411dp-h891dp (even a bare Material3 AlertDialog + OutlinedTextField).
@Config(sdk = [35], application = TestSyncerApp::class)
class PasswordsScreenTest {
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
    fun setPasswordThenImportCsvFromHomeAndDeleteIt() {
        context.appContainer.settings.replace(
            AppSettings(setupCompleted = true, accountName = "me@gmail.com", passwords = FolderTarget(true, FakeDocumentsProvider.treeUri("pw").toString())),
        )
        compose.waitForIdle()

        // Home: the passwords card; importing without a database password explains what to do.
        compose.onNodeWithText("Nessuna importazione ancora").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Importa dal Gestore password di Google").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Imposta la password del database nelle impostazioni").fetchSemanticsNodes().isNotEmpty()
        }

        // Settings: set the database password.
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithText("Password del database").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Imposta").performScrollTo().performClick()
        compose.onNodeWithText("Nuova password").performTextReplacement("password-lunga")
        compose.onNodeWithText("Conferma la nuova password").performTextReplacement("password-lunga")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { context.appContainer.vault.hasPassword.value }
        compose.onNodeWithText("Impostata (salvata cifrata nel Keystore del telefono)").performScrollTo().assertIsDisplayed()

        // Import a Google Password Manager CSV and delete it afterwards.
        val csv = File(FakeDocumentsProvider.root, "download/Password Google.csv").apply {
            parentFile!!.mkdirs()
            writeText("name,url,username,password,note\nexample.com,https://example.com/,mario,s3cret,\n")
        }
        val csvUri = DocumentsContract.buildDocumentUriUsingTree(FakeDocumentsProvider.treeUri("download"), "download/${csv.name}")
        // Buttons are disabled while the password job finishes; the snackbar may cover them.
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Importa da file CSV").assertIsEnabled() }.isSuccess }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Password del database impostata").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Importa da file CSV").performScrollTo().performClick()
        val shadow = shadowOf(compose.activity)
        val started = shadow.nextStartedActivityForResult
        shadow.receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(csvUri))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Eliminare il file CSV?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Elimina").performClick()
        compose.waitUntil(5_000) { !csv.exists() }

        val db = File(FakeDocumentsProvider.root, "pw/${PasswordVault.FILE_NAME}")
        val xml = Kdbx.readXml(db.readBytes(), "password-lunga")
        assertTrue(xml.contains("<Key>UserName</Key><Value>mario</Value>"))
        assertFalse(xml.contains("s3cret"))
    }
}
