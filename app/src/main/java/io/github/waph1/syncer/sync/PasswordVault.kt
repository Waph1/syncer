package io.github.waph1.syncer.sync

import android.content.Context
import io.github.waph1.syncer.format.Kdbx
import io.github.waph1.syncer.format.KdbxException
import io.github.waph1.syncer.format.KeePassXml
import io.github.waph1.syncer.format.PasswordEntry
import io.github.waph1.syncer.security.SecretStore
import io.github.waph1.syncer.settings.SettingsRepository
import io.github.waph1.syncer.storage.ManagedFolder
import io.github.waph1.syncer.storage.SafFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Locale

/** A problem the user can fix (missing password or folder, empty import...). */
class VaultException(message: String) : Exception(message)

/**
 * Writes imported Google passwords into an encrypted KeePass database ([FILE_NAME]) and manages
 * its password, which is kept only in the [SecretStore].
 */
class PasswordVault(
    private val context: Context,
    private val settings: SettingsRepository,
    private val status: StatusRepository,
    private val secrets: SecretStore,
    private val kdfParams: Kdbx.KdfParams = Kdbx.KdfParams(),
) {
    private val mutex = Mutex()
    private val hasPasswordState = MutableStateFlow(secrets.get(KEY_PASSWORD) != null)
    val hasPassword: StateFlow<Boolean> = hasPasswordState.asStateFlow()

    /** Checks that an import can be saved, before asking the user to transfer anything. */
    fun checkReady() {
        val target = settings.current.passwords
        if (!target.enabled) throw VaultException("Attiva le password nelle impostazioni")
        if (target.folderUri == null) throw VaultException("Scegli la cartella del database nelle impostazioni")
        if (secrets.get(KEY_PASSWORD) == null) throw VaultException("Imposta la password del database nelle impostazioni")
    }

    /** Saves [entries] as the new database and returns a summary. Never overwrites with an empty import. */
    suspend fun save(entries: List<PasswordEntry>, source: String): String = mutex.withLock {
        try {
            checkReady()
            if (entries.isEmpty()) throw VaultException("Nessuna password ricevuta: il database esistente non è stato modificato")
            val password = secrets.get(KEY_PASSWORD)!!
            val sorted = entries.sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.username.lowercase(Locale.ROOT) }))
            val bytes = withContext(Dispatchers.Default) {
                Kdbx.create(password, kdfParams) { protector -> KeePassXml.build(DATABASE_NAME, sorted, Instant.now(), protector) }
            }
            withContext(Dispatchers.IO) {
                val folder = SafFolder.of(context, settings.current.passwords.folderUri!!)
                ManagedFolder(folder, File(context.noBackupFilesDir, "manifests/passwords.json"), deleteStale = false)
                    .begin().apply { put(FILE_NAME, bytes) }.finish()
                verify(folder, bytes)
            }
            val message = "${entries.size} password salvate in $FILE_NAME (da $source)"
            status.recordPasswords(true, message)
            message
        } catch (e: VaultException) {
            status.recordPasswords(false, e.message.orEmpty())
            throw e
        } catch (e: Exception) {
            val message = "Salvataggio non riuscito: ${e.message ?: e.javaClass.simpleName}"
            status.recordPasswords(false, message)
            throw VaultException(message)
        }
    }

    /**
     * Sets the database password. With a password already set, [current] must match it and the
     * existing database is re-encrypted with the new one.
     */
    suspend fun changePassword(current: String?, new: String): String = mutex.withLock {
        val stored = secrets.get(KEY_PASSWORD)
        if (stored != null && current != stored) throw VaultException("La password attuale non è corretta")
        val reencrypted = if (stored != null) reencrypt(stored, new) else false
        secrets.put(KEY_PASSWORD, new)
        hasPasswordState.value = true
        if (reencrypted) "Password cambiata: $FILE_NAME è stato cifrato con la nuova password" else "Password del database impostata"
    }

    /** For a forgotten password: sets a new one; the existing file keeps the old one until the next import. */
    suspend fun resetPassword(new: String): String = mutex.withLock {
        secrets.put(KEY_PASSWORD, new)
        hasPasswordState.value = true
        "Nuova password impostata: sarà usata dalla prossima importazione"
    }

    private suspend fun reencrypt(old: String, new: String): Boolean = withContext(Dispatchers.IO) {
        val uri = settings.current.passwords.folderUri ?: return@withContext false
        val folder = runCatching { SafFolder.of(context, uri) }.getOrNull() ?: return@withContext false
        val entry = runCatching { folder.list().firstOrNull { !it.isDirectory && it.name == FILE_NAME } }.getOrNull()
            ?: return@withContext false
        val current = folder.open(entry.uri).use { it.readBytes() }
        val updated = try {
            withContext(Dispatchers.Default) { Kdbx.changePassword(current, old, new, kdfParams) }
        } catch (e: KdbxException) {
            throw VaultException("Impossibile ricifrare $FILE_NAME: ${e.message}")
        }
        folder.write(entry.uri, updated)
        verify(folder, updated)
        true
    }

    /** Reads the file back and compares it byte by byte with what was written. */
    private fun verify(folder: SafFolder, expected: ByteArray) {
        val entry = folder.list().firstOrNull { !it.isDirectory && it.name == FILE_NAME }
            ?: throw VaultException("$FILE_NAME non trovato dopo il salvataggio")
        val actual = folder.open(entry.uri).use { it.readBytes() }
        if (!actual.contentEquals(expected)) throw VaultException("Verifica di $FILE_NAME non riuscita: riprova")
    }

    companion object {
        const val FILE_NAME = "Password Google.kdbx"
        const val DATABASE_NAME = "Password Google"
        private const val KEY_PASSWORD = "kdbx_password"
    }
}
