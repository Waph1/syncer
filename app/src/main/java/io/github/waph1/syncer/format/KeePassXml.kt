package io.github.waph1.syncer.format

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** A login to store in the KeePass database. */
data class PasswordEntry(
    val title: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    /** otpauth:// URI (KeePassXC / KeePassDX "otp" field). */
    val otp: String? = null,
    /** Extra fields, e.g. "AndroidApp" for app logins (used by KeePassDX autofill). */
    val extra: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val created: Instant? = null,
    val modified: Instant? = null,
)

/** Builds the KeePass 2.x XML document (KDBX 4 flavour: binary timestamps, protected values). */
object KeePassXml {
    private const val ZERO_UUID = "AAAAAAAAAAAAAAAAAAAAAA=="

    fun build(databaseName: String, entries: List<PasswordEntry>, now: Instant, protector: Kdbx.Protector): String {
        val t = time(now)
        val sb = StringBuilder(4096 + entries.size * 1024)
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n<KeePassFile>\n<Meta>\n")
        el(sb, "Generator", "Syncer")
        el(sb, "DatabaseName", databaseName)
        el(sb, "DatabaseNameChanged", t)
        el(sb, "DatabaseDescription", "Password esportate dal Gestore password di Google con Syncer")
        el(sb, "DatabaseDescriptionChanged", t)
        el(sb, "DefaultUserName", "")
        el(sb, "DefaultUserNameChanged", t)
        el(sb, "MaintenanceHistoryDays", "365")
        el(sb, "Color", "")
        el(sb, "MasterKeyChanged", t)
        el(sb, "MasterKeyChangeRec", "-1")
        el(sb, "MasterKeyChangeForce", "-1")
        sb.append("<MemoryProtection>")
        el(sb, "ProtectTitle", "False")
        el(sb, "ProtectUserName", "False")
        el(sb, "ProtectPassword", "True")
        el(sb, "ProtectURL", "False")
        el(sb, "ProtectNotes", "False")
        sb.append("</MemoryProtection>\n")
        el(sb, "RecycleBinEnabled", "False")
        el(sb, "RecycleBinUUID", ZERO_UUID)
        el(sb, "RecycleBinChanged", t)
        el(sb, "EntryTemplatesGroup", ZERO_UUID)
        el(sb, "EntryTemplatesGroupChanged", t)
        el(sb, "HistoryMaxItems", "10")
        el(sb, "HistoryMaxSize", "6291456")
        el(sb, "LastSelectedGroup", ZERO_UUID)
        el(sb, "LastTopVisibleGroup", ZERO_UUID)
        sb.append("<CustomData/>\n</Meta>\n<Root>\n<Group>\n")
        el(sb, "UUID", uuid("syncer:group:$databaseName"))
        el(sb, "Name", databaseName)
        el(sb, "Notes", "")
        el(sb, "IconID", "48")
        times(sb, t, t)
        el(sb, "IsExpanded", "True")
        el(sb, "DefaultAutoTypeSequence", "")
        el(sb, "EnableAutoType", "null")
        el(sb, "EnableSearching", "null")
        el(sb, "LastTopVisibleEntry", ZERO_UUID)

        // Stable UUIDs across exports (same login → same entry), so KeePass merges work.
        val seen = HashMap<String, Int>()
        for (e in entries) {
            val identity = "${e.url}\u0000${e.username}\u0000${e.title}"
            val occurrence = seen.merge(identity, 1, Int::plus)!!
            sb.append("<Entry>\n")
            el(sb, "UUID", uuid("syncer:entry:$identity\u0000$occurrence"))
            el(sb, "IconID", "0")
            el(sb, "ForegroundColor", "")
            el(sb, "BackgroundColor", "")
            el(sb, "OverrideURL", "")
            el(sb, "Tags", e.tags.joinToString(";"))
            times(sb, e.created?.let(::time) ?: t, e.modified?.let(::time) ?: t)
            // KeePass lists fields alphabetically; protected values must be encoded in document order.
            val fields = sortedMapOf(
                "Notes" to e.notes,
                "Password" to e.password,
                "Title" to e.title,
                "URL" to e.url,
                "UserName" to e.username,
            )
            e.extra.forEach { (k, v) -> if (k !in fields) fields[k] = v }
            e.otp?.let { fields["otp"] = it }
            for ((key, value) in fields) {
                sb.append("<String><Key>").append(escape(key)).append("</Key>")
                if (key == "Password" || key == "otp") {
                    val protectedValue = protector.protect(value.toByteArray(Charsets.UTF_8))
                    sb.append("<Value Protected=\"True\">").append(Base64.getEncoder().encodeToString(protectedValue)).append("</Value>")
                } else {
                    sb.append("<Value>").append(escape(value)).append("</Value>")
                }
                sb.append("</String>\n")
            }
            sb.append("<AutoType>")
            el(sb, "Enabled", "True")
            el(sb, "DataTransferObfuscation", "0")
            sb.append("</AutoType>\n<History/>\n</Entry>\n")
        }
        sb.append("</Group>\n<DeletedObjects/>\n</Root>\n</KeePassFile>\n")
        return sb.toString()
    }

    private fun times(sb: StringBuilder, created: String, modified: String) {
        sb.append("<Times>")
        el(sb, "CreationTime", created)
        el(sb, "LastModificationTime", modified)
        el(sb, "LastAccessTime", modified)
        el(sb, "ExpiryTime", created)
        el(sb, "Expires", "False")
        el(sb, "UsageCount", "0")
        el(sb, "LocationChanged", modified)
        sb.append("</Times>\n")
    }

    private fun el(sb: StringBuilder, name: String, value: String) {
        if (value.isEmpty()) sb.append('<').append(name).append("/>") else {
            sb.append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>')
        }
        sb.append('\n')
    }

    /** KDBX 4 time: base64 of little-endian int64 seconds since 0001-01-01T00:00:00Z. */
    internal fun time(instant: Instant): String {
        val seconds = instant.epochSecond + 62_135_596_800L
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(seconds).array())
    }

    private fun uuid(seed: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8)).copyOf(16))

    /** Escapes XML text and drops characters XML 1.0 cannot represent. */
    internal fun escape(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\t', '\n', '\r' -> sb.append(c)
                else -> if (c >= ' ' && c != '￾' && c != '￿') sb.append(c)
            }
        }
        return sb.toString()
    }
}
