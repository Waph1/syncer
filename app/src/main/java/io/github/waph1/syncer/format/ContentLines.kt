package io.github.waph1.syncer.format

/**
 * Helpers shared by the iCalendar (RFC 5545) and vCard (RFC 2426) writers:
 * text escaping and 75-octet line folding with CRLF line endings.
 */
internal object ContentLines {
    private const val MAX_OCTETS = 75

    /** Escapes a TEXT value: backslash, semicolon, comma and line breaks. */
    fun escapeText(value: String): String {
        val sb = StringBuilder(value.length + 8)
        var i = 0
        while (i < value.length) {
            when (val c = value[i]) {
                '\\' -> sb.append("\\\\")
                ';' -> sb.append("\\;")
                ',' -> sb.append("\\,")
                '\r' -> {
                    sb.append("\\n")
                    if (i + 1 < value.length && value[i + 1] == '\n') i++
                }
                '\n' -> sb.append("\\n")
                else -> if (c >= ' ' || c == '\t') sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Formats a parameter value, quoting it when it contains separators. */
    fun paramValue(value: String): String {
        val clean = value.replace("\"", "'").replace(Regex("[\\r\\n]+"), " ")
        return if (clean.any { it == ':' || it == ';' || it == ',' }) "\"$clean\"" else clean
    }

    /**
     * Folds a single logical content line so that no physical line exceeds 75 octets
     * (UTF-8), never splitting a multi-byte character. Continuation lines start with a space.
     */
    fun fold(line: String): String {
        if (utf8Length(line) <= MAX_OCTETS) return line
        val sb = StringBuilder(line.length + line.length / 70 * 3)
        var octets = 0
        var limit = MAX_OCTETS
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val charCount = Character.charCount(cp)
            val size = utf8Length(cp)
            if (octets + size > limit) {
                sb.append("\r\n ")
                octets = 0
                limit = MAX_OCTETS - 1 // the leading space counts
            }
            sb.appendCodePoint(cp)
            octets += size
            i += charCount
        }
        return sb.toString()
    }

    private fun utf8Length(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private fun utf8Length(s: String): Int {
        var total = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            total += utf8Length(cp)
            i += Character.charCount(cp)
        }
        return total
    }
}

/** Accumulates folded content lines separated by CRLF. */
internal class ContentLineBuilder {
    private val sb = StringBuilder()

    fun line(raw: String): ContentLineBuilder {
        sb.append(ContentLines.fold(raw)).append("\r\n")
        return this
    }

    /** Adds `NAME;params:escaped-text` if the value is not blank. */
    fun text(name: String, value: String?, params: String = ""): ContentLineBuilder {
        if (value.isNullOrBlank()) return this
        return line("$name$params:${ContentLines.escapeText(value)}")
    }

    override fun toString(): String = sb.toString()
}
