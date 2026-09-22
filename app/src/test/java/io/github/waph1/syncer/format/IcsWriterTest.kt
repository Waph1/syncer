package io.github.waph1.syncer.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class IcsWriterTest {
    private val stamp = Instant.parse("2026-01-01T00:00:00Z")
    private val rome = ZoneId.of("Europe/Rome")

    private fun millis(local: String, zone: ZoneId = rome) =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun utcMidnight(date: String) = Instant.parse("${date}T00:00:00Z").toEpochMilli()

    private fun write(vararg events: IcsEvent, tz: String? = "Europe/Rome") =
        IcsWriter().write(IcsCalendar("Lavoro", tz, 0xFF3366CC.toInt(), events.toList()), stamp)

    /** Undoes RFC 5545 folding so assertions can look at logical lines. */
    private fun unfold(ics: String) = ics.replace("\r\n ", "").split("\r\n")

    @Test
    fun timedEventUsesTzidAndVtimezone() {
        val ics = write(
            IcsEvent(uid = "a1@google.com", summary = "Riunione", start = millis("2024-01-15T10:00"), end = millis("2024-01-15T11:30"), timeZone = "Europe/Rome"),
        )
        val lines = unfold(ics)
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertTrue(lines.contains("X-WR-CALNAME:Lavoro"))
        assertTrue(lines.contains("X-WR-TIMEZONE:Europe/Rome"))
        assertTrue(lines.contains("X-APPLE-CALENDAR-COLOR:#3366CC"))
        assertTrue(lines.contains("DTSTART;TZID=Europe/Rome:20240115T100000"))
        assertTrue(lines.contains("DTEND;TZID=Europe/Rome:20240115T113000"))
        assertTrue(lines.contains("DTSTAMP:20260101T000000Z"))
        assertTrue(lines.contains("TZID:Europe/Rome"))
        assertTrue(lines.contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU"))
        assertTrue(lines.contains("RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU"))
        assertTrue(lines.contains("TZOFFSETFROM:+0100"))
        assertTrue(lines.contains("TZOFFSETTO:+0200"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        // VTIMEZONE must precede the events.
        assertTrue(lines.indexOf("BEGIN:VTIMEZONE") < lines.indexOf("BEGIN:VEVENT"))
    }

    @Test
    fun vtimezoneDaylightRuleMatchesJavaTimeTransitions() {
        val ics = write(
            IcsEvent(uid = "ny", start = millis("2024-06-01T09:00", ZoneId.of("America/New_York")), timeZone = "America/New_York"),
        )
        val lines = unfold(ics)
        assertTrue(lines.contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU"))
        assertTrue(lines.contains("RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU"))
        assertTrue(lines.contains("DTSTART;TZID=America/New_York:20240601T090000"))
    }

    @Test
    fun fixedOffsetZoneHasSingleStandardObservance() {
        val ics = write(IcsEvent(uid = "tk", start = millis("2024-06-01T09:00", ZoneId.of("Asia/Tokyo")), timeZone = "Asia/Tokyo"), tz = null)
        val lines = unfold(ics)
        assertTrue(lines.contains("TZOFFSETTO:+0900"))
        assertFalse(lines.contains("BEGIN:DAYLIGHT"))
        assertFalse(lines.any { it.startsWith("X-WR-TIMEZONE") })
    }

    @Test
    fun utcAndUnknownZonesAreWrittenInUtc() {
        val start = Instant.parse("2024-03-01T08:00:00Z").toEpochMilli()
        val lines = unfold(
            write(
                IcsEvent(uid = "u1", start = start, end = start + 3_600_000, timeZone = "UTC"),
                IcsEvent(uid = "u2", start = start, end = start + 3_600_000, timeZone = "Not/AZone"),
                IcsEvent(uid = "u3", start = start, end = start + 3_600_000, timeZone = "GMT+01:00"),
                tz = null,
            ),
        )
        assertEquals(3, lines.count { it == "DTSTART:20240301T080000Z" })
        assertFalse(lines.contains("BEGIN:VTIMEZONE"))
    }

    @Test
    fun allDayRecurringEventUsesDatesAndDateUntil() {
        val lines = unfold(
            write(
                IcsEvent(
                    uid = "bday",
                    summary = "Compleanno",
                    start = utcMidnight("2024-05-10"),
                    duration = "P1D",
                    timeZone = "UTC",
                    allDay = true,
                    rrules = listOf("FREQ=YEARLY;UNTIL=20300510T000000Z;WKST=MO"),
                    exdates = "20250510T000000Z",
                ),
            ),
        )
        assertTrue(lines.contains("DTSTART;VALUE=DATE:20240510"))
        assertTrue(lines.contains("DTEND;VALUE=DATE:20240511"))
        assertTrue(lines.contains("RRULE:FREQ=YEARLY;UNTIL=20300510;WKST=MO"))
        assertTrue(lines.contains("EXDATE;VALUE=DATE:20250510"))
        assertFalse(lines.contains("BEGIN:VTIMEZONE"))
    }

    @Test
    fun durationIsConvertedToDtend() {
        val lines = unfold(
            write(
                IcsEvent(uid = "d", start = millis("2024-03-30T23:30"), duration = "P3600S", timeZone = "Europe/Rome", rrules = listOf("FREQ=DAILY;COUNT=3")),
            ),
        )
        assertTrue(lines.contains("DTSTART;TZID=Europe/Rome:20240330T233000"))
        assertTrue(lines.contains("DTEND;TZID=Europe/Rome:20240331T003000"))
        assertTrue(lines.contains("RRULE:FREQ=DAILY;COUNT=3"))
    }

    @Test
    fun dateOnlyUntilOnTimedEventBecomesUtcEndOfDay() {
        val lines = unfold(
            write(IcsEvent(uid = "w", start = millis("2024-01-01T10:00"), end = millis("2024-01-01T11:00"), timeZone = "Europe/Rome", rrules = listOf("RRULE:FREQ=WEEKLY;UNTIL=20240131"))),
        )
        assertTrue(lines.contains("RRULE:FREQ=WEEKLY;UNTIL=20240131T225959Z"))
    }

    @Test
    fun exdatesWithTzidAndExceptions() {
        val master = IcsEvent(
            uid = "rec@google.com",
            summary = "Standup",
            start = millis("2024-01-08T09:00"),
            duration = "PT15M",
            timeZone = "Europe/Rome",
            rrules = listOf("FREQ=WEEKLY;BYDAY=MO"),
            exdates = "Europe/Rome;20240115T090000,20240122T090000",
            extraExdates = listOf(millis("2024-01-29T09:00")),
        )
        val moved = IcsEvent(
            uid = "rec@google.com",
            summary = "Standup (spostato)",
            start = millis("2024-02-05T10:00"),
            end = millis("2024-02-05T10:15"),
            timeZone = "Europe/Rome",
            recurrenceId = millis("2024-02-05T09:00"),
        )
        val lines = unfold(write(moved, master))
        assertTrue(lines.contains("EXDATE;TZID=Europe/Rome:20240115T090000,20240122T090000"))
        assertTrue(lines.contains("EXDATE:20240129T080000Z"))
        assertTrue(lines.contains("RECURRENCE-ID;TZID=Europe/Rome:20240205T090000"))
        // The master (no RECURRENCE-ID) is written before its exception.
        val firstSummary = lines.first { it.startsWith("SUMMARY:") }
        assertEquals("SUMMARY:Standup", firstSummary)
    }

    @Test
    fun attendeesOrganizerAndAlarms() {
        val lines = unfold(
            write(
                IcsEvent(
                    uid = "m",
                    summary = "Pranzo",
                    start = millis("2024-01-15T13:00"),
                    end = millis("2024-01-15T14:00"),
                    timeZone = "Europe/Rome",
                    status = EventStatus.CONFIRMED,
                    transparent = false,
                    classification = Classification.PRIVATE,
                    organizerEmail = "me@example.com",
                    attendees = listOf(
                        IcsAttendee("b@example.com", "Rossi, Mario", AttendeeRole.OPTIONAL, AttendeeStatus.ACCEPTED),
                        IcsAttendee("a@example.com", null),
                    ),
                    alarmMinutes = listOf(30, 0, 30),
                ),
            ),
        )
        assertTrue(lines.contains("ORGANIZER:mailto:me@example.com"))
        assertTrue(lines.contains("ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:a@example.com"))
        assertTrue(lines.contains("ATTENDEE;CN=\"Rossi, Mario\";ROLE=OPT-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:b@example.com"))
        assertTrue(lines.contains("STATUS:CONFIRMED"))
        assertTrue(lines.contains("TRANSP:OPAQUE"))
        assertTrue(lines.contains("CLASS:PRIVATE"))
        assertEquals(2, lines.count { it == "BEGIN:VALARM" })
        assertTrue(lines.contains("TRIGGER:-PT30M"))
        assertTrue(lines.contains("TRIGGER:PT0S"))
    }

    @Test
    fun organizerOmittedWithoutAttendees() {
        val lines = unfold(write(IcsEvent(uid = "x", start = millis("2024-01-15T13:00"), timeZone = "Europe/Rome", organizerEmail = "me@example.com")))
        assertFalse(lines.any { it.startsWith("ORGANIZER") })
        assertFalse(lines.any { it.startsWith("DTEND") })
    }

    @Test
    fun textIsEscapedAndLinesAreFoldedAt75Octets() {
        val description = "Riga 1; con, virgole\\ e backslash\nRiga 2 " + "è".repeat(60) + " 😀".repeat(10)
        val ics = write(IcsEvent(uid = "t", summary = "Test", description = description, start = millis("2024-01-15T13:00"), timeZone = "Europe/Rome"))
        for (physical in ics.split("\r\n")) {
            assertTrue("line too long: $physical", physical.toByteArray(Charsets.UTF_8).size <= 75)
        }
        val logical = unfold(ics).first { it.startsWith("DESCRIPTION:") }
        assertEquals("DESCRIPTION:" + ContentLines.escapeText(description), logical)
        assertTrue(logical.startsWith("DESCRIPTION:Riga 1\\; con\\, virgole\\\\ e backslash\\nRiga 2"))
        // Every line ends with CRLF, no bare LF.
        assertFalse(ics.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun outputIsDeterministicRegardlessOfInputOrder() {
        val a = IcsEvent(uid = "a", start = millis("2024-01-15T13:00"), timeZone = "Europe/Rome")
        val b = IcsEvent(uid = "b", start = millis("2024-01-16T13:00"), timeZone = "Europe/Rome")
        assertEquals(write(a, b), write(b, a))
    }

    @Test
    fun durationParsing() {
        assertEquals(RfcDuration(false, 0, 3600), RfcDuration.parse("P3600S"))
        assertEquals(RfcDuration(false, 0, 5400), RfcDuration.parse("PT1H30M"))
        assertEquals(RfcDuration(false, 1, 0), RfcDuration.parse("P1D"))
        assertEquals(RfcDuration(false, 14, 0), RfcDuration.parse("P2W"))
        assertEquals(RfcDuration(true, 0, 900), RfcDuration.parse("-PT15M"))
        assertEquals(RfcDuration(false, 1, 7200), RfcDuration.parse("P1DT2H"))
        assertNull(RfcDuration.parse("P"))
        assertNull(RfcDuration.parse("1H"))
        assertNull(RfcDuration.parse("P1X"))
        assertNull(RfcDuration.parse("P12"))
        assertEquals(1, RfcDuration.parse("P86400S")!!.wholeDays())
    }

    @Test
    fun offsetFormatting() {
        assertEquals("+0100", IcsWriter.formatOffset(ZoneOffset.ofHours(1)))
        assertEquals("-0330", IcsWriter.formatOffset(ZoneOffset.ofHoursMinutes(-3, -30)))
        assertEquals("+0545", IcsWriter.formatOffset(ZoneOffset.ofHoursMinutes(5, 45)))
        assertEquals("+000030", IcsWriter.formatOffset(ZoneOffset.ofTotalSeconds(30)))
    }
}
