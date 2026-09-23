package io.github.waph1.syncer.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.waph1.syncer.SyncerApp
import java.io.File

/** Application for Robolectric tests: a synchronous test WorkManager instead of the real one. */
class TestSyncerApp : SyncerApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        super.onCreate()
    }
}

/** Base for fake providers: no writes, subclasses answer queries. */
abstract class ReadOnlyProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    protected fun cursor(projection: Array<out String>?, rows: List<Map<String, Any?>>): Cursor {
        val columns = projection ?: rows.firstOrNull()?.keys?.toTypedArray() ?: emptyArray()
        return MatrixCursor(columns).apply { rows.forEach { row -> addRow(columns.map { row[it] }) } }
    }
}

/**
 * A Storage Access Framework provider backed by a directory, implementing just what
 * [io.github.waph1.syncer.storage.SafFolder] uses: children listing, create, delete,
 * display name queries and file streams. Document ids are paths relative to [root].
 */
class FakeDocumentsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private fun file(documentId: String) = File(root, documentId)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val columns = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cursor = MatrixCursor(columns)
        val documentId = DocumentsContract.getDocumentId(uri)
        val files = if (uri.lastPathSegment == "children") {
            file(documentId).listFiles().orEmpty().sortedBy { it.name }.toList()
        } else {
            listOf(file(documentId))
        }
        for (f in files) {
            val id = f.relativeTo(root).path
            cursor.addRow(
                columns.map {
                    when (it) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> f.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE ->
                            if (f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                        DocumentsContract.Document.COLUMN_SIZE -> f.length()
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> f.lastModified()
                        else -> null
                    }
                },
            )
        }
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        @Suppress("DEPRECATION")
        val uri = extras?.getParcelable<Uri>(EXTRA_URI) ?: return null
        val documentId = DocumentsContract.getDocumentId(uri)
        return when (method) {
            "android:createDocument" -> {
                val name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!
                val mime = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                var target = File(file(documentId), name)
                var n = 1
                while (target.exists()) target = File(file(documentId), "${name.substringBeforeLast('.')} (${n++}).${name.substringAfterLast('.')}")
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
                val treeId = DocumentsContract.getTreeDocumentId(uri)
                Bundle().apply {
                    putParcelable(
                        EXTRA_URI,
                        DocumentsContract.buildDocumentUriUsingTree(
                            DocumentsContract.buildTreeDocumentUri(AUTHORITY, treeId),
                            target.relativeTo(root).path,
                        ),
                    )
                }
            }
            "android:deleteDocument" -> {
                file(documentId).deleteRecursively()
                Bundle()
            }
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.parseMode(mode))

    companion object {
        const val AUTHORITY = "io.github.waph1.syncer.test.documents"

        // DocumentsContract.EXTRA_URI is hidden from the SDK.
        private const val EXTRA_URI = "uri"
        lateinit var root: File

        /** Tree URI for a folder under [root] (created if missing). */
        fun treeUri(path: String): Uri {
            File(root, path).mkdirs()
            return DocumentsContract.buildTreeDocumentUri(AUTHORITY, path)
        }
    }
}

/** CalendarContract provider answering from in-memory rows keyed by table name. */
class FakeCalendarProvider : ReadOnlyProvider() {
    /** Supports updating one calendar by id (content://com.android.calendar/calendars/<id>). */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (uri.pathSegments.firstOrNull() != "calendars" || values == null) return 0
        val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
        var updated = 0
        tables["calendars"] = tables["calendars"].orEmpty().map { row ->
            if (row["_id"] == id) {
                updated++
                row + values.keySet().associateWith { values.get(it) }
            } else {
                row
            }
        }
        return updated
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val table = uri.pathSegments.firstOrNull().orEmpty()
        var rows = tables[table].orEmpty()
        if (table == "events" && args != null && args.isNotEmpty()) rows = rows.filter { it["calendar_id"].toString() == args[0] }
        if (table == "calendars" && args != null && args.isNotEmpty()) rows = rows.filter { it["account_name"] == args[0] }
        return cursor(projection, rows)
    }

    companion object {
        const val AUTHORITY = "com.android.calendar"
        val tables = mutableMapOf<String, List<Map<String, Any?>>>()
    }
}

/** ContactsContract provider answering from in-memory rows keyed by table name. */
class FakeContactsProvider : ReadOnlyProvider() {
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
        cursor(projection, tables[uri.pathSegments.firstOrNull().orEmpty()].orEmpty())

    companion object {
        const val AUTHORITY = "com.android.contacts"
        val tables = mutableMapOf<String, List<Map<String, Any?>>>()
    }
}
