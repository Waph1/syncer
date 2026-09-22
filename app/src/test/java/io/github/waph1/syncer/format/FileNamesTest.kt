package io.github.waph1.syncer.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {

    @Test
    fun sanitizeRemovesReservedCharacters() {
        assertEquals("Lavoro Casa", FileNames.sanitize("Lavoro/Casa"))
        assertEquals("a b c d", FileNames.sanitize("a:b*c?\"d"))
        assertEquals("Nota", FileNames.sanitize("  ..Nota.. "))
        assertEquals("riga1 riga2", FileNames.sanitize("riga1\nriga2"))
        assertEquals("", FileNames.sanitize("///"))
        assertEquals(10, FileNames.sanitize("x".repeat(50), maxLength = 10).length)
        // Never cut an emoji (surrogate pair) in half.
        assertEquals("abcdefghi", FileNames.sanitize("abcdefghi😀", maxLength = 10))
    }

    @Test
    fun assignUniqueResolvesCaseInsensitiveCollisions() {
        val names = FileNames.assignUnique(listOf("Spesa", "spesa", "", "Spesa"), ".md", { it }).map { it.second }
        assertEquals(listOf("Spesa.md", "spesa (2).md", "Senza titolo.md", "Spesa (3).md"), names)
    }
}
