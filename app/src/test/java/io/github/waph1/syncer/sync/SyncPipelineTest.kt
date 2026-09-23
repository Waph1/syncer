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
import io.github.waph1.syncer.source.CalendarSource
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

    private fun event(id: Long, calendarId: Long, title: String) = mapOf(
        "_id" to id, "calendar_id" to calendarId, "_sync_id" to "ev$id", "title" to title, "dtstart" to 1_705_309_200_000L,
        "dtend" to 1_705_312_800_000L, "eventTimezone" to "Europe/Rome", "allDay" to 0, "eventStatus" to 1, "deleted" to 0,
    )

    private fun calendar(id: Long, name: String, sync: Int?) = mapOf(
        "_id" to id, "calendar_displayName" to name, "account_name" to account, "calendar_timezone" to "Europe/Rome",
        "sync_events" to sync, "_sync_id" to "cal$id@group.calendar.google.com", "visible" to 0,
    )

    private fun calendarRows(title: String = "Riunione") {
        FakeCalendarProvider.tables["calendars"] = listOf(
            calendar(1, "Lavoro", 1),
            calendar(2, "Non sincronizzato", 0),
        )
        FakeCalendarProvider.tables["events"] = listOf(event(10, 1, title))
        FakeCalendarProvider.tables["reminders"] = listOf(mapOf("event_id" to 10L, "minutes" to 15, "method" to 1))
    }

    @Test
    fun calendarIsExportedOnceAndRewrittenOnlyWhenChanged() {
        calendarRows()
        configure { it.copy(calendar = FolderTarget(true, FakeDocumentsProvider.treeUri("cal").toString())) }

        val first = sync(SyncType.CALENDAR)
        assertTrue(first.message, first.ok)
        assertTrue(first.message, first.message.startsWith("1 calendari, 1 eventi — 1 file aggiornati"))
        // Calendars whose sync is off on the device are named, so the user can fix them.
        assertTrue(first.message, first.message.endsWith("Non sincronizzati su questo telefono (Impostazioni › Dati e cartelle › Calendari): Non sincronizzato"))
        assertEquals(listOf("Lavoro.ics"), dir("cal").list()!!.toList())
        val ics = File(dir("cal"), "Lavoro.ics")
        val text = ics.readText()
        assertTrue(text.contains("UID:ev10@google.com\r\n"))
        assertTrue(text.contains("DTSTART;TZID=Europe/Rome:20240115T100000\r\n"))
        assertTrue(text.contains("TRIGGER:-PT15M\r\n"))

        val second = sync(SyncType.CALENDAR)
        assertTrue(second.message, second.message.contains("nessun file modificato"))
        assertEquals(text, ics.readText()) // DTSTAMP not rewritten either

        calendarRows(title = "Riunione spostata")
        assertTrue(sync(SyncType.CALENDAR).message.contains("— 1 file aggiornati"))
        assertTrue(ics.readText().contains("SUMMARY:Riunione spostata"))
    }

    @Test
    fun calendarSelectionHonoursSyncStateEventsAndExclusions() {
        FakeCalendarProvider.tables["calendars"] = listOf(
            calendar(1, "Lavoro", 1),
            calendar(2, "Compleanni", 0), // sync off, but events still on the device: exported
            calendar(3, "Festivita", 0), // sync off, no events: skipped, previous file kept
            calendar(4, "Stato ignoto", null),
            calendar(5, "Escluso", 1),
        )
        FakeCalendarProvider.tables["events"] = listOf(event(10, 1, "A"), event(20, 2, "B"), event(40, 4, "D"), event(50, 5, "E"))
        val folder = FakeDocumentsProvider.treeUri("cal").toString()
        configure { it.copy(calendar = FolderTarget(true, folder)) }
        sync(SyncType.CALENDAR)
        assertEquals(setOf("Compleanni.ics", "Escluso.ics", "Lavoro.ics", "Stato ignoto.ics"), dir("cal").list()!!.toSet())

        // A previously exported calendar whose events disappear from the device is kept as is.
        File(dir("cal"), "Festivita.ics").writeText("vecchio export")
        container.settings.update { it.copy(excludedCalendars = setOf("cal5@group.calendar.google.com")) }
        val status = sync(SyncType.CALENDAR)
        assertTrue(status.message, status.ok)
        assertTrue(status.message, status.message.startsWith("3 calendari, 3 eventi"))
        assertTrue(status.message, status.message.contains("Non sincronizzati su questo telefono (Impostazioni › Dati e cartelle › Calendari): Festivita"))
        assertTrue(status.message, status.message.endsWith("Esclusi dall'esportazione: 1"))
        // The excluded calendar's export is removed; the user's own file is untouched.
        assertEquals(setOf("Compleanni.ics", "Festivita.ics", "Lavoro.ics", "Stato ignoto.ics"), dir("cal").list()!!.toSet())
        assertEquals("vecchio export", File(dir("cal"), "Festivita.ics").readText())
    }

    @Test
    fun enablingSyncUpdatesTheCalendarProvider() {
        FakeCalendarProvider.tables["calendars"] = listOf(calendar(3, "Festivita", 0))
        val source = CalendarSource(app.contentResolver)
        assertEquals(false, source.calendars(account).single().syncEvents)
        assertTrue(source.enableSync(3, account))
        val updated = source.calendars(account).single()
        assertEquals(true, updated.syncEvents)
        assertEquals("cal3@group.calendar.google.com", updated.key)
        assertFalse(updated.visible)
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
