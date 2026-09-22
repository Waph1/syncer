package io.github.waph1.syncer.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.waph1.syncer.format.FileNames
import io.github.waph1.syncer.format.IcsWriter
import io.github.waph1.syncer.format.KeepNote
import io.github.waph1.syncer.format.MarkdownWriter
import io.github.waph1.syncer.format.TodoTxtWriter
import io.github.waph1.syncer.format.VCardWriter
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.NotesSource
import io.github.waph1.syncer.settings.SettingsRepository
import io.github.waph1.syncer.settings.SyncType
import io.github.waph1.syncer.source.ApiException
import io.github.waph1.syncer.source.AuthConfigurationException
import io.github.waph1.syncer.source.AuthorizationRequiredException
import io.github.waph1.syncer.source.CalendarSource
import io.github.waph1.syncer.source.ContactsSource
import io.github.waph1.syncer.source.GoogleApiClient
import io.github.waph1.syncer.source.GoogleAuth
import io.github.waph1.syncer.source.KeepApiSource
import io.github.waph1.syncer.source.KeepTakeoutParser
import io.github.waph1.syncer.source.KeepTakeoutSource
import io.github.waph1.syncer.source.TasksSource
import io.github.waph1.syncer.storage.FolderAccessException
import io.github.waph1.syncer.storage.ManagedFolder
import io.github.waph1.syncer.storage.SafFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A sync failure with a user-facing message and the kind of fix it needs. */
class SyncProblem(val problem: Problem, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Exports the enabled data types into their folders. Runs are serialized by a mutex. */
class SyncEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    private val status: StatusRepository,
    private val auth: GoogleAuth,
    private val notifier: Notifier,
) {
    private val mutex = Mutex()

    /**
     * Syncs the requested types (only those enabled in the settings).
     * [force] re-processes sources that are otherwise skipped when unchanged (Takeout exports).
     */
    suspend fun run(types: Set<SyncType>, force: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = settings.current
            if (!s.setupCompleted) return@withContext
            for (type in SyncType.entries.filter { it in types && s.target(it).enabled }) {
                status.setRunning(type, true)
                try {
                    val message = syncOne(type, s, force)
                    status.recordSuccess(type, message)
                    notifier.clearProblem(type)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val p = classify(e)
                    Log.w(TAG, "Sync $type failed", e)
                    status.recordFailure(type, p.message ?: "Errore sconosciuto", p.problem)
                    if (p.problem != Problem.TRANSIENT) notifier.showProblem(type, p.message.orEmpty(), p.problem)
                } finally {
                    status.setRunning(type, false)
                }
            }
        }
    }

    private fun syncOne(type: SyncType, s: AppSettings, force: Boolean): String {
        val account = s.accountName ?: throw SyncProblem(Problem.CONFIGURATION, "Nessun account Google selezionato")
        val folderUri = s.target(type).folderUri
            ?: throw SyncProblem(Problem.FOLDER, "Nessuna cartella selezionata")
        val folder = SafFolder.of(context, folderUri)
        val managed = { key: String, target: SafFolder -> ManagedFolder(target, manifest(key), s.deleteRemovedFiles).begin() }
        return when (type) {
            SyncType.CALENDAR -> syncCalendars(account, managed("calendar", folder))
            SyncType.CONTACTS -> syncContacts(account, s.contactsIncludePhotos, managed("contacts", folder))
            SyncType.TASKS -> syncTasks(account, managed("tasks", folder))
            SyncType.NOTES -> syncNotes(account, s, force, folder, managed)
        }
    }

    private fun syncCalendars(account: String, out: ManagedFolder): String {
        requirePermission(Manifest.permission.READ_CALENDAR, "calendario")
        val source = CalendarSource(context.contentResolver)
        val all = source.calendars(account)
        val active = all.filter { it.syncEnabled }
        if (active.isEmpty()) {
            throw SyncProblem(
                Problem.CONFIGURATION,
                "Nessun calendario sincronizzato per $account: attiva la sincronizzazione del Calendario nelle impostazioni Account di Android",
            )
        }
        val writer = IcsWriter()
        var events = 0
        for ((calendar, fileName) in FileNames.assignUnique(active, ".ics", { it.name })) {
            val ics = source.read(calendar)
            events += ics.events.size
            val text = writer.write(ics, Instant.now())
            // DTSTAMP changes at every export: ignore it when deciding whether the file changed.
            out.put(fileName, text, hashSource = text.lineSequence().filterNot { it.startsWith("DTSTAMP:") }.joinToString("\n"))
        }
        val stats = out.finish()
        val skipped = all.size - active.size
        return "${active.size} calendari, $events eventi" +
            (if (skipped > 0) " ($skipped non sincronizzati sul dispositivo, ignorati)" else "") + describe(stats)
    }

    private fun syncContacts(account: String, includePhotos: Boolean, out: ManagedFolder): String {
        requirePermission(Manifest.permission.READ_CONTACTS, "contatti")
        val contacts = ContactsSource(context.contentResolver).read(account, includePhotos)
        if (contacts.isEmpty()) {
            throw SyncProblem(
                Problem.CONFIGURATION,
                "Nessun contatto trovato per $account: verifica che la sincronizzazione dei Contatti sia attiva. Il file esistente non è stato modificato",
            )
        }
        out.put(CONTACTS_FILE, VCardWriter().write(contacts, includePhotos))
        return "${contacts.size} contatti" + describe(out.finish())
    }

    private fun syncTasks(account: String, out: ManagedFolder): String {
        val lists = TasksSource(GoogleApiClient(auth, account, GoogleAuth.SCOPE_TASKS)).read()
        if (lists.isEmpty()) throw SyncProblem(Problem.TRANSIENT, "Google Tasks non ha restituito elenchi: nessun file modificato")
        val sorted = lists.sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.id }))
        for ((list, fileName) in FileNames.assignUnique(sorted, ".todo.txt", { it.title })) {
            out.put(fileName, TodoTxtWriter.write(list.tasks))
        }
        return "${lists.size} elenchi, ${lists.sumOf { it.tasks.size }} attività" + describe(out.finish())
    }

    private fun syncNotes(
        account: String,
        s: AppSettings,
        force: Boolean,
        folder: SafFolder,
        managed: (String, SafFolder) -> ManagedFolder,
    ): String = when (s.notesSource) {
        NotesSource.KEEP_API -> {
            val notes = KeepApiSource(GoogleApiClient(auth, account, GoogleAuth.SCOPE_KEEP)).read()
            writeNotes(notes, null, folder, managed)
        }
        NotesSource.TAKEOUT -> {
            val takeoutUri = s.takeoutFolderUri
                ?: throw SyncProblem(Problem.FOLDER, "Seleziona la cartella in cui salvi gli export di Google Takeout")
            val source = KeepTakeoutSource(SafFolder.of(context, takeoutUri))
            val export = source.locate()
                ?: throw SyncProblem(Problem.CONFIGURATION, "Nessun export di Google Takeout (zip o cartella Keep) trovato nella cartella Takeout")
            val signature = export.signature + "|" + s.notes.folderUri + "|" + s.deleteRemovedFiles
            if (!force && status.current.takeoutSignature == signature) {
                "Nessun nuovo export Takeout (ultimo: ${export.description})"
            } else {
                source.read(export, File(context.cacheDir, "takeout")).use { content ->
                    if (content.notes.isEmpty()) {
                        throw SyncProblem(Problem.CONFIGURATION, "L'export ${export.description} non contiene note di Keep")
                    }
                    writeNotes(content.notes, content::attachment, folder, managed).also {
                        status.setTakeoutSignature(signature)
                    } + " da ${export.description}"
                }
            }
        }
    }

    private fun writeNotes(
        notes: List<KeepNote>,
        attachment: ((String) -> ByteArray?)?,
        folder: SafFolder,
        managed: (String, SafFolder) -> ManagedFolder,
    ): String {
        val out = managed("notes", folder)
        val sorted = notes.sortedWith(compareBy({ it.created ?: Instant.EPOCH }, { it.id }))
        val withAttachments = attachment != null && sorted.any { it.attachments.isNotEmpty() }
        val attachmentsOut = if (withAttachments) {
            managed("notes-attachments", folder.subfolder(KeepTakeoutParser.ATTACHMENTS_DIR, create = true)!!)
        } else {
            null
        }
        var attachmentCount = 0
        for ((note, fileName) in FileNames.assignUnique(sorted, ".md", ::noteBaseName)) {
            val fixed = note.copy(
                attachments = note.attachments.map { a ->
                    a.copy(path = KeepTakeoutParser.ATTACHMENTS_DIR + "/" + FileNames.sanitize(a.path.substringAfterLast('/'), 120))
                },
            )
            out.put(fileName, MarkdownWriter.write(fixed))
            if (attachmentsOut != null) {
                for ((original, target) in note.attachments.zip(fixed.attachments)) {
                    val name = original.path.substringAfterLast('/')
                    // Takeout sometimes references "x.jpeg" while the exported file is "x.jpg".
                    val bytes = attachment!!(name) ?: attachment(alternativeExtension(name)) ?: continue
                    attachmentsOut.put(target.path.substringAfterLast('/'), bytes)
                    attachmentCount++
                }
            }
        }
        val stats = out.finish()
        attachmentsOut?.finish()
        return "${notes.size} note" + (if (attachmentCount > 0) ", $attachmentCount allegati" else "") + describe(stats)
    }

    private fun alternativeExtension(name: String): String = when {
        name.endsWith(".jpeg", ignoreCase = true) -> name.dropLast(5) + ".jpg"
        name.endsWith(".jpg", ignoreCase = true) -> name.dropLast(4) + ".jpeg"
        else -> name
    }

    private fun noteBaseName(note: KeepNote): String {
        note.title.takeIf { it.isNotBlank() }?.let { return it }
        val firstLine = (note.text?.lineSequence() ?: emptySequence())
            .plus(note.listItems.orEmpty().asSequence().map { it.text })
            .map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (firstLine != null) return firstLine.take(50)
        val created = note.created ?: return "Nota"
        return "Nota " + NOTE_TIME.format(created.atZone(ZoneId.systemDefault()))
    }

    private fun describe(stats: ManagedFolder.Stats): String = buildString {
        append(" — ")
        append(if (stats.written == 0) "nessun file modificato" else "${stats.written} file aggiornati")
        if (stats.deleted > 0) append(", ${stats.deleted} eliminati")
    }

    private fun requirePermission(permission: String, what: String) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            throw SyncProblem(Problem.PERMISSION, "Permesso di accesso al $what non concesso")
        }
    }

    private fun manifest(key: String) = File(context.noBackupFilesDir, "manifests/$key.json")

    private fun classify(e: Throwable): SyncProblem = when (e) {
        is SyncProblem -> e
        is AuthorizationRequiredException -> SyncProblem(Problem.AUTHORIZATION, e.message.orEmpty(), e)
        is FolderAccessException -> SyncProblem(Problem.FOLDER, e.message.orEmpty(), e)
        is SecurityException -> SyncProblem(Problem.PERMISSION, "Permesso negato: ${e.message}", e)
        is ApiException -> when (e.code) {
            401, 403 -> SyncProblem(Problem.CONFIGURATION, e.message.orEmpty(), e)
            else -> SyncProblem(Problem.TRANSIENT, e.message.orEmpty(), e)
        }
        is AuthConfigurationException -> SyncProblem(Problem.CONFIGURATION, e.message.orEmpty(), e)
        is IOException -> SyncProblem(Problem.TRANSIENT, "Errore di rete: ${e.message ?: e.javaClass.simpleName}", e)
        else -> SyncProblem(Problem.CONFIGURATION, "Errore: ${e.message ?: e.javaClass.simpleName}", e)
    }

    companion object {
        private const val TAG = "SyncEngine"
        const val CONTACTS_FILE = "Contatti.vcf"
        private val NOTE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm", Locale.ROOT)
    }
}
