package io.github.waph1.syncer.sync

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.FolderTarget
import io.github.waph1.syncer.settings.NotesSource
import io.github.waph1.syncer.settings.SettingsCodec
import io.github.waph1.syncer.settings.SyncType
import io.github.waph1.syncer.testing.FakeCalendarProvider
import io.github.waph1.syncer.testing.FakeContactsProvider
import io.github.waph1.syncer.testing.FakeDocumentsProvider
import io.github.waph1.syncer.testing.TestSyncerApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end: fake Calendar/Contacts providers and a directory-backed SAF provider,
 * through SyncEngine, SafFolder and ManagedFolder, down to the files on "disk".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestSyncerApp::class)
class SyncPipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val container get() = app.appContainer
    private val account = "me@gmail.com"

    @Before
    fun setUp() {
        FakeDocumentsProvider.root = tmp.newFolder("storage")
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
        Robolectric.setupContentProvider(FakeCalendarProvider::class.java, FakeCalendarProvider.AUTHORITY)
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, FakeContactsProvider.AUTHORITY)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.READ_CONTACTS)
        FakeCalendarProvider.tables.clear()
        FakeContactsProvider.tables.clear()
    }

    private fun configure(transform: (AppSettings) -> AppSettings) {
        container.settings.replace(transform(AppSettings(setupCompleted = true, accountName = account, periodicSyncEnabled = false, syncOnChange = false)))
    }

    private fun sync(type: SyncType, force: Boolean = false): TypeStatus {
        runBlocking { container.engine.run(setOf(type), force) }
        return container.status.current.types.getValue(type)
    }

    private fun dir(path: String) = File(FakeDocumentsProvider.root, path)

    private fun calendarRows(title: String = "Riunione") {
        FakeCalendarProvider.tables["calendars"] = listOf(
            mapOf("_id" to 1L, "calendar_displayName" to "Lavoro", "account_name" to account, "calendar_timezone" to "Europe/Rome", "sync_events" to 1),
            mapOf("_id" to 2L, "calendar_displayName" to "Non sincronizzato", "account_name" to account, "sync_events" to 0),
        )
        FakeCalendarProvider.tables["events"] = listOf(
            mapOf(
                "_id" to 10L, "calendar_id" to 1L, "_sync_id" to "ev1", "title" to title, "dtstart" to 1_705_309_200_000L,
                "dtend" to 1_705_312_800_000L, "eventTimezone" to "Europe/Rome", "allDay" to 0, "eventStatus" to 1, "deleted" to 0,
            ),
        )
        FakeCalendarProvider.tables["reminders"] = listOf(mapOf("event_id" to 10L, "minutes" to 15, "method" to 1))
    }

    @Test
    fun calendarIsExportedOnceAndRewrittenOnlyWhenChanged() {
        calendarRows()
        configure { it.copy(calendar = FolderTarget(true, FakeDocumentsProvider.treeUri("cal").toString())) }

        val first = sync(SyncType.CALENDAR)
        assertTrue(first.message, first.ok)
        assertTrue(first.message, first.message.startsWith("1 calendari, 1 eventi (1 non sincronizzati"))
        val ics = File(dir("cal"), "Lavoro.ics")
        val text = ics.readText()
        assertTrue(text.contains("UID:ev1@google.com\r\n"))
        assertTrue(text.contains("DTSTART;TZID=Europe/Rome:20240115T100000\r\n"))
        assertTrue(text.contains("TRIGGER:-PT15M\r\n"))

        val second = sync(SyncType.CALENDAR)
        assertTrue(second.message, second.message.endsWith("nessun file modificato"))
        assertEquals(text, ics.readText()) // DTSTAMP not rewritten either

        calendarRows(title = "Riunione spostata")
        assertTrue(sync(SyncType.CALENDAR).message.endsWith("1 file aggiornati"))
        assertTrue(ics.readText().contains("SUMMARY:Riunione spostata"))
    }

    @Test
    fun emptySourceNeverWipesExistingExports() {
        calendarRows()
        configure { it.copy(calendar = FolderTarget(true, FakeDocumentsProvider.treeUri("cal").toString())) }
        sync(SyncType.CALENDAR)
        FakeCalendarProvider.tables.clear()
        val status = sync(SyncType.CALENDAR)
        assertFalse(status.ok)
        assertEquals(Problem.CONFIGURATION, status.problem)
        assertTrue(File(dir("cal"), "Lavoro.ics").exists())
    }

    @Test
    fun missingPermissionIsReported() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        configure { it.copy(calendar = FolderTarget(true, FakeDocumentsProvider.treeUri("cal").toString())) }
        val status = sync(SyncType.CALENDAR)
        assertEquals(Problem.PERMISSION, status.problem)
    }

    @Test
    fun contactsAreExportedAsSingleVcf() {
        FakeContactsProvider.tables["raw_contacts"] = listOf(mapOf("_id" to 1L, "sourceid" to "g1", "starred" to 0, "display_name" to "Mario Rossi"))
        FakeContactsProvider.tables["groups"] = listOf(mapOf("_id" to 5L, "title" to "Amici", "system_id" to null))
        FakeContactsProvider.tables["data"] = listOf(
            mapOf("raw_contact_id" to 1L, "mimetype" to "vnd.android.cursor.item/name", "data1" to "Mario Rossi", "data2" to "Mario", "data3" to "Rossi"),
            mapOf("raw_contact_id" to 1L, "mimetype" to "vnd.android.cursor.item/phone_v2", "data1" to "+39 333 1234567", "data2" to 2, "is_primary" to 1),
            mapOf("raw_contact_id" to 1L, "mimetype" to "vnd.android.cursor.item/email_v2", "data1" to "mario@example.com", "data2" to 0, "data3" to "Palestra"),
            mapOf("raw_contact_id" to 1L, "mimetype" to "vnd.android.cursor.item/contact_event", "data1" to "1980-05-10", "data2" to 3),
            mapOf("raw_contact_id" to 1L, "mimetype" to "vnd.android.cursor.item/group_membership", "data1" to 5L),
        )
        configure { it.copy(contacts = FolderTarget(true, FakeDocumentsProvider.treeUri("contatti").toString())) }
        val status = sync(SyncType.CONTACTS)
        assertTrue(status.message, status.ok)
        val vcf = File(dir("contatti"), "Contatti.vcf").readText()
        assertTrue(vcf.contains("UID:g1\r\n"))
        assertTrue(vcf.contains("N:Rossi;Mario;;;\r\n"))
        assertTrue(vcf.contains("TEL;TYPE=CELL,PREF:+39 333 1234567\r\n"))
        assertTrue(vcf.contains("item1.EMAIL;TYPE=INTERNET:mario@example.com\r\nitem1.X-ABLabel:Palestra\r\n"))
        assertTrue(vcf.contains("BDAY:1980-05-10\r\n"))
        assertTrue(vcf.contains("CATEGORIES:Amici\r\n"))
    }

    @Test
    fun takeoutZipBecomesMarkdownNotesWithAttachmentsAndStaleNotesAreRemoved() {
        val takeout = FakeDocumentsProvider.treeUri("takeout")
        writeZip(
            File(dir("takeout"), "takeout-20240101T000000Z-001.zip"),
            mapOf(
                "Takeout/Keep/Spesa.json" to """{"title":"Spesa","listContent":[{"text":"Latte","isChecked":false}],"createdTimestampUsec":1,"labels":[{"name":"Casa"}]}""",
                "Takeout/Keep/Foto.json" to """{"title":"Foto","textContent":"Guarda","createdTimestampUsec":2,"attachments":[{"filePath":"img.jpeg","mimetype":"image/jpeg"}]}""",
                "Takeout/Keep/Cestino.json" to """{"title":"Cestino","isTrashed":true,"createdTimestampUsec":3}""",
                "Takeout/Keep/img.jpg" to "JPEGDATA",
                "Takeout/Keep/Spesa.html" to "<html/>",
            ),
        )
        configure {
            it.copy(notes = FolderTarget(true, FakeDocumentsProvider.treeUri("note").toString()), notesSource = NotesSource.TAKEOUT, takeoutFolderUri = takeout.toString())
        }
        val status = sync(SyncType.NOTES)
        assertTrue(status.message, status.ok)
        assertTrue(status.message, status.message.startsWith("2 note, 1 allegati"))
        val spesa = File(dir("note"), "Spesa.md").readText()
        assertTrue(spesa.contains("- [ ] Latte"))
        assertTrue(spesa.contains("  - \"Casa\""))
        assertTrue(File(dir("note"), "Foto.md").readText().contains("![img.jpeg](attachments/img.jpeg)"))
        assertEquals("JPEGDATA", File(dir("note"), "attachments/img.jpeg").readText())
        assertFalse(File(dir("note"), "Cestino.md").exists())

        // Same export again: skipped without touching anything.
        assertTrue(sync(SyncType.NOTES).message.startsWith("Nessun nuovo export Takeout"))

        // A newer export without "Foto": its Markdown file is removed.
        Thread.sleep(10)
        writeZip(
            File(dir("takeout"), "takeout-20240201T000000Z-001.zip"),
            mapOf("Takeout/Keep/Spesa.json" to """{"title":"Spesa","textContent":"Aggiornata","createdTimestampUsec":1}"""),
        )
        File(dir("takeout"), "takeout-20240201T000000Z-001.zip").setLastModified(System.currentTimeMillis() + 60_000)
        val third = sync(SyncType.NOTES)
        assertTrue(third.message, third.ok)
        assertFalse(File(dir("note"), "Foto.md").exists())
        assertTrue(File(dir("note"), "Spesa.md").readText().contains("Aggiornata"))
    }

    @Test
    fun everySettingsChangeCreatesATimestampedBackup() {
        val backupDir = FakeDocumentsProvider.treeUri("backup").toString()
        configure { it.copy(settingsBackupEnabled = true, settingsBackupFolderUri = backupDir) }
        container.settings.update { it.copy(syncIntervalMinutes = 30) }
        container.settings.update { it.copy(syncIntervalMinutes = 45) }
        container.settings.update { it.copy(syncIntervalMinutes = 45) } // no change: no backup

        waitFor { dir("backup").listFiles().orEmpty().size >= 3 }
        Thread.sleep(300)
        val files = dir("backup").listFiles().orEmpty().sortedBy { it.name }
        assertEquals(3, files.size)
        files.forEach { assertTrue(it.name, it.name.matches(Regex("syncer-settings_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(-\\d{3})?\\.json"))) }
        val intervals = files.map { SettingsCodec.decodeImport(it.readText()).syncIntervalMinutes }.toSet()
        assertEquals(setOf(60, 30, 45), intervals)
        assertEquals(null, container.status.current.lastBackupError)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
