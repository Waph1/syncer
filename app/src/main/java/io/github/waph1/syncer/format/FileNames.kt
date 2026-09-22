package io.github.waph1.syncer.format

import java.util.Locale

object FileNames {
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]")
    private val SPACES = Regex("\\s+")

    /** Makes [name] safe for FAT/exFAT/ext4 storage: no reserved characters, trimmed, bounded length. */
    fun sanitize(name: String, maxLength: Int = 80): String {
        var s = ILLEGAL.replace(name, " ")
        s = SPACES.replace(s, " ").trim().trim('.').trim()
        if (s.length > maxLength) {
            // Avoid cutting a surrogate pair in half.
            var end = maxLength
            if (Character.isHighSurrogate(s[end - 1])) end--
            s = s.substring(0, end).trim()
        }
        return s
    }

    /**
     * Assigns a unique file name to every item, resolving case-insensitive collisions with
     * " (2)", " (3)"... Items are processed in the given order, so pass a stable order.
     */
    fun <T> assignUnique(items: List<T>, extension: String, baseName: (T) -> String, fallback: String = "Senza titolo"): List<Pair<T, String>> {
        val used = HashSet<String>()
        return items.map { item ->
            val base = sanitize(baseName(item)).ifEmpty { fallback }
            var candidate = "$base$extension"
            var n = 2
            while (!used.add(candidate.lowercase(Locale.ROOT))) {
                candidate = "$base ($n)$extension"
                n++
            }
            item to candidate
        }
    }
}
