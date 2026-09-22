package io.github.waph1.syncer.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ManagedFolderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** In-memory folder that counts writes, like a case-insensitive SAF provider. */
    private class MemoryStore(override val key: String = "content://tree/a") : FileStore {
        val files = linkedMapOf<String, ByteArray>()
        var writes = 0

        override fun listFiles() = files.keys.map { StoredFile(it, it) }
        override fun create(name: String): StoredFile {
            files[name] = ByteArray(0)
            return StoredFile(name, name)
        }
        override fun write(file: StoredFile, bytes: ByteArray) {
            files[file.handle] = bytes
            writes++
        }
        override fun delete(file: StoredFile) = files.remove(file.handle) != null
        fun text(name: String) = files[name]?.toString(Charsets.UTF_8)
    }

    private lateinit var manifest: File

    private fun run(store: FileStore, deleteStale: Boolean = true, block: ManagedFolder.() -> Unit): ManagedFolder.Stats {
        if (!::manifest.isInitialized) manifest = File(tmp.root, "manifests/test.json")
        return ManagedFolder(store, manifest, deleteStale).begin().apply(block).finish()
    }

    @Test
    fun writesOnlyChangedFiles() {
        val store = MemoryStore()
        assertEquals(ManagedFolder.Stats(2, 0, 0), run(store) { put("a.ics", "A1"); put("b.ics", "B1") })
        assertEquals(ManagedFolder.Stats(1, 1, 0), run(store) { put("a.ics", "A1"); put("b.ics", "B2") })
        assertEquals("B2", store.text("b.ics"))
        assertEquals(3, store.writes)
    }

    @Test
    fun hashSourceIgnoresVolatileParts() {
        val store = MemoryStore()
        run(store) { put("c.ics", "DTSTAMP:1\nX", hashSource = "X") }
        val stats = run(store) { put("c.ics", "DTSTAMP:2\nX", hashSource = "X") }
        assertEquals(ManagedFolder.Stats(0, 1, 0), stats)
        assertEquals("DTSTAMP:1\nX", store.text("c.ics"))
    }

    @Test
    fun rewritesFilesDeletedByTheUser() {
        val store = MemoryStore()
        run(store) { put("a.md", "A") }
        store.files.remove("a.md")
        assertEquals(ManagedFolder.Stats(1, 0, 0), run(store) { put("a.md", "A") })
        assertEquals("A", store.text("a.md"))
    }

    @Test
    fun deletesOnlyStaleFilesItCreated() {
        val store = MemoryStore()
        store.files["user notes.txt"] = "mine".toByteArray()
        run(store) { put("old.md", "O"); put("keep.md", "K") }
        val stats = run(store) { put("keep.md", "K") }
        assertEquals(ManagedFolder.Stats(0, 1, 1), stats)
        assertFalse("old.md" in store.files)
        assertTrue("user notes.txt" in store.files)
    }

    @Test
    fun keepsStaleFilesWhenDeletionDisabledAndDeletesThemLater() {
        val store = MemoryStore()
        run(store) { put("old.md", "O") }
        assertEquals(ManagedFolder.Stats(0, 0, 0), run(store, deleteStale = false) {})
        assertTrue("old.md" in store.files)
        // Still tracked: removed once deletion is enabled again.
        assertEquals(ManagedFolder.Stats(0, 0, 1), run(store, deleteStale = true) {})
        assertFalse("old.md" in store.files)
    }

    @Test
    fun manifestOfAnotherFolderNeverCausesDeletions() {
        val first = MemoryStore("content://tree/first")
        run(first) { put("x.md", "X") }
        val second = MemoryStore("content://tree/second")
        second.files["x.md"] = "unrelated".toByteArray()
        assertEquals(ManagedFolder.Stats(0, 0, 0), run(second) {})
        assertEquals("unrelated", second.text("x.md"))
    }

    @Test
    fun overwritesExistingFileMatchingCaseInsensitively() {
        val store = MemoryStore()
        store.files["Spesa.md"] = "vecchio".toByteArray()
        run(store) { put("spesa.md", "nuovo") }
        assertEquals("nuovo", store.text("Spesa.md"))
        assertEquals(1, store.files.size)
    }
}
