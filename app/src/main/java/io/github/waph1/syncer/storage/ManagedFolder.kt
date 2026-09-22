package io.github.waph1.syncer.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A file in a [FileStore]; [handle] is opaque to callers (a document URI for SAF). */
data class StoredFile(val name: String, val handle: String)

/** The folder operations [ManagedFolder] needs; implemented by [SafFolder]. */
interface FileStore {
    /** Identifies the folder, to tell manifests of different folders apart. */
    val key: String
    fun listFiles(): List<StoredFile>
    /** Creates an empty file; the returned name may differ if the provider renamed it. */
    fun create(name: String): StoredFile
    fun write(file: StoredFile, bytes: ByteArray)
    fun delete(file: StoredFile): Boolean
}

/**
 * Writes a set of files into a user folder, tracking what it wrote in a private manifest:
 * - a file is rewritten only when its content changed (or it went missing);
 * - files written by a previous run but not produced any more are deleted (if enabled);
 * - files the app did not create are never deleted.
 */
class ManagedFolder(
    private val folder: FileStore,
    private val manifestFile: File,
    private val deleteStale: Boolean,
) {
    @Serializable
    private data class Manifest(val folderUri: String, val files: Map<String, String> = emptyMap())

    data class Stats(val written: Int, val unchanged: Int, val deleted: Int)

    private lateinit var previous: Map<String, String>
    private lateinit var existing: MutableMap<String, StoredFile>
    private val current = linkedMapOf<String, String>()
    private var written = 0
    private var unchanged = 0

    fun begin(): ManagedFolder {
        previous = loadManifest()
        existing = folder.listFiles().associateBy { it.name.lowercase(Locale.ROOT) }.toMutableMap()
        return this
    }

    /** Writes [content] as [name] unless an identical version (by [hashSource]) is already there. */
    fun put(name: String, content: ByteArray, hashSource: ByteArray = content) {
        val hash = sha256(hashSource)
        val entry = existing[name.lowercase(Locale.ROOT)]
        if (entry != null && previous[entry.name] == hash) {
            current[entry.name] = hash
            unchanged++
            return
        }
        val file = entry ?: folder.create(name)
        folder.write(file, content)
        current[file.name] = hash
        existing[file.name.lowercase(Locale.ROOT)] = file
        written++
    }

    fun put(name: String, text: String, hashSource: String = text) =
        put(name, text.toByteArray(Charsets.UTF_8), hashSource.toByteArray(Charsets.UTF_8))

    fun finish(): Stats {
        var deleted = 0
        val kept = LinkedHashMap(current)
        for ((name, hash) in previous) {
            if (name in current) continue
            val entry = existing[name.lowercase(Locale.ROOT)] ?: continue
            if (deleteStale) {
                if (folder.delete(entry)) deleted++ else kept[name] = hash
            } else {
                kept[name] = hash
            }
        }
        saveManifest(kept)
        return Stats(written, unchanged, deleted)
    }

    private fun loadManifest(): Map<String, String> {
        if (!manifestFile.exists()) return emptyMap()
        val manifest = runCatching { json.decodeFromString(Manifest.serializer(), manifestFile.readText()) }.getOrNull()
            ?: return emptyMap()
        // A manifest for another folder must not cause deletions here.
        return if (manifest.folderUri == folder.key) manifest.files else emptyMap()
    }

    private fun saveManifest(files: Map<String, String>) {
        manifestFile.parentFile?.mkdirs()
        val tmp = File(manifestFile.path + ".tmp")
        tmp.writeText(json.encodeToString(Manifest.serializer(), Manifest(folder.key, files)))
        if (!tmp.renameTo(manifestFile)) {
            manifestFile.delete()
            tmp.renameTo(manifestFile)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
