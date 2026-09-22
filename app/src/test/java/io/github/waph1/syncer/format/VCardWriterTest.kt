package io.github.waph1.syncer.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VCardWriterTest {
    private fun unfold(s: String) = s.replace("\r\n ", "").split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun writesStructuredContact() {
        val contact = VContact(
            uid = "c1",
            displayName = "Mario Rossi",
            name = VName(family = "Rossi", given = "Mario", prefix = "Dott."),
            nicknames = listOf("Super, Mario"),
            phones = listOf(
                VLabeled("+39 333 1234567", listOf("CELL"), preferred = true),
                VLabeled("+39 06 123456", listOf("VOICE"), label = "Casa al mare"),
            ),
            emails = listOf(VLabeled("mario@example.com", listOf("WORK"))),
            addresses = listOf(VAddress(street = "Via Roma 1", city = "Roma", postalCode = "00100", country = "Italia", types = listOf("HOME"))),
            organizations = listOf(VOrganization("ACME; Srl", "R&D", "Ingegnere")),
            websites = listOf("https://example.com"),
            notes = listOf("Nota\nsu due righe"),
            dates = listOf(VDate("1980-05-10", VEventKind.BIRTHDAY), VDate("2010-06-01", VEventKind.ANNIVERSARY), VDate("2015-01-01", VEventKind.OTHER, "Laurea")),
            relations = listOf(VLabeled("Anna", label = "Spouse")),
            impps = listOf(VLabeled("xmpp:mario@example.com")),
            groups = listOf("Amici", "Lavoro", "Amici"),
            starred = true,
        )
        val lines = unfold(VCardWriter().write(listOf(contact), includePhotos = true))
        assertEquals("BEGIN:VCARD", lines.first())
        assertEquals("VERSION:3.0", lines[1])
        assertTrue(lines.contains("UID:c1"))
        assertTrue(lines.contains("FN:Mario Rossi"))
        assertTrue(lines.contains("N:Rossi;Mario;;Dott.;"))
        assertTrue(lines.contains("NICKNAME:Super\\, Mario"))
        assertTrue(lines.contains("ORG:ACME\\; Srl;R&D"))
        assertTrue(lines.contains("TITLE:Ingegnere"))
        assertTrue(lines.contains("TEL;TYPE=CELL,PREF:+39 333 1234567"))
        assertTrue(lines.contains("item1.TEL;TYPE=VOICE:+39 06 123456"))
        assertTrue(lines.contains("item1.X-ABLabel:Casa al mare"))
        assertTrue(lines.contains("EMAIL;TYPE=INTERNET,WORK:mario@example.com"))
        assertTrue(lines.contains("ADR;TYPE=HOME:;;Via Roma 1;Roma;;00100;Italia"))
        assertTrue(lines.contains("URL:https://example.com"))
        assertTrue(lines.contains("NOTE:Nota\\nsu due righe"))
        assertTrue(lines.contains("BDAY:1980-05-10"))
        assertTrue(lines.contains("X-ANNIVERSARY:2010-06-01"))
        assertTrue(lines.contains("item2.X-ABDATE:2015-01-01"))
        assertTrue(lines.contains("item2.X-ABLabel:Laurea"))
        assertTrue(lines.contains("item3.X-ABRELATEDNAMES:Anna"))
        assertTrue(lines.contains("IMPP:xmpp:mario@example.com"))
        assertTrue(lines.contains("CATEGORIES:Amici,Lavoro"))
        assertTrue(lines.contains("X-STARRED:1"))
        assertEquals("END:VCARD", lines.last())
    }

    @Test
    fun formattedNameFallsBack() {
        val lines = unfold(
            VCardWriter().write(
                listOf(
                    VContact(uid = "1", name = VName(given = "Luca", family = "Bianchi")),
                    VContact(uid = "2", emails = listOf(VLabeled("x@example.com"))),
                    VContact(uid = "3"),
                ),
                includePhotos = false,
            ),
        )
        assertTrue(lines.contains("FN:Luca Bianchi"))
        assertTrue(lines.contains("FN:x@example.com"))
        assertTrue(lines.contains("FN:"))
        assertEquals(3, lines.count { it == "BEGIN:VCARD" })
        assertTrue(lines.contains("N:;;;;"))
    }

    @Test
    fun photoIsBase64EncodedAndFoldedOnlyWhenRequested() {
        val photo = ByteArray(300) { it.toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        val contact = VContact(uid = "p", displayName = "Foto", photo = photo)
        val withPhoto = VCardWriter().write(listOf(contact), includePhotos = true)
        withPhoto.split("\r\n").forEach { assertTrue(it.length <= 75) }
        val photoLine = unfold(withPhoto).first { it.startsWith("PHOTO") }
        assertEquals("PHOTO;ENCODING=b;TYPE=JPEG:" + Base64.getEncoder().encodeToString(photo), photoLine)
        assertFalse(VCardWriter().write(listOf(contact), includePhotos = false).contains("PHOTO"))
    }

    @Test
    fun contactsAreSortedByName() {
        val out = VCardWriter().write(listOf(VContact(uid = "b", displayName = "zeta"), VContact(uid = "a", displayName = "Alfa")), false)
        assertTrue(out.indexOf("FN:Alfa") < out.indexOf("FN:zeta"))
    }
}
