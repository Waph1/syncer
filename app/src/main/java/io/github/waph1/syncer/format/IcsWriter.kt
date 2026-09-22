package io.github.waph1.syncer.format

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule
import java.util.Locale

/** Serializes an [IcsCalendar] to an RFC 5545 iCalendar document. */
class IcsWriter(private val prodId: String = DEFAULT_PRODID) {

    fun write(calendar: IcsCalendar, dtStamp: Instant): String {
        val usedZones = sortedMapOf<String, ZoneId>()
        val body = ContentLineBuilder()
        val stamp = UTC_DATE_TIME.format(dtStamp.atOffset(ZoneOffset.UTC))
        val events = calendar.events.sortedWith(
            compareBy<IcsEvent>({ it.uid }, { it.recurrenceId ?: Long.MIN_VALUE }),
        )
        for (event in events) writeEvent(body, event, stamp, usedZones)

        val out = ContentLineBuilder()
            .line("BEGIN:VCALENDAR")
            .line("VERSION:2.0")
            .line("PRODID:$prodId")
            .line("CALSCALE:GREGORIAN")
            .text("X-WR-CALNAME", calendar.name)
        calendarZone(calendar.timeZone)?.let { out.line("X-WR-TIMEZONE:$it") }
        calendar.color?.let { out.line("X-APPLE-CALENDAR-COLOR:" + String.format(Locale.ROOT, "#%06X", it and 0xFFFFFF)) }
        for ((id, zone) in usedZones) writeTimeZone(out, id, zone)
        return out.toString() + body.toString() + "END:VCALENDAR\r\n"
    }

    private fun writeEvent(out: ContentLineBuilder, e: IcsEvent, stamp: String, usedZones: MutableMap<String, ZoneId>) {
        val zone = if (e.allDay) null else zoneOrNull(e.timeZone)
        val endZone = if (e.allDay) null else zoneOrNull(e.endTimeZone) ?: zone
        zone?.let { usedZones[it.id] = it }

        out.line("BEGIN:VEVENT")
        out.line("UID:" + ContentLines.escapeText(e.uid))
        out.line("DTSTAMP:$stamp")

        if (e.allDay) {
            val startDate = utcDate(e.start)
            out.line("DTSTART;VALUE=DATE:" + DATE.format(startDate))
            var endDate = e.end?.let { utcDate(it) }
                ?: e.duration?.let { RfcDuration.parse(it) }?.let { startDate.plusDays(it.wholeDays()) }
                ?: startDate.plusDays(1)
            if (!endDate.isAfter(startDate)) endDate = startDate.plusDays(1)
            out.line("DTEND;VALUE=DATE:" + DATE.format(endDate))
        } else {
            out.line("DTSTART" + dateTime(e.start, zone))
            val end = e.end ?: e.duration?.let { RfcDuration.parse(it) }?.addTo(e.start, zone ?: ZoneOffset.UTC)
            if (end != null && end >= e.start) {
                endZone?.let { usedZones[it.id] = it }
                out.line("DTEND" + dateTime(end, endZone))
            }
        }

        e.recurrenceId?.let { rid ->
            if (e.recurrenceIdAllDay) {
                out.line("RECURRENCE-ID;VALUE=DATE:" + DATE.format(utcDate(rid)))
            } else {
                out.line("RECURRENCE-ID" + dateTime(rid, zone))
            }
        }

        for (rule in e.rrules) normalizeRule(rule, e.allDay, zone)?.let { out.line("RRULE:$it") }
        for (rule in e.exrules) normalizeRule(rule, e.allDay, zone)?.let { out.line("EXRULE:$it") }
        writeDateList(out, "RDATE", e.rdates, e.allDay, usedZones)
        writeDateList(out, "EXDATE", e.exdates, e.allDay, usedZones)
        if (e.extraExdates.isNotEmpty()) {
            val values = e.extraExdates.distinct().sorted()
            if (e.allDay) {
                out.line("EXDATE;VALUE=DATE:" + values.joinToString(",") { DATE.format(utcDate(it)) })
            } else {
                out.line("EXDATE:" + values.joinToString(",") { utcDateTime(it) })
            }
        }

        out.text("SUMMARY", e.summary)
        out.text("LOCATION", e.location)
        out.text("DESCRIPTION", e.description)
        e.status?.let { out.line("STATUS:${it.ics}") }
        e.transparent?.let { out.line("TRANSP:" + if (it) "TRANSPARENT" else "OPAQUE") }
        e.classification?.let { out.line("CLASS:${it.ics}") }

        if (e.attendees.isNotEmpty() && !e.organizerEmail.isNullOrBlank()) {
            val cn = e.organizerName?.takeIf { it.isNotBlank() }?.let { ";CN=" + ContentLines.paramValue(it) } ?: ""
            out.line("ORGANIZER$cn:mailto:${e.organizerEmail}")
        }
        for (a in e.attendees.sortedBy { it.email.lowercase(Locale.ROOT) }) {
            val params = buildString {
                a.name?.takeIf { it.isNotBlank() }?.let { append(";CN=").append(ContentLines.paramValue(it)) }
                if (a.isResource) append(";CUTYPE=RESOURCE")
                append(";ROLE=").append(a.role.ics)
                append(";PARTSTAT=").append(a.status.ics)
            }
            out.line("ATTENDEE$params:mailto:${a.email}")
        }

        for (minutes in e.alarmMinutes.distinct().sorted()) {
            out.line("BEGIN:VALARM")
            out.line("ACTION:DISPLAY")
            out.line("DESCRIPTION:" + ContentLines.escapeText(e.summary?.takeIf { it.isNotBlank() } ?: "Promemoria"))
            out.line("TRIGGER:" + triggerFor(minutes))
            out.line("END:VALARM")
        }
        out.line("END:VEVENT")
    }

    private fun dateTime(millis: Long, zone: ZoneId?): String =
        if (zone == null) ":" + utcDateTime(millis)
        else ";TZID=${zone.id}:" + LOCAL_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

    /**
     * Android stores RDATE/EXDATE as one or more lines of "[TZID;]value,value".
     * Values are grouped by type so each output line has a single value type.
     */
    private fun writeDateList(
        out: ContentLineBuilder,
        name: String,
        raw: String?,
        allDay: Boolean,
        usedZones: MutableMap<String, ZoneId>,
    ) {
        if (raw.isNullOrBlank()) return
        for (line in raw.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }) {
            val sep = line.indexOf(';')
            val tzid = if (sep > 0) line.substring(0, sep) else null
            val values = (if (sep > 0) line.substring(sep + 1) else line)
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val dates = mutableListOf<String>()
            val utc = mutableListOf<String>()
            val local = mutableListOf<String>()
            val periods = mutableListOf<String>()
            for (v in values) {
                when {
                    v.contains('/') -> periods += v
                    allDay || DATE_ONLY.matches(v) -> dates += v.take(8)
                    v.endsWith("Z") -> utc += v
                    else -> local += v
                }
            }
            if (dates.isNotEmpty()) out.line("$name;VALUE=DATE:" + dates.distinct().joinToString(","))
            if (utc.isNotEmpty()) out.line("$name:" + utc.joinToString(","))
            if (local.isNotEmpty()) {
                val zone = zoneOrNull(tzid)
                if (zone != null) {
                    usedZones[zone.id] = zone
                    out.line("$name;TZID=${zone.id}:" + local.joinToString(","))
                } else {
                    out.line("$name:" + local.joinToString(","))
                }
            }
            if (periods.isNotEmpty()) out.line("$name;VALUE=PERIOD:" + periods.joinToString(","))
        }
    }

    /** Strips a "RRULE:" prefix and aligns UNTIL with the DTSTART value type (RFC 5545 §3.3.10). */
    private fun normalizeRule(rule: String, allDay: Boolean, zone: ZoneId?): String? {
        val value = rule.trim().substringAfter(':', rule.trim()).trim()
        if (value.isEmpty()) return null
        return value.split(';').joinToString(";") { part ->
            if (!part.startsWith("UNTIL=", ignoreCase = true)) return@joinToString part
            val until = part.substring(6)
            when {
                allDay -> "UNTIL=" + until.take(8)
                DATE_ONLY.matches(until) -> {
                    // End of that day in the event's zone, expressed in UTC.
                    val date = LocalDate.parse(until, DATE)
                    val endOfDay = date.atTime(23, 59, 59).atZone(zone ?: ZoneOffset.UTC).toInstant()
                    "UNTIL=" + utcDateTime(endOfDay.toEpochMilli())
                }
                !until.endsWith("Z") && FLOATING.matches(until) -> {
                    val local = LocalDateTime.parse(until, LOCAL_DATE_TIME)
                    "UNTIL=" + utcDateTime(local.atZone(zone ?: ZoneOffset.UTC).toInstant().toEpochMilli())
                }
                else -> part
            }
        }
    }

    private fun triggerFor(minutesBefore: Int): String = when {
        minutesBefore == 0 -> "PT0S"
        minutesBefore > 0 -> "-PT${minutesBefore}M"
        else -> "PT${-minutesBefore}M"
    }

    private fun writeTimeZone(out: ContentLineBuilder, id: String, zone: ZoneId) {
        val rules = zone.rules
        out.line("BEGIN:VTIMEZONE")
        out.line("TZID:$id")
        val transitionRules = rules.transitionRules
        if (transitionRules.isEmpty()) {
            val offset = rules.getOffset(Instant.now())
            out.line("BEGIN:STANDARD")
            out.line("DTSTART:19700101T000000")
            out.line("TZOFFSETFROM:" + formatOffset(offset))
            out.line("TZOFFSETTO:" + formatOffset(offset))
            out.line("END:STANDARD")
        } else {
            for (rule in transitionRules) {
                val transition = rule.createTransition(1970)
                val component = if (rule.offsetAfter != rule.standardOffset) "DAYLIGHT" else "STANDARD"
                out.line("BEGIN:$component")
                out.line("DTSTART:" + LOCAL_DATE_TIME.format(transition.dateTimeBefore))
                out.line("TZOFFSETFROM:" + formatOffset(rule.offsetBefore))
                out.line("TZOFFSETTO:" + formatOffset(rule.offsetAfter))
                out.line("RRULE:FREQ=YEARLY;BYMONTH=${rule.month.value};" + byDay(rule))
                out.line("END:$component")
            }
        }
        out.line("END:VTIMEZONE")
    }

    private fun byDay(rule: ZoneOffsetTransitionRule): String {
        val dom = rule.dayOfMonthIndicator
        val dow = rule.dayOfWeek ?: return "BYMONTHDAY=$dom"
        val code = DAY_CODES.getValue(dow)
        return if (dom > 0) {
            // java.time compiles "lastSun" of 31-day months as "Sun>=25".
            if (dom + 6 == rule.month.maxLength()) "BYDAY=-1$code"
            else if ((dom - 1) % 7 == 0) "BYDAY=${(dom - 1) / 7 + 1}$code"
            else "BYDAY=$code;BYMONTHDAY=" + (dom..minOf(dom + 6, 31)).joinToString(",")
        } else {
            val back = -dom - 1
            if (back % 7 == 0) "BYDAY=-${back / 7 + 1}$code"
            else "BYDAY=$code;BYMONTHDAY=" + (dom - 6..dom).joinToString(",")
        }
    }

    private fun calendarZone(id: String?): String? = zoneOrNull(id)?.id

    companion object {
        const val DEFAULT_PRODID = "-//Syncer//Syncer for Android//IT"

        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
        private val LOCAL_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)
        private val UTC_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
        private val DATE_ONLY = Regex("\\d{8}")
        private val FLOATING = Regex("\\d{8}T\\d{6}")
        private val UTC_IDS = setOf("UTC", "ETC/UTC", "GMT", "ETC/GMT", "Z", "UNIVERSAL", "ZULU", "ETC/UNIVERSAL", "ETC/ZULU", "UCT", "ETC/UCT")
        private val DAY_CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
        )

        /** Returns the zone for an IANA id, or null for UTC / unknown ids (which are written in UTC). */
        internal fun zoneOrNull(id: String?): ZoneId? {
            if (id.isNullOrBlank() || id.uppercase(Locale.ROOT) in UTC_IDS) return null
            val zone = runCatching { ZoneId.of(id) }.getOrNull() ?: return null
            // Bare offsets ("+01:00", "GMT+01:00") are not valid TZIDs; UTC is equivalent for them.
            if (zone is ZoneOffset || zone.id.contains(':')) return null
            return zone
        }

        private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()

        private fun utcDateTime(millis: Long): String = UTC_DATE_TIME.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

        internal fun formatOffset(offset: ZoneOffset): String {
            val total = offset.totalSeconds
            val abs = kotlin.math.abs(total)
            val sign = if (total < 0) "-" else "+"
            val base = String.format(Locale.ROOT, "%s%02d%02d", sign, abs / 3600, abs % 3600 / 60)
            return if (abs % 60 != 0) base + String.format(Locale.ROOT, "%02d", abs % 60) else base
        }
    }
}

/** An RFC 2445/5545 duration, parsed leniently (Android stores e.g. "P3600S"). */
internal data class RfcDuration(val negative: Boolean, val days: Long, val seconds: Long) {

    /** Whole days for all-day events; a seconds-only duration is rounded to days. */
    fun wholeDays(): Long {
        val d = days + seconds / 86_400
        return (if (negative) -d else d).coerceAtLeast(1)
    }

    /** Adds this duration to [startMillis]: days are nominal (DST aware), seconds are exact. */
    fun addTo(startMillis: Long, zone: ZoneId): Long {
        val sign = if (negative) -1 else 1
        return Instant.ofEpochMilli(startMillis).atZone(zone)
            .plusDays(sign * days)
            .plusSeconds(sign * seconds)
            .toInstant().toEpochMilli()
    }

    companion object {
        fun parse(text: String): RfcDuration? {
            var s = text.trim().uppercase(Locale.ROOT)
            var negative = false
            if (s.startsWith("-") || s.startsWith("+")) {
                negative = s[0] == '-'
                s = s.substring(1)
            }
            if (!s.startsWith("P") || s.length < 3) return null
            var days = 0L
            var seconds = 0L
            var number = StringBuilder()
            var sawUnit = false
            for (c in s.substring(1)) {
                when {
                    c.isDigit() -> number.append(c)
                    c == 'T' -> if (number.isNotEmpty()) return null
                    else -> {
                        val n = number.toString().toLongOrNull() ?: return null
                        when (c) {
                            'W' -> days += n * 7
                            'D' -> days += n
                            'H' -> seconds += n * 3600
                            'M' -> seconds += n * 60
                            'S' -> seconds += n
                            else -> return null
                        }
                        number = StringBuilder()
                        sawUnit = true
                    }
                }
            }
            if (!sawUnit || number.isNotEmpty()) return null
            return RfcDuration(negative, days, seconds)
        }
    }
}
