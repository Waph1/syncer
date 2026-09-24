package io.github.waph1.syncer.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class KdbxTest {
    // Small Argon2 cost keeps the tests fast; production uses Kdbx.KdfParams() defaults.
    private val fast = Kdbx.KdfParams(memoryBytes = 1024 * 1024, iterations = 1, parallelism = 1)
    private val now = Instant.parse("2026-09-24T10:00:00Z")

    private val entries = listOf(
        PasswordEntry("Esempio", "mario", "p@ss<w>rd&\"è😀", "https://example.com/login", "nota\nsu due righe", tags = listOf("web")),
        PasswordEntry("Twitter", "mario", "second", "androidapp://com.twitter.android", extra = mapOf("AndroidApp" to "com.twitter.android")),
        PasswordEntry("Esempio", "mario", "duplicate", "https://example.com/login"),
        PasswordEntry("Solo OTP", otp = "otpauth://totp/Ex:mario?secret=JBSWY3DPEHPK3PXP&issuer=Ex"),
    )

    private fun create(password: String = "correct horse", params: Kdbx.KdfParams = fast) =
        Kdbx.create(password, params) { protector -> KeePassXml.build("Password Google", entries, now, protector) }

    @Test
    fun createdDatabaseDecryptsToTheXmlWithProtectedPasswords() {
        val file = create()
        assertEquals(0x03, file[0].toInt())
        assertEquals(0xD9.toByte(), file[1])
        val xml = Kdbx.readXml(file, "correct horse")
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<DatabaseName>Password Google</DatabaseName>"))
        assertTrue(xml.contains("<Key>UserName</Key><Value>mario</Value>"))
        assertTrue(xml.contains("<Key>Notes</Key><Value>nota\nsu due righe</Value>"))
        assertTrue(xml.contains("<Key>AndroidApp</Key><Value>com.twitter.android</Value>"))
        assertTrue(xml.contains("<Tags>web</Tags>"))
        // Secrets never appear in clear inside the XML.
        assertFalse(xml.contains("p@ss"))
        assertFalse(xml.contains("JBSWY3DPEHPK3PXP"))
        assertEquals(5, Regex("Protected=\"True\"").findAll(xml).count())
        // Same login twice gets two different (but deterministic) UUIDs.
        val uuids = Regex("<Entry>\n<UUID>(.*?)</UUID>").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(4, uuids.toSet().size)
        assertEquals(uuids, Regex("<Entry>\n<UUID>(.*?)</UUID>").findAll(Kdbx.readXml(create(), "correct horse")).map { it.groupValues[1] }.toList())
    }

    @Test
    fun wrongPasswordIsRejected() {
        val e = assertThrows(KdbxException::class.java) { Kdbx.readXml(create(), "wrong") }
        assertEquals("Password del database errata", e.message)
    }

    @Test
    fun corruptedFileIsRejected() {
        val file = create()
        file[file.size - 40] = (file[file.size - 40] + 1).toByte()
        assertThrows(KdbxException::class.java) { Kdbx.readXml(file, "correct horse") }
        assertThrows(KdbxException::class.java) { Kdbx.readXml("not a database".toByteArray(), "x") }
    }

    @Test
    fun changePasswordKeepsTheContent() {
        val original = create()
        val changed = Kdbx.changePassword(original, "correct horse", "nuova password", fast)
        assertEquals(Kdbx.readXml(original, "correct horse"), Kdbx.readXml(changed, "nuova password"))
        assertThrows(KdbxException::class.java) { Kdbx.readXml(changed, "correct horse") }
    }

    @Test
    fun xmlTimesAndEscaping() {
        // 1970-01-01 = 62135596800 s after 0001-01-01.
        assertEquals("APeRdw4AAAA=", KeePassXml.time(Instant.EPOCH))
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;", KeePassXml.escape("a & b <c> \"d\" 'e'\u0001"))
    }

    /** Writes a database for validation with an independent reader (pykeepass). */
    @Test
    fun dumpForExternalValidation() {
        val dir = System.getenv("SYNCER_SAMPLE_DIR")
        assumeTrue(dir != null)
        File(dir!!).mkdirs()
        File(dir, "fast.kdbx").writeBytes(create())
        File(dir, "default.kdbx").writeBytes(create(params = Kdbx.KdfParams()))
        File(dir, "changed.kdbx").writeBytes(Kdbx.changePassword(create(), "correct horse", "nuova password", fast))
    }
}
