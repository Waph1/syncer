package io.github.waph1.syncer.format

/** A calendar to be exported as one .ics file. */
data class IcsCalendar(
    val name: String,
    val timeZone: String?,
    /** ARGB color, if known. */
    val color: Int?,
    val events: List<IcsEvent>,
)

enum class EventStatus(val ics: String) { TENTATIVE("TENTATIVE"), CONFIRMED("CONFIRMED"), CANCELLED("CANCELLED") }

enum class Classification(val ics: String) { PUBLIC("PUBLIC"), PRIVATE("PRIVATE"), CONFIDENTIAL("CONFIDENTIAL") }

enum class AttendeeRole(val ics: String) { REQUIRED("REQ-PARTICIPANT"), OPTIONAL("OPT-PARTICIPANT"), NONE("NON-PARTICIPANT") }

enum class AttendeeStatus(val ics: String) {
    NEEDS_ACTION("NEEDS-ACTION"),
    ACCEPTED("ACCEPTED"),
    DECLINED("DECLINED"),
    TENTATIVE("TENTATIVE"),
}

data class IcsAttendee(
    val email: String,
    val name: String?,
    val role: AttendeeRole = AttendeeRole.REQUIRED,
    val status: AttendeeStatus = AttendeeStatus.NEEDS_ACTION,
    val isResource: Boolean = false,
)

/**
 * One VEVENT. Times are epoch milliseconds (UTC), as stored by Android's CalendarContract.
 * All-day events are stored at UTC midnight.
 */
data class IcsEvent(
    val uid: String,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val start: Long,
    /** Exclusive end; when null [duration] is used. */
    val end: Long? = null,
    /** RFC 2445 duration as stored by Android (e.g. "P3600S", "P1D"). */
    val duration: String? = null,
    /** IANA time zone of the start; null or UTC means UTC. */
    val timeZone: String? = null,
    val endTimeZone: String? = null,
    val allDay: Boolean = false,
    val rrules: List<String> = emptyList(),
    val exrules: List<String> = emptyList(),
    /** Raw RDATE / EXDATE strings in Android's format ("[TZID;]value,value" per line). */
    val rdates: String? = null,
    val exdates: String? = null,
    /** Extra excluded instances (original start times of cancelled exceptions). */
    val extraExdates: List<Long> = emptyList(),
    /** For modified instances of a recurring event: the original start time. */
    val recurrenceId: Long? = null,
    val recurrenceIdAllDay: Boolean = false,
    val status: EventStatus? = null,
    val transparent: Boolean? = null,
    val classification: Classification? = null,
    val organizerEmail: String? = null,
    val organizerName: String? = null,
    val attendees: List<IcsAttendee> = emptyList(),
    /** Reminder offsets in minutes before the start. */
    val alarmMinutes: List<Int> = emptyList(),
)
