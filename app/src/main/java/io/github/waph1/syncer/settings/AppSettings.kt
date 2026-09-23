package io.github.waph1.syncer.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

enum class SyncType { CALENDAR, TASKS, NOTES, CONTACTS }

@Serializable
enum class NotesSource {
    /** Official Google Keep API: available only for Google Workspace accounts. */
    @SerialName("keep_api") KEEP_API,

    /** Google Takeout exports (zip or extracted "Keep" folder) placed in a folder. */
    @SerialName("takeout") TAKEOUT,
}

@Serializable
data class FolderTarget(
    val enabled: Boolean = false,
    /** Storage Access Framework tree URI. */
    val folderUri: String? = null,
)

@Serializable
data class AppSettings(
    val setupCompleted: Boolean = false,
    /** Google account (e-mail) whose data is exported. */
    val accountName: String? = null,
    val calendar: FolderTarget = FolderTarget(),
    /** Keys ([io.github.waph1.syncer.source.CalendarSource.CalendarRef.key]) of calendars not to export. */
    val excludedCalendars: Set<String> = emptySet(),
    val tasks: FolderTarget = FolderTarget(),
    val notes: FolderTarget = FolderTarget(),
    val contacts: FolderTarget = FolderTarget(),
    val notesSource: NotesSource = NotesSource.TAKEOUT,
    /** Folder where Google Takeout exports of Keep are placed. */
    val takeoutFolderUri: String? = null,
    val contactsIncludePhotos: Boolean = true,
    /** Re-export calendars and contacts as soon as they change on the device. */
    val syncOnChange: Boolean = true,
    val periodicSyncEnabled: Boolean = true,
    val syncIntervalMinutes: Int = 60,
    /** Delete exported files whose source no longer exists (e.g. a deleted note). */
    val deleteRemovedFiles: Boolean = true,
    /** Save a timestamped copy of the settings to [settingsBackupFolderUri] on every change. */
    val settingsBackupEnabled: Boolean = false,
    val settingsBackupFolderUri: String? = null,
) {
    fun target(type: SyncType): FolderTarget = when (type) {
        SyncType.CALENDAR -> calendar
        SyncType.TASKS -> tasks
        SyncType.NOTES -> notes
        SyncType.CONTACTS -> contacts
    }

    fun withTarget(type: SyncType, target: FolderTarget): AppSettings = when (type) {
        SyncType.CALENDAR -> copy(calendar = target)
        SyncType.TASKS -> copy(tasks = target)
        SyncType.NOTES -> copy(notes = target)
        SyncType.CONTACTS -> copy(contacts = target)
    }

    fun enabledTypes(): Set<SyncType> = SyncType.entries.filter { target(it).enabled }.toSet()

    companion object {
        /** WorkManager does not run periodic work more often than every 15 minutes. */
        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_INTERVAL_MINUTES = 7 * 24 * 60
    }
}

/** File format used for settings backups and import. */
@Serializable
data class SettingsFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val createdAt: String,
    val appVersion: String? = null,
    val settings: AppSettings,
) {
    companion object {
        const val FORMAT = "syncer-settings"
    }
}

object SettingsCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }

    fun encode(settings: AppSettings): String = json.encodeToString(AppSettings.serializer(), settings)

    fun decode(text: String): AppSettings = json.decodeFromString(AppSettings.serializer(), text)

    fun encodeFile(file: SettingsFile): String = json.encodeToString(SettingsFile.serializer(), file)

    /**
     * Reads a settings backup. Accepts both the wrapped [SettingsFile] format and a bare
     * [AppSettings] object. Throws [IllegalArgumentException] for unrelated JSON.
     */
    fun decodeImport(text: String): AppSettings {
        val element = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IllegalArgumentException("Il file non è un JSON valido", it) }
        val inner: JsonObject = when {
            element["format"]?.toString()?.trim('"') == SettingsFile.FORMAT && element["settings"] is JsonObject ->
                element["settings"]!!.jsonObject
            element.keys.any { it in KNOWN_KEYS } -> element
            else -> throw IllegalArgumentException("Il file non contiene impostazioni di Syncer")
        }
        return json.decodeFromJsonElement(AppSettings.serializer(), inner)
    }

    private val KNOWN_KEYS = setOf("accountName", "calendar", "tasks", "notes", "contacts", "setupCompleted")
}
