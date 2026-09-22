package io.github.waph1.syncer.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import io.github.waph1.syncer.BuildConfig
import io.github.waph1.syncer.storage.SafFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Persists [AppSettings] as JSON in private SharedPreferences and notifies listeners on change. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    private val listeners = CopyOnWriteArrayList<(old: AppSettings, new: AppSettings) -> Unit>()

    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    fun addListener(listener: (old: AppSettings, new: AppSettings) -> Unit) {
        listeners += listener
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val old: AppSettings
        val new: AppSettings
        synchronized(this) {
            old = state.value
            new = transform(old)
            if (new == old) return
            prefs.edit { putString(KEY, SettingsCodec.encode(new)) }
            state.value = new
        }
        listeners.forEach { it(old, new) }
    }

    fun replace(settings: AppSettings) = update { settings }

    private fun load(): AppSettings = prefs.getString(KEY, null)
        ?.let { runCatching { SettingsCodec.decode(it) }.getOrNull() }
        ?: AppSettings()

    private companion object {
        const val KEY = "settings_json"
    }
}

/** Writes timestamped settings backups and reads backups for import. */
class SettingsBackup(private val context: Context) {

    /** Saves a new backup file and returns its name. Existing backups are never overwritten. */
    fun write(settings: AppSettings, folderUri: String): String {
        val folder = SafFolder.of(context, folderUri)
        val existing = folder.list().map { it.name.lowercase(Locale.ROOT) }.toSet()
        val now = LocalDateTime.now()
        val candidates = sequenceOf("syncer-settings_${FILE_TIME.format(now)}", "syncer-settings_${FILE_TIME_MILLIS.format(now)}") +
            generateSequence(2) { it + 1 }.map { "syncer-settings_${FILE_TIME_MILLIS.format(now)}-$it" }
        val name = candidates.map { "$it.json" }.first { it.lowercase(Locale.ROOT) !in existing }
        val file = SettingsFile(createdAt = Instant.now().toString(), appVersion = BuildConfig.VERSION_NAME, settings = settings)
        val uri = folder.createFile(name)
        folder.write(uri, SettingsCodec.encodeFile(file).toByteArray(Charsets.UTF_8))
        return folder.displayName(uri) ?: name
    }

    fun read(uri: Uri): AppSettings {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("Impossibile leggere il file")
        return SettingsCodec.decodeImport(text)
    }

    private companion object {
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)
        val FILE_TIME_MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ROOT)
    }
}
