package io.github.waph1.syncer.format

import java.util.Base64
import java.util.Locale

data class VName(
    val family: String? = null,
    val given: String? = null,
    val middle: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val phoneticFamily: String? = null,
    val phoneticGiven: String? = null,
    val phoneticMiddle: String? = null,
)

/**
 * A value with vCard TYPE parameters (e.g. "CELL", "WORK") and an optional custom label,
 * written in the Apple/Google style `itemN.X-ABLabel` when present.
 */
data class VLabeled(
    val value: String,
    val types: List<String> = emptyList(),
    val label: String? = null,
    val preferred: Boolean = false,
)

data class VAddress(
    val street: String? = null,
    val poBox: String? = null,
    val extended: String? = null,
    val city: String? = null,
    val region: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val formatted: String? = null,
    val types: List<String> = emptyList(),
    val label: String? = null,
)

data class VOrganization(
    val company: String? = null,
    val department: String? = null,
    val title: String? = null,
)

enum class VEventKind { BIRTHDAY, ANNIVERSARY, OTHER }

data class VDate(val date: String, val kind: VEventKind, val label: String? = null)

@Suppress("ArrayInDataClass") // equality is never used; photos are compared as files
data class VContact(
    val uid: String,
    val displayName: String? = null,
    val name: VName? = null,
    val nicknames: List<String> = emptyList(),
    val phones: List<VLabeled> = emptyList(),
    val emails: List<VLabeled> = emptyList(),
    val addresses: List<VAddress> = emptyList(),
    val organizations: List<VOrganization> = emptyList(),
    val websites: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val dates: List<VDate> = emptyList(),
    /** Related people: value is the name, label the relation (e.g. "Spouse"). */
    val relations: List<VLabeled> = emptyList(),
    /** Instant messaging / SIP as URIs (e.g. "xmpp:me@example.com", "sip:me@example.com"). */
    val impps: List<VLabeled> = emptyList(),
    val groups: List<String> = emptyList(),
    val photo: ByteArray? = null,
    val starred: Boolean = false,
)

/** Serializes contacts to a single vCard 3.0 (RFC 2426) stream. */
class VCardWriter(private val prodId: String = IcsWriter.DEFAULT_PRODID) {

    fun write(contacts: List<VContact>, includePhotos: Boolean): String {
        val sorted = contacts.sortedWith(
            compareBy<VContact>({ (it.displayName ?: "").lowercase(Locale.ROOT) }, { it.uid }),
        )
        return sorted.joinToString("") { writeOne(it, includePhotos) }
    }

    private fun writeOne(c: VContact, includePhotos: Boolean): String {
        val out = ContentLineBuilder()
        var item = 0
        fun labeled(property: String, v: VLabeled, value: String) {
            val types = buildList {
                addAll(v.types)
                if (v.preferred) add("PREF")
            }.distinct()
            val params = if (types.isEmpty()) "" else ";TYPE=" + types.joinToString(",")
            if (v.label.isNullOrBlank()) {
                out.line("$property$params:$value")
            } else {
                item++
                out.line("item$item.$property$params:$value")
                out.line("item$item.X-ABLabel:" + ContentLines.escapeText(v.label))
            }
        }

        out.line("BEGIN:VCARD")
        out.line("VERSION:3.0")
        out.line("PRODID:$prodId")
        out.line("UID:" + ContentLines.escapeText(c.uid))
        out.line("FN:" + ContentLines.escapeText(formattedName(c)))
        val n = c.name ?: VName()
        out.line(
            "N:" + listOf(n.family, n.given, n.middle, n.prefix, n.suffix)
                .joinToString(";") { ContentLines.escapeText(it.orEmpty()) },
        )
        out.text("X-PHONETIC-FIRST-NAME", n.phoneticGiven)
        out.text("X-PHONETIC-MIDDLE-NAME", n.phoneticMiddle)
        out.text("X-PHONETIC-LAST-NAME", n.phoneticFamily)
        val nicknames = c.nicknames.filter { it.isNotBlank() }
        if (nicknames.isNotEmpty()) out.line("NICKNAME:" + nicknames.joinToString(",") { ContentLines.escapeText(it) })

        c.organizations.firstOrNull()?.let { org ->
            if (!org.company.isNullOrBlank() || !org.department.isNullOrBlank()) {
                val parts = listOfNotNull(org.company.orEmpty(), org.department?.takeIf { it.isNotBlank() })
                out.line("ORG:" + parts.joinToString(";") { ContentLines.escapeText(it) })
            }
            out.text("TITLE", org.title)
        }

        for (p in c.phones) if (p.value.isNotBlank()) labeled("TEL", p, ContentLines.escapeText(p.value))
        for (e in c.emails) {
            if (e.value.isNotBlank()) labeled("EMAIL", e.copy(types = listOf("INTERNET") + e.types), ContentLines.escapeText(e.value))
        }
        for (a in c.addresses) {
            val components = listOf(a.poBox, a.extended, a.street, a.city, a.region, a.postalCode, a.country)
            val hasStructured = components.any { !it.isNullOrBlank() }
            val value = if (hasStructured) {
                components.joinToString(";") { ContentLines.escapeText(it.orEmpty()) }
            } else {
                ";;" + ContentLines.escapeText(a.formatted.orEmpty()) + ";;;;"
            }
            if (!hasStructured && a.formatted.isNullOrBlank()) continue
            labeled("ADR", VLabeled(value = "", types = a.types, label = a.label), value)
        }
        for (url in c.websites) if (url.isNotBlank()) out.line("URL:" + ContentLines.escapeText(url))
        for (d in c.dates) {
            when (d.kind) {
                VEventKind.BIRTHDAY -> out.line("BDAY:" + d.date)
                VEventKind.ANNIVERSARY -> out.line("X-ANNIVERSARY:" + d.date)
                VEventKind.OTHER -> labeled("X-ABDATE", VLabeled(value = d.date, label = d.label ?: "Other"), d.date)
            }
        }
        for (r in c.relations) {
            if (r.value.isNotBlank()) {
                labeled("X-ABRELATEDNAMES", r.copy(label = r.label ?: "Other"), ContentLines.escapeText(r.value))
            }
        }
        for (im in c.impps) if (im.value.isNotBlank()) labeled("IMPP", im, im.value)
        val notes = c.notes.filter { it.isNotBlank() }
        if (notes.isNotEmpty()) out.line("NOTE:" + ContentLines.escapeText(notes.joinToString("\n\n")))
        val groups = c.groups.filter { it.isNotBlank() }.distinct().sorted()
        if (groups.isNotEmpty()) out.line("CATEGORIES:" + groups.joinToString(",") { ContentLines.escapeText(it) })
        if (c.starred) out.line("X-STARRED:1")
        if (includePhotos && c.photo != null && c.photo.isNotEmpty()) {
            val type = if (c.photo.size > 3 && c.photo[0] == 0x89.toByte() && c.photo[1] == 'P'.code.toByte()) "PNG" else "JPEG"
            out.line("PHOTO;ENCODING=b;TYPE=$type:" + Base64.getEncoder().encodeToString(c.photo))
        }
        out.line("END:VCARD")
        return out.toString()
    }

    private fun formattedName(c: VContact): String {
        c.displayName?.takeIf { it.isNotBlank() }?.let { return it }
        c.name?.let { n ->
            val joined = listOf(n.prefix, n.given, n.middle, n.family, n.suffix)
                .filter { !it.isNullOrBlank() }.joinToString(" ")
            if (joined.isNotBlank()) return joined
        }
        return c.organizations.firstOrNull()?.company?.takeIf { it.isNotBlank() }
            ?: c.emails.firstOrNull()?.value
            ?: c.phones.firstOrNull()?.value
            ?: ""
    }
}
