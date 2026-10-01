package io.github.waph1.syncer.format

import io.github.waph1.syncer.format.DurationText.Invalid
import io.github.waph1.syncer.format.DurationText.Valid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class DurationTextTest {

    private fun parse(text: String) = DurationText.parse(text)

    private fun duration(text: String): Duration = (parse(text) as? Valid)?.duration
        ?: throw AssertionError("\"$text\" → ${parse(text)}")

    private fun invalid(text: String): String = (parse(text) as? Invalid)?.message
        ?: throw AssertionError("\"$text\" should be invalid")

    @Test
    fun parsesItalianAndShortForms() {
        assertEquals(Duration.ofDays(14), duration("2 settimane"))
        assertEquals(Duration.ofDays(14), duration("ogni 2 sett"))
        assertEquals(Duration.ofDays(30), duration("1 mese"))
        assertEquals(Duration.ofDays(30), duration("un mese"))
        assertEquals(Duration.ofDays(45), duration("un mese e mezzo"))
        assertEquals(Duration.ofDays(44), duration("1 mese e 2 settimane"))
        assertEquals(Duration.ofHours(36), duration("36h"))
        assertEquals(Duration.ofHours(36), duration("1g12h"))
        assertEquals(Duration.ofHours(36), duration("1,5 giorni"))
        assertEquals(Duration.ofHours(36), duration("1.5 days"))
        assertEquals(Duration.ofHours(1), duration("un'ora"))
        assertEquals(Duration.ofHours(1), duration("Un’Ora"))
        assertEquals(Duration.ofMinutes(90), duration("90 minuti"))
        assertEquals(Duration.ofHours(12), duration("mezza giornata"))
        assertEquals(Duration.ofDays(365), duration("1 anno"))
        assertEquals(Duration.ofDays(365), duration("un anno"))
        assertEquals(Duration.ofDays(360), duration("12 mesi"))
        assertEquals(Duration.ofDays(364), duration("52 settimane"))
        assertEquals(Duration.ofDays(21), duration("3 weeks"))
        assertEquals(Duration.ofDays(1), duration("giorno"))
    }

    @Test
    fun enforcesLimitsAndRejectsNonsense() {
        assertEquals("Minimo 1 ora", invalid("30 minuti"))
        assertEquals("Massimo 1 anno", invalid("13 mesi"))
        assertEquals("Massimo 1 anno", invalid("366 giorni"))
        assertTrue(invalid("").startsWith("Scrivi una durata"))
        assertTrue(invalid("2").startsWith("Aggiungi l'unità"))
        assertTrue(invalid("2 3 giorni").startsWith("Manca l'unità"))
        assertTrue(invalid("2 lune").contains("lune"))
        assertTrue(invalid("3m").contains("ambiguo"))
        assertEquals(Duration.ofMinutes(15), (DurationText.parse("15 min", DurationText.FIFTEEN_MINUTES, DurationText.ONE_YEAR) as Valid).duration)
        assertEquals("Minimo 15 minuti", (DurationText.parse("5 min", DurationText.FIFTEEN_MINUTES, DurationText.ONE_YEAR) as Invalid).message)
        assertEquals(null, DurationText.durationOf("boh"))
    }

    @Test
    fun formatsInTheLargestExactUnit() {
        assertEquals("2 settimane", DurationText.format(Duration.ofDays(14)))
        assertEquals("1 mese", DurationText.format(Duration.ofDays(30)))
        assertEquals("1 anno", DurationText.format(Duration.ofDays(365)))
        assertEquals("12 mesi", DurationText.format(Duration.ofDays(360)))
        assertEquals("36 ore", DurationText.format(Duration.ofHours(36)))
        assertEquals("2 giorni e 2 ore", DurationText.format(Duration.ofHours(50)))
        assertEquals("24 giorni e 12 ore", DurationText.format(Duration.ofHours(24 * 24 + 12)))
        assertEquals("1 ora e 30 minuti", DurationText.format(Duration.ofMinutes(90)))
        assertEquals("6 ore", DurationText.format(Duration.ofHours(6)))
        assertEquals("15 minuti", DurationText.format(Duration.ofMinutes(15)))
        assertEquals("1 giorno, 2 ore e 5 minuti", DurationText.format(Duration.ofMinutes(24 * 60 + 125)))
    }
}
