package io.github.waph1.syncer.source

import android.content.ContentResolver
import android.database.Cursor
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import io.github.waph1.syncer.format.AttendeeRole
import io.github.waph1.syncer.format.AttendeeStatus
import io.github.waph1.syncer.format.Classification
import io.github.waph1.syncer.format.EventStatus
import io.github.waph1.syncer.format.IcsAttendee
import io.github.waph1.syncer.format.IcsCalendar
import io.github.waph1.syncer.format.IcsEvent
import java.util.Locale

/**
 * Reads the Google calendars of an account from Android's CalendarContract provider, i.e. the
 * data kept up to date on the device by the Google Calendar sync adapter.
 */
class CalendarSource(private val resolver: ContentResolver) {

    data class CalendarRef(val id: Long, val name: String, val timeZone: String?, val color: Int?, val syncEnabled: Boolean)

    fun calendars(account: String): List<CalendarRef> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.NAME,
            Calendars.OWNER_ACCOUNT,
            Calendars.CALENDAR_TIME_ZONE,
            Calendars.CALENDAR_COLOR,
            Calendars.SYNC_EVENTS,
        )
        val result = mutableListOf<CalendarRef>()
        resolver.query(
            Calendars.CONTENT_URI, projection,
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(account, GOOGLE_ACCOUNT_TYPE), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.str(1) ?: c.str(2) ?: c.str(3) ?: "Calendario ${c.getLong(0)}"
                result += CalendarRef(
                    id = c.getLong(0),
                    name = name,
                    timeZone = c.str(4),
                    color = if (c.isNull(5)) null else c.getInt(5),
                    syncEnabled = !c.isNull(6) && c.getInt(6) == 1,
                )
            }
        }
        return result.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun read(calendar: CalendarRef): IcsCalendar {
        val rows = queryEvents(calendar.id)
        val ids = rows.map { it.id }
        return CalendarMapper.toIcs(calendar, rows, queryAttendees(ids), queryReminders(ids))
    }

    private fun queryEvents(calendarId: Long): List<EventRow> {
        val projection = arrayOf(
            Events._ID, Events._SYNC_ID, Events.UID_2445, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
            Events.DTSTART, Events.DTEND, Events.DURATION, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE,
            Events.ALL_DAY, Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.ORIGINAL_ID,
            Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY, Events.STATUS,
            Events.AVAILABILITY, Events.ACCESS_LEVEL, Events.ORGANIZER,
        )
        val rows = mutableListOf<EventRow>()
        resolver.query(
            Events.CONTENT_URI, projection,
            "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0", arrayOf(calendarId.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.isNull(6)) continue
                rows += EventRow(
                    id = c.getLong(0),
                    syncId = c.str(1),
                    uid2445 = c.str(2),
                    title = c.str(3),
                    description = c.str(4),
                    location = c.str(5),
                    dtStart = c.getLong(6),
                    dtEnd = c.longOrNull(7),
                    duration = c.str(8),
                    timeZone = c.str(9),
                    endTimeZone = c.str(10),
                    allDay = c.intOrNull(11) == 1,
                    rrule = c.str(12),
                    rdate = c.str(13),
                    exrule = c.str(14),
                    exdate = c.str(15),
                    originalId = c.longOrNull(16),
                    originalSyncId = c.str(17),
                    originalInstanceTime = c.longOrNull(18),
                    originalAllDay = c.intOrNull(19) == 1,
                    status = c.intOrNull(20),
                    availability = c.intOrNull(21),
                    accessLevel = c.intOrNull(22),
                    organizer = c.str(23),
                )
            }
        }
        return rows
    }

    private fun queryAttendees(eventIds: List<Long>): Map<Long, List<IcsAttendee>> {
        val result = mutableMapOf<Long, MutableList<IcsAttendee>>()
        val projection = arrayOf(
            Attendees.EVENT_ID, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL,
            Attendees.ATTENDEE_TYPE, Attendees.ATTENDEE_STATUS,
        )
        for (chunk in eventIds.chunked(CHUNK)) {
            resolver.query(Attendees.CONTENT_URI, projection, inClause(Attendees.EVENT_ID, chunk), null, null)?.use { c ->
                while (c.moveToNext()) {
                    val email = c.str(2)?.takeIf { it.contains('@') } ?: continue
                    val type = c.intOrNull(3)
                    result.getOrPut(c.getLong(0)) { mutableListOf() } += IcsAttendee(
                        email = email,
                        name = c.str(1),
                        role = when (type) {
                            Attendees.TYPE_OPTIONAL -> AttendeeRole.OPTIONAL
                            Attendees.TYPE_NONE -> AttendeeRole.NONE
                            else -> AttendeeRole.REQUIRED
                        },
                        status = when (c.intOrNull(4)) {
                            Attendees.ATTENDEE_STATUS_ACCEPTED -> AttendeeStatus.ACCEPTED
                            Attendees.ATTENDEE_STATUS_DECLINED -> AttendeeStatus.DECLINED
                            Attendees.ATTENDEE_STATUS_TENTATIVE -> AttendeeStatus.TENTATIVE
                            else -> AttendeeStatus.NEEDS_ACTION
                        },
                        isResource = type == Attendees.TYPE_RESOURCE,
                    )
                }
            }
        }
        return result
    }

    private fun queryReminders(eventIds: List<Long>): Map<Long, List<Int>> {
        val result = mutableMapOf<Long, MutableList<Int>>()
        val projection = arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD)
        val popupMethods = setOf(Reminders.METHOD_DEFAULT, Reminders.METHOD_ALERT, Reminders.METHOD_ALARM)
        for (chunk in eventIds.chunked(CHUNK)) {
            resolver.query(Reminders.CONTENT_URI, projection, inClause(Reminders.EVENT_ID, chunk), null, null)?.use { c ->
                while (c.moveToNext()) {
                    val minutes = c.intOrNull(1) ?: continue
                    if (minutes < 0 || (c.intOrNull(2) ?: Reminders.METHOD_DEFAULT) !in popupMethods) continue
                    result.getOrPut(c.getLong(0)) { mutableListOf() } += minutes
                }
            }
        }
        return result
    }

    companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val CHUNK = 500
    }
}

/** One row of CalendarContract.Events (constants as stored by the provider). */
internal data class EventRow(
    val id: Long,
    val syncId: String?,
    val uid2445: String?,
    val title: String?,
    val description: String?,
    val location: String?,
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val timeZone: String?,
    val endTimeZone: String?,
    val allDay: Boolean,
    val rrule: String?,
    val rdate: String?,
    val exrule: String?,
    val exdate: String?,
    val originalId: Long?,
    val originalSyncId: String?,
    val originalInstanceTime: Long?,
    val originalAllDay: Boolean,
    val status: Int?,
    val availability: Int?,
    val accessLevel: Int?,
    val organizer: String?,
) {
    /** A modified or cancelled instance of a recurring event. */
    val isException: Boolean get() = originalId != null || originalSyncId != null
}

/**
 * Maps provider rows to iCalendar events: recurring-event exceptions share their master's UID
 * and get a RECURRENCE-ID; cancelled instances become EXDATEs of the master.
 */
internal object CalendarMapper {
    fun toIcs(
        calendar: CalendarSource.CalendarRef,
        rows: List<EventRow>,
        attendees: Map<Long, List<IcsAttendee>>,
        reminders: Map<Long, List<Int>>,
    ): IcsCalendar {
        val byId = rows.associateBy { it.id }
        val bySyncId = rows.filter { it.syncId != null }.associateBy { it.syncId!! }
        fun uidOf(row: EventRow): String = row.uid2445?.takeIf { it.isNotBlank() }
            ?: row.syncId?.takeIf { it.isNotBlank() }?.let { "$it@google.com" }
            ?: "local-${calendar.id}-${row.id}@syncer"

        fun masterOf(row: EventRow): EventRow? =
            row.originalId?.let { byId[it] } ?: row.originalSyncId?.let { bySyncId[it] }

        // Cancelled instances of recurring events become EXDATEs on their master.
        val extraExdates = mutableMapOf<Long, MutableList<Long>>()
        val events = mutableListOf<EventRow>()
        for (row in rows) {
            if (row.status == Events.STATUS_CANCELED) {
                if (row.isException && row.originalInstanceTime != null) {
                    masterOf(row)?.let { extraExdates.getOrPut(it.id) { mutableListOf() } += row.originalInstanceTime }
                }
                continue
            }
            events += row
        }

        val icsEvents = events.map { row ->
            val master = if (row.isException) masterOf(row) else null
            val uid = when {
                master != null -> uidOf(master)
                row.originalSyncId != null -> "${row.originalSyncId}@google.com"
                else -> uidOf(row)
            }
            val eventAttendees = attendees[row.id].orEmpty()
            // Google lists the organizer as the only attendee of events without guests.
            val guests = eventAttendees.filter { !it.email.equals(row.organizer, ignoreCase = true) }
            IcsEvent(
                uid = uid,
                summary = row.title,
                description = row.description,
                location = row.location,
                start = row.dtStart,
                end = row.dtEnd,
                duration = row.duration,
                timeZone = row.timeZone,
                endTimeZone = row.endTimeZone,
                allDay = row.allDay,
                rrules = row.rrule.ruleLines(),
                exrules = row.exrule.ruleLines(),
                rdates = row.rdate,
                exdates = row.exdate,
                extraExdates = extraExdates[row.id].orEmpty(),
                recurrenceId = if (row.isException) row.originalInstanceTime else null,
                recurrenceIdAllDay = row.originalAllDay,
                status = when (row.status) {
                    Events.STATUS_TENTATIVE -> EventStatus.TENTATIVE
                    Events.STATUS_CONFIRMED -> EventStatus.CONFIRMED
                    else -> null
                },
                transparent = when (row.availability) {
                    Events.AVAILABILITY_FREE -> true
                    Events.AVAILABILITY_BUSY, Events.AVAILABILITY_TENTATIVE -> false
                    else -> null
                },
                classification = when (row.accessLevel) {
                    Events.ACCESS_PUBLIC -> Classification.PUBLIC
                    Events.ACCESS_PRIVATE -> Classification.PRIVATE
                    Events.ACCESS_CONFIDENTIAL -> Classification.CONFIDENTIAL
                    else -> null
                },
                organizerEmail = row.organizer,
                organizerName = eventAttendees.firstOrNull { it.email.equals(row.organizer, ignoreCase = true) }?.name,
                attendees = if (guests.isEmpty()) emptyList() else eventAttendees,
                alarmMinutes = reminders[row.id].orEmpty(),
            )
        }
        return IcsCalendar(calendar.name, calendar.timeZone, calendar.color, icsEvents)
    }
}

internal fun Cursor.str(index: Int): String? = if (isNull(index)) null else getString(index)
internal fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
internal fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

/** Builds "column IN (1,2,3)" for numeric ids (safe: values are longs, not user input). */
internal fun inClause(column: String, ids: List<Long>): String = "$column IN (${ids.joinToString(",")})"

private fun String?.ruleLines(): List<String> =
    this?.split('\n', '\r')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
