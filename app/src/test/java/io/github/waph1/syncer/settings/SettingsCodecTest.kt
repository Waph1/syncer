package io.github.waph1.syncer.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsCodecTest {
    private val sample = AppSettings(
        setupCompleted = true,
        accountName = "me@gmail.com",
        calendar = FolderTarget(true, "content://com.android.externalstorage.documents/tree/primary%3ASync%2FCalendario"),
        tasks = FolderTarget(true, "content://tree/tasks"),
        notes = FolderTarget(false, null),
        contacts = FolderTarget(true, "content://tree/contacts"),
        notesSource = NotesSource.KEEP_API,
        syncIntervalMinutes = 30,
        settingsBackupEnabled = true,
        settingsBackupFolderUri = "content://tree/backup",
    )

    @Test
    fun roundTrip() {
        assertEquals(sample, SettingsCodec.decode(SettingsCodec.encode(sample)))
    }

    @Test
    fun importsWrappedBackupFile() {
        val text = SettingsCodec.encodeFile(SettingsFile(createdAt = "2026-09-22T21:33:05Z", appVersion = "1.0.0", settings = sample))
        assertEquals(sample, SettingsCodec.decodeImport(text))
    }

    @Test
    fun importsBareSettingsAndIgnoresUnknownKeys() {
        val text = """{"accountName":"x@gmail.com","futureOption":true,"tasks":{"enabled":true}}"""
        val imported = SettingsCodec.decodeImport(text)
        assertEquals("x@gmail.com", imported.accountName)
        assertEquals(FolderTarget(enabled = true), imported.tasks)
        assertEquals(AppSettings().syncIntervalMinutes, imported.syncIntervalMinutes)
    }

    @Test
    fun unknownEnumFallsBackToDefault() {
        val imported = SettingsCodec.decodeImport("""{"accountName":"x@gmail.com","notesSource":"something_new"}""")
        assertEquals(NotesSource.TAKEOUT, imported.notesSource)
    }

    @Test
    fun rejectsUnrelatedJson() {
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.decodeImport("""{"hello":"world"}""") }
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.decodeImport("not json") }
    }
}
