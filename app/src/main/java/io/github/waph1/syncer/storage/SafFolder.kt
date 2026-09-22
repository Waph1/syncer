package io.github.waph1.syncer.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Thrown when a folder chosen by the user is no longer reachable (permission revoked, SD removed...). */
class FolderAccessException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Minimal Storage Access Framework wrapper around a document tree, using DocumentsContract
 * directly (a single query per listing, unlike DocumentFile).
 */
class SafFolder private constructor(
    private val resolver: ContentResolver,
    val treeUri: Uri,
    private val documentId: String,
) : FileStore {
    data class Entry(
        val name: String,
        val documentId: String,
        val uri: Uri,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    fun list(): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        val entries = mutableListOf<Entry>()
        guard {
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    entries += Entry(
                        name = c.getString(1) ?: continue,
                        documentId = id,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        isDirectory = c.getString(2) == Document.MIME_TYPE_DIR,
                        size = if (c.isNull(3)) -1 else c.getLong(3),
                        lastModified = if (c.isNull(4)) 0 else c.getLong(4),
                    )
                }
            } ?: throw FolderAccessException("Cartella non raggiungibile")
        }
        return entries
    }

    fun subfolder(name: String, create: Boolean): SafFolder? {
        list().firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?.let { return SafFolder(resolver, treeUri, it.documentId) }
        if (!create) return null
        val uri = guard { DocumentsContract.createDocument(resolver, documentUri(), Document.MIME_TYPE_DIR, name) }
            ?: throw FolderAccessException("Impossibile creare la cartella $name")
        return SafFolder(resolver, treeUri, DocumentsContract.getDocumentId(uri))
    }

    /**
     * Creates an empty file. The generic MIME type keeps the provider from altering the
     * extension (e.g. appending ".txt" to "Lista.todo.txt" or ".md").
     */
    fun createFile(name: String): Uri =
        guard { DocumentsContract.createDocument(resolver, documentUri(), "application/octet-stream", name) }
            ?: throw FolderAccessException("Impossibile creare il file $name")

    override val key: String get() = treeUri.toString()

    override fun listFiles(): List<StoredFile> =
        list().filter { !it.isDirectory }.map { StoredFile(it.name, it.uri.toString()) }

    override fun create(name: String): StoredFile {
        val uri = createFile(name)
        return StoredFile(displayName(uri) ?: name, uri.toString())
    }

    override fun write(file: StoredFile, bytes: ByteArray) = write(file.handle.toUri(), bytes)

    override fun delete(file: StoredFile): Boolean = delete(file.handle.toUri())

    fun write(uri: Uri, bytes: ByteArray) {
        guard {
            // "wt" truncates: plain "w" leaves trailing garbage on some providers.
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw FolderAccessException("Impossibile scrivere il file")
        }
    }

    fun open(uri: Uri): InputStream =
        guard { resolver.openInputStream(uri) } ?: throw FolderAccessException("Impossibile leggere il file")

    fun delete(uri: Uri): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun documentUri(): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw FolderAccessException("Permesso sulla cartella perso: selezionala di nuovo nelle impostazioni", e)
    } catch (e: FileNotFoundException) {
        throw FolderAccessException("Cartella non trovata: selezionala di nuovo nelle impostazioni", e)
    } catch (e: IllegalArgumentException) {
        throw FolderAccessException("Cartella non valida: selezionala di nuovo nelle impostazioni", e)
    }

    companion object {
        const val PERMISSION_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun of(context: Context, treeUri: String): SafFolder {
            val uri = treeUri.toUri()
            val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }
                .getOrElse { throw FolderAccessException("Cartella non valida: selezionala di nuovo", it) }
            return SafFolder(context.contentResolver, uri, id)
        }

        /** True if the app still holds a persisted read/write grant for [treeUri]. */
        fun hasPermission(context: Context, treeUri: String?): Boolean {
            if (treeUri.isNullOrBlank()) return false
            val uri = treeUri.toUri()
            return context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        }

        /** Persists the grant returned by ACTION_OPEN_DOCUMENT_TREE so it survives reboots. */
        fun takePermission(context: Context, treeUri: Uri) {
            context.contentResolver.takePersistableUriPermission(treeUri, PERMISSION_FLAGS)
        }

        /** Human readable path for a tree URI, e.g. "primary:Sync/Calendario" → "Memoria interna/Sync/Calendario". */
        fun describe(treeUri: String?): String? {
            if (treeUri.isNullOrBlank()) return null
            val uri = treeUri.toUri()
            val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: return uri.lastPathSegment
            val volume = id.substringBefore(':')
            val path = id.substringAfter(':', "")
            val root = if (volume == "primary") "Memoria interna" else volume
            return if (path.isEmpty()) root else "$root/$path"
        }
    }
}
