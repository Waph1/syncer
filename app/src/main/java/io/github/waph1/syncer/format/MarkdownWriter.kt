package io.github.waph1.syncer.format

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class NoteListItem(
    val text: String,
    val checked: Boolean,
    val children: List<NoteListItem> = emptyList(),
)

data class NoteAttachment(
    /** Path relative to the notes folder, e.g. "attachments/photo.jpg". */
    val path: String,
    val mimeType: String?,
)

data class NoteLink(val url: String, val title: String?)

data class KeepNote(
    val id: String,
    val title: String,
    val text: String? = null,
    val listItems: List<NoteListItem>? = null,
    val created: Instant? = null,
    val updated: Instant? = null,
    val labels: List<String> = emptyList(),
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val color: String? = null,
    val attachments: List<NoteAttachment> = emptyList(),
    val links: List<NoteLink> = emptyList(),
)

/** Writes a Keep note as Markdown with a YAML front matter (Obsidian/Markor friendly). */
object MarkdownWriter {
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun write(note: KeepNote): String {
        val sb = StringBuilder()
        sb.append("---\n")
        if (note.title.isNotBlank()) sb.append("title: ").append(yamlString(note.title)).append('\n')
        note.created?.let { sb.append("created: ").append(timestamp(it)).append('\n') }
        note.updated?.let { sb.append("updated: ").append(timestamp(it)).append('\n') }
        if (note.labels.isNotEmpty()) {
            sb.append("tags:\n")
            note.labels.distinct().sorted().forEach { sb.append("  - ").append(yamlString(it)).append('\n') }
        }
        if (note.pinned) sb.append("pinned: true\n")
        if (note.archived) sb.append("archived: true\n")
        note.color?.takeIf { it.isNotBlank() && !it.equals("DEFAULT", ignoreCase = true) }
            ?.let { sb.append("color: ").append(yamlString(it.lowercase())).append('\n') }
        sb.append("source: google-keep\n")
        sb.append("---\n\n")

        note.text?.takeIf { it.isNotBlank() }?.let { sb.append(it.trimEnd()).append("\n") }
        note.listItems?.takeIf { it.isNotEmpty() }?.let { items ->
            if (!note.text.isNullOrBlank()) sb.append('\n')
            // Keep shows unchecked items first, then checked ones.
            val ordered = items.filter { !it.checked } + items.filter { it.checked }
            ordered.forEach { appendItem(sb, it, 0) }
        }
        if (note.links.isNotEmpty()) {
            sb.append("\n## Link\n\n")
            note.links.forEach { link ->
                val title = link.title?.takeIf { it.isNotBlank() } ?: link.url
                sb.append("- [").append(escapeLinkText(title)).append("](").append(link.url.replace(" ", "%20")).append(")\n")
            }
        }
        if (note.attachments.isNotEmpty()) {
            sb.append("\n## Allegati\n\n")
            note.attachments.forEach { a ->
                val target = a.path.split('/').joinToString("/") { encodePathSegment(it) }
                val name = a.path.substringAfterLast('/')
                if (a.mimeType?.startsWith("image/") == true) {
                    sb.append("![").append(escapeLinkText(name)).append("](").append(target).append(")\n")
                } else {
                    sb.append("- [").append(escapeLinkText(name)).append("](").append(target).append(")\n")
                }
            }
        }
        return sb.toString()
    }

    private fun appendItem(sb: StringBuilder, item: NoteListItem, depth: Int) {
        val indent = "    ".repeat(depth)
        val lines = item.text.trimEnd().split('\n')
        sb.append(indent).append(if (item.checked) "- [x] " else "- [ ] ").append(lines.first()).append('\n')
        lines.drop(1).forEach { sb.append(indent).append("      ").append(it).append('\n') }
        item.children.forEach { appendItem(sb, it, depth + 1) }
    }

    private fun timestamp(instant: Instant): String =
        TIMESTAMP.format(instant.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC))

    internal fun yamlString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun escapeLinkText(text: String): String = text.replace("[", "\\[").replace("]", "\\]")

    private fun encodePathSegment(segment: String): String = buildString {
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-._~")) append(ch)
            else append('%').append(String.format(java.util.Locale.ROOT, "%02X", c))
        }
    }
}
