package io.github.waph1.syncer.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class PasswordImportTest {

    @Test
    fun parsesCxfLoginsNotesAndTotp() {
        val json = """
            {"version":{"major":1,"minor":0},"exporterRpId":"passwords.google","exporterDisplayName":"Gestore password di Google",
             "timestamp":1758700000,
             "accounts":[{"id":"YWNj","username":"me","email":"me@gmail.com","collections":[],"items":[
               {"id":"aXQx","creationAt":1700000000,"modifiedAt":1710000000,"title":"example.com",
                "scope":{"urls":["https://example.com/login","https://m.example.com"],"androidApps":[]},
                "credentials":[
                  {"type":"basic-auth","username":{"fieldType":"string","value":"mario"},"password":{"fieldType":"concealed-string","value":"s3cret"}},
                  {"type":"note","content":{"fieldType":"string","value":"PIN 1234"}}],
                "tags":["lavoro"]},
               {"id":"aXQy","title":"",
                "scope":{"urls":[],"androidApps":[{"bundleId":"com.twitter.android","name":"X"}]},
                "credentials":[{"type":"basic-auth","username":{"fieldType":"string","value":"@mario"},"password":{"fieldType":"concealed-string","value":"pw2"}}]},
               {"id":"aXQz","title":"Solo passkey","credentials":[{"type":"passkey","credentialId":"x"}]},
               {"id":"aXQ0","title":"Con OTP","credentials":[
                  {"type":"totp","secret":"JBSWY3DPEHPK3PXP","period":30,"digits":6,"algorithm":"sha1","issuer":"Ex Co","username":"mario"}]}
             ]}]}
        """.trimIndent()
        val entries = CxfParser.parse(json)
        assertEquals(3, entries.size)
        val web = entries[0]
        assertEquals("example.com", web.title)
        assertEquals("mario", web.username)
        assertEquals("s3cret", web.password)
        assertEquals("https://example.com/login", web.url)
        assertEquals("PIN 1234", web.notes)
        assertEquals(mapOf("KP2A_URL_1" to "https://m.example.com"), web.extra)
        assertEquals(listOf("lavoro"), web.tags)
        assertEquals(Instant.ofEpochSecond(1700000000), web.created)
        val app = entries[1]
        assertEquals("com.twitter.android", app.title)
        assertEquals("androidapp://com.twitter.android", app.url)
        assertEquals(mapOf("AndroidApp" to "com.twitter.android"), app.extra)
        val otp = entries[2]
        assertEquals("mario", otp.username)
        assertEquals("otpauth://totp/Ex%20Co%3Amario?secret=JBSWY3DPEHPK3PXP&issuer=Ex%20Co&period=30&digits=6&algorithm=SHA1", otp.otp)
        assertNull(entries.firstOrNull { it.title == "Solo passkey" })
    }

    @Test
    fun parsesGooglePasswordsCsv() {
        val csv = "\uFEFFname,url,username,password,note\r\n" +
            "example.com,https://www.example.com/,mario,\"p,a\"\"ss\",\"riga 1\nriga 2\"\r\n" +
            ",android://Jzj5T2E45Hb33D-lk-EHZVCrb7a064dEicTwrTYQYGXO99JqE2YERhbMP1qLogwJiy87OsBzC09Gk094Z-U_hg==@com.twitter.android/,@mario,pw,\r\n" +
            ",https://www.site.org/path,luigi,pw3,\r\n\r\n"
        val entries = GooglePasswordsCsv.parse(csv)
        assertEquals(3, entries.size)
        assertEquals("p,a\"ss", entries[0].password)
        assertEquals("riga 1\nriga 2", entries[0].notes)
        assertEquals("com.twitter.android", entries[1].title)
        assertEquals("androidapp://com.twitter.android", entries[1].url)
        assertEquals("site.org", entries[2].title)
    }

    @Test
    fun csvWithoutPasswordColumnIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { GooglePasswordsCsv.parse("a,b\n1,2\n") }
    }
}
