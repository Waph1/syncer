package io.github.waph1.syncer.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.format.Kdbx
import io.github.waph1.syncer.format.KdbxException
import io.github.waph1.syncer.format.PasswordEntry
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.FolderTarget
import io.github.waph1.syncer.settings.SettingsCodec
import io.github.waph1.syncer.testing.FakeDocumentsProvider
import io.github.waph1.syncer.testing.TestSyncerApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestSyncerApp::class)
class PasswordVaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val container get() = app.appContainer
    private val vault get() = container.vault
    private val dbFile get() = File(FakeDocumentsProvider.root, "pw/${PasswordVault.FILE_NAME}")

    private val entries = listOf(
        PasswordEntry("example.com", "mario", "s3cret", "https://example.com"),
        PasswordEntry("Twitter", "@mario", "pw2", "androidapp://com.twitter.android"),
    )

    @Before
    fun setUp() {
        FakeDocumentsProvider.root = tmp.newFolder("storage")
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
        container.settings.replace(
            AppSettings(setupCompleted = true, accountName = "me@gmail.com", passwords = FolderTarget(true, FakeDocumentsProvider.treeUri("pw").toString())),
        )
    }

    @Test
    fun savesAnEncryptedDatabaseAndManagesItsPassword() = runBlocking {
        // No database password yet: nothing can be saved.
        val missing = assertThrows(VaultException::class.java) { runBlocking { vault.save(entries, "test") } }
        assertEquals("Imposta la password del database nelle impostazioni", missing.message)
        assertFalse(vault.hasPassword.value)

        vault.changePassword(null, "password-1")
        assertTrue(vault.hasPassword.value)
        assertEquals("2 password salvate in Password Google.kdbx (da test)", vault.save(entries, "test"))
        val xml = Kdbx.readXml(dbFile.readBytes(), "password-1")
        assertTrue(xml.contains("<Key>UserName</Key><Value>@mario</Value>"))
        assertFalse(xml.contains("s3cret"))
        assertEquals(true, container.status.current.passwords?.ok)

        // An empty transfer never replaces the existing database.
        val before = dbFile.readBytes()
        assertThrows(VaultException::class.java) { runBlocking { vault.save(emptyList(), "test") } }
        assertTrue(before.contentEquals(dbFile.readBytes()))
        assertEquals(false, container.status.current.passwords?.ok)

        // Changing the password requires the current one and re-encrypts the file.
        assertThrows(VaultException::class.java) { runBlocking { vault.changePassword("wrong", "password-2") } }
        vault.changePassword("password-1", "password-2")
        assertEquals(xml, Kdbx.readXml(dbFile.readBytes(), "password-2"))
        assertThrows(KdbxException::class.java) { Kdbx.readXml(dbFile.readBytes(), "password-1") }

        // A reset (forgotten password) only affects future imports.
        vault.resetPassword("password-3")
        Kdbx.readXml(dbFile.readBytes(), "password-2")
        vault.save(entries, "test")
        Kdbx.readXml(dbFile.readBytes(), "password-3")
        Unit
    }

    @Test
    fun databasePasswordIsNeverInTheSettingsBackup() = runBlocking {
        vault.changePassword(null, "segretissima")
        val json = SettingsCodec.encode(container.settings.current)
        assertFalse(json.contains("segretissima"))
        assertTrue(json.contains("\"passwords\""))
    }
}
