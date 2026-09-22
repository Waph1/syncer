package io.github.waph1.syncer.source

import io.github.waph1.syncer.format.KeepNote
import io.github.waph1.syncer.storage.SafFolder
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Google Keep through the official Keep API v1. Google only enables this API for
 * Google Workspace accounts (the admin must allow it); personal @gmail.com accounts get 403.
 */
class KeepApiSource(private val client: GoogleApiClient) {
    fun read(): List<KeepNote> {
        val notes = mutableListOf<ApiNote>()
        var page: String? = null
        do {
            val url = "https://keep.googleapis.com/v1/notes?pageSize=100" +
                (page?.let { "&pageToken=" + GoogleApiClient.encode(it) } ?: "")
            val response = client.get(url, KeepNotesResponse.serializer())
            notes += response.notes
            page = response.nextPageToken
        } while (page != null)
        return notes.filter { !it.trashed }.map { it.toKeepNote() }
    }
}

/**
 * Google Keep from Google Takeout exports placed by the user in a folder: either the
 * downloaded zip file(s) or the extracted "Takeout/Keep" folder.
 */
class KeepTakeoutSource(private val folder: SafFolder) {

    sealed class Export(val signature: String, val description: String) {
        class Zips(val zips: List<SafFolder.Entry>) : Export(
            zips.joinToString("|") { "${it.name}:${it.size}:${it.lastModified}" },
            zips.joinToString(", ") { it.name },
        )

        class JsonFolder(val folder: SafFolder, val files: List<SafFolder.Entry>, path: String) : Export(
            files.sortedBy { it.name }.joinToString("|") { "${it.name}:${it.size}:${it.lastModified}" }.hashCode().toString(),
            "cartella $path (${files.count { it.name.endsWith(".json", true) }} note)",
        )
    }

    class Content(val notes: List<KeepNote>, private val attachments: (String) -> ByteArray?, private val cleanup: () -> Unit) : Closeable {
        /** Bytes of an attachment referenced by a note, by file name. */
        fun attachment(fileName: String): ByteArray? = attachments(fileName)
        override fun close() = cleanup()
    }

    /** Finds the most recent export: the newest zip (all parts of it) or an extracted Keep folder. */
    fun locate(): Export? {
        val entries = folder.list()
        val zips = entries.filter { !it.isDirectory && it.name.lowercase(Locale.ROOT).endsWith(".zip") }
        if (zips.isNotEmpty()) {
            val newest = zips.maxBy { it.lastModified }
            // Multi-part exports are named takeout-<date>-001.zip, -002.zip...
            val prefix = newest.name.replace(PART_SUFFIX, "")
            val parts = zips.filter { it.name.replace(PART_SUFFIX, "") == prefix }.sortedBy { it.name }
            return Export.Zips(parts)
        }
        for (path in listOf(emptyList(), listOf("Keep"), listOf("Takeout", "Keep"))) {
            var current: SafFolder? = folder
            for (segment in path) current = current?.subfolder(segment, create = false)
            val dir = current ?: continue
            val files = if (path.isEmpty()) entries else dir.list()
            if (files.any { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }) {
                return Export.JsonFolder(dir, files.filter { !it.isDirectory }, path.joinToString("/").ifEmpty { "selezionata" })
            }
        }
        return null
    }

    fun read(export: Export, tempDir: File): Content = when (export) {
        is Export.Zips -> readZips(export, tempDir)
        is Export.JsonFolder -> readFolder(export)
    }

    private fun readZips(export: Export.Zips, tempDir: File): Content {
        tempDir.deleteRecursively()
        tempDir.mkdirs()
        val notes = mutableListOf<KeepNote>()
        for (zip in export.zips) {
            ZipInputStream(folder.open(zip.uri).buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val parts = entry.name.replace('\\', '/').split('/')
                    // Only direct children of a "Keep" folder.
                    if (parts.size < 2 || parts[parts.size - 2] != "Keep") continue
                    val fileName = parts.last()
                    val lower = fileName.lowercase(Locale.ROOT)
                    when {
                        lower.endsWith(".json") -> {
                            val text = zis.readBytes().toString(Charsets.UTF_8)
                            runCatching { KeepTakeoutParser.parse(text, fileName.removeSuffix(".json")) }
                                .getOrNull()?.let { notes += it }
                        }
                        lower.endsWith(".html") || lower.endsWith(".txt") -> Unit
                        else -> File(tempDir, safeName(fileName)).outputStream().use { zis.copyTo(it) }
                    }
                }
            }
        }
        return Content(
            notes = notes,
            attachments = { name -> File(tempDir, safeName(name)).takeIf { it.isFile }?.readBytes() },
            cleanup = { tempDir.deleteRecursively() },
        )
    }

    private fun readFolder(export: Export.JsonFolder): Content {
        val byName = export.files.associateBy { it.name }
        val notes = export.files.filter { it.name.endsWith(".json", ignoreCase = true) }.mapNotNull { entry ->
            runCatching {
                val text = export.folder.open(entry.uri).use { it.readBytes().toString(Charsets.UTF_8) }
                KeepTakeoutParser.parse(text, entry.name.removeSuffix(".json"))
            }.getOrNull()
        }
        return Content(
            notes = notes,
            attachments = { name -> byName[name]?.let { e -> runCatching { export.folder.open(e.uri).use { it.readBytes() } }.getOrNull() } },
            cleanup = {},
        )
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        val PART_SUFFIX = Regex("-\\d{3}\\.zip$", RegexOption.IGNORE_CASE)
    }
}
