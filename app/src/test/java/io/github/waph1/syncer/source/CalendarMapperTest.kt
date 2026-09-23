package io.github.waph1.syncer.source

import android.provider.CalendarContract.Events
import io.github.waph1.syncer.format.AttendeeStatus
import io.github.waph1.syncer.format.Classification
import io.github.waph1.syncer.format.EventStatus
import io.github.waph1.syncer.format.IcsAttendee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarMapperTest {
    private val calendar = CalendarSource.CalendarRef(7, "work@group.calendar.google.com", "Lavoro", "Europe/Rome", null, true)

    private fun row(
        id: Long,
        syncId: String? = null,
        originalId: Long? = null,
        originalSyncId: String? = null,
        originalInstanceTime: Long? = null,
        status: Int? = Events.STATUS_CONFIRMED,
        organizer: String? = "me@gmail.com",
        uid2445: String? = null,
    ) = EventRow(
        id = id, syncId = syncId, uid2445 = uid2445, title = "E$id", description = null, location = null,
        dtStart = 1_000L * id, dtEnd = 1_000L * id + 500, duration = null, timeZone = "Europe/Rome", endTimeZone = null,
        allDay = false, rrule = if (originalId == null && originalSyncId == null) "FREQ=DAILY\n" else null, rdate = null,
        exrule = null, exdate = null, originalId = originalId, originalSyncId = originalSyncId,
        originalInstanceTime = originalInstanceTime, originalAllDay = false, status = status,
        availability = Events.AVAILABILITY_FREE, accessLevel = Events.ACCESS_PRIVATE, organizer = organizer,
    )

    @Test
    fun exceptionsShareMasterUidAndCancelledInstancesBecomeExdates() {
        val rows = listOf(
            row(1, syncId = "abc"),
            row(2, syncId = "abc_20240102", originalId = 1, originalInstanceTime = 86_400_000),
            row(3, syncId = "abc_20240103", originalSyncId = "abc", originalInstanceTime = 172_800_000, status = Events.STATUS_CANCELED),
            row(4, originalSyncId = "missing", originalInstanceTime = 5),
            row(5),
            row(6, uid2445 = "imported@example.com"),
        )
        val ics = CalendarMapper.toIcs(calendar, rows, emptyMap(), emptyMap())
        assertEquals(5, ics.events.size)
        val master = ics.events.first { it.summary == "E1" }
        assertEquals("abc@google.com", master.uid)
        assertEquals(listOf("FREQ=DAILY"), master.rrules)
        assertEquals(listOf(172_800_000L), master.extraExdates)
        assertNull(master.recurrenceId)
        assertEquals(EventStatus.CONFIRMED, master.status)
        assertEquals(true, master.transparent)
        assertEquals(Classification.PRIVATE, master.classification)

        val moved = ics.events.first { it.summary == "E2" }
        assertEquals("abc@google.com", moved.uid)
        assertEquals(86_400_000L, moved.recurrenceId)
        assertTrue(moved.rrules.isEmpty())

        assertEquals("missing@google.com", ics.events.first { it.summary == "E4" }.uid)
        assertEquals("local-7-5@syncer", ics.events.first { it.summary == "E5" }.uid)
        assertEquals("imported@example.com", ics.events.first { it.summary == "E6" }.uid)
    }

    @Test
    fun organizerAloneIsNotTreatedAsAMeeting() {
        val self = IcsAttendee("me@gmail.com", "Me", status = AttendeeStatus.ACCEPTED)
        val guest = IcsAttendee("guest@example.com", "Ospite")
        val rows = listOf(row(1, syncId = "solo"), row(2, syncId = "meeting"))
        val ics = CalendarMapper.toIcs(calendar, rows, mapOf(1L to listOf(self), 2L to listOf(self, guest)), mapOf(2L to listOf(10)))
        val solo = ics.events.first { it.uid == "solo@google.com" }
        assertTrue(solo.attendees.isEmpty())
        val meeting = ics.events.first { it.uid == "meeting@google.com" }
        assertEquals(2, meeting.attendees.size)
        assertEquals("Me", meeting.organizerName)
        assertEquals(listOf(10), meeting.alarmMinutes)
    }
}
