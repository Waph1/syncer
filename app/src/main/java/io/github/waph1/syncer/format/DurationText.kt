package io.github.waph1.syncer.format

import java.text.Normalizer
import java.time.Duration
import java.util.Locale

/**
 * Free-text durations such as "2 settimane", "1 mese e 2 settimane", "36h", "1,5 giorni",
 * "un anno", "un mese e mezzo". Months count as 30 days and years as 365 days.
 */
object DurationText {
    sealed interface Result
    data class Valid(val duration: Duration) : Result
    data class Invalid(val message: String) : Result

    val ONE_HOUR: Duration = Duration.ofHours(1)
    val ONE_YEAR: Duration = Duration.ofDays(365)
    val FIFTEEN_MINUTES: Duration = Duration.ofMinutes(15)

    private enum class Span(val minutes: Long, val singular: String, val plural: String) {
        MINUTE(1, "minuto", "minuti"),
        HOUR(60, "ora", "ore"),
        DAY(24 * 60, "giorno", "giorni"),
        WEEK(7 * 24 * 60, "settimana", "settimane"),
        MONTH(30 * 24 * 60, "mese", "mesi"),
        YEAR(365 * 24 * 60, "anno", "anni"),
    }

    private val UNITS: Map<String, Span> = buildMap {
        listOf("min", "mins", "minuto", "minuti", "minute", "minutes").forEach { put(it, Span.MINUTE) }
        listOf("h", "hr", "hrs", "ora", "ore", "hour", "hours").forEach { put(it, Span.HOUR) }
        listOf("g", "gg", "d", "giorno", "giorni", "giornata", "giornate", "day", "days").forEach { put(it, Span.DAY) }
        listOf("sett", "settimana", "settimane", "w", "wk", "week", "weeks").forEach { put(it, Span.WEEK) }
        listOf("mese", "mesi", "month", "months", "mo").forEach { put(it, Span.MONTH) }
        listOf("a", "anno", "anni", "y", "yr", "year", "years").forEach { put(it, Span.YEAR) }
    }

    /** Words meaning "one", as in "un mese", "una settimana", "un'ora". */
    private val ONE = setOf("un", "una", "uno", "a", "an", "one")
    private val FILLERS = setOf("ogni", "e", "and", "every")
    /** "mezzo"/"mezza": half of the previous unit ("un mese e mezzo") or, before a unit, 0.5 ("mezz'ora"). */
    private val HALF = setOf("mezzo", "mezza", "mezz", "half")
    private val TOKEN = Regex("\\d+(?:[.,]\\d+)?|\\p{L}+")

    fun parse(text: String, min: Duration = ONE_HOUR, max: Duration = ONE_YEAR): Result {
        val tokens = TOKEN.findAll(normalize(text)).map { it.value }.toList()
        if (tokens.isEmpty()) return Invalid("Scrivi una durata, per esempio \"2 settimane\"")
        var totalMinutes = 0.0
        var amount: Double? = null
        var parts = 0
        var lastUnit: Span? = null
        for ((index, token) in tokens.withIndex()) {
            val number = token.replace(',', '.').toDoubleOrNull()
            when {
                number != null -> {
                    if (amount != null) return Invalid("Manca l'unità dopo ${format(amount)}")
                    amount = number
                }
                amount == null && token in ONE && tokens.size > 1 -> amount = 1.0
                token in FILLERS -> Unit
                token in HALF && amount == null -> {
                    val previous = lastUnit
                    val next = tokens.getOrNull(index + 1)
                    if (previous != null && (next == null || next !in UNITS)) totalMinutes += previous.minutes / 2.0
                    else amount = 0.5
                }
                token == "m" -> return Invalid("\"m\" è ambiguo: scrivi \"min\" o \"mesi\"")
                else -> {
                    val unit = UNITS[token] ?: return Invalid("Unità non riconosciuta: \"$token\"")
                    totalMinutes += (amount ?: 1.0) * unit.minutes
                    amount = null
                    lastUnit = unit
                    parts++
                }
            }
        }
        if (amount != null) return Invalid("Aggiungi l'unità: ore, giorni, settimane, mesi o anni")
        if (parts == 0) return Invalid("Scrivi una durata, per esempio \"2 settimane\"")
        val duration = Duration.ofMinutes(Math.round(totalMinutes))
        if (duration < min) return Invalid("Minimo ${format(min)}")
        if (duration > max) return Invalid("Massimo ${format(max)}")
        return Valid(duration)
    }

    /** Parses [text] or returns null (for values saved earlier, already validated). */
    fun durationOf(text: String?, min: Duration = ONE_HOUR, max: Duration = ONE_YEAR): Duration? =
        text?.let { (parse(it, min, max) as? Valid)?.duration }

    /** Human readable Italian form: "2 settimane", "1 giorno e 12 ore", "1 anno". */
    fun format(duration: Duration): String {
        val minutes = duration.toMinutes()
        if (minutes <= 0) return "0 minuti"
        // A single unit when exact (largest first), unless it makes a big number of hours or minutes.
        Span.entries.reversed().firstOrNull { minutes % it.minutes == 0L }?.let { unit ->
            val small = when (unit) {
                Span.MINUTE -> minutes < 60
                Span.HOUR -> minutes < 48 * 60
                else -> true
            }
            if (small) return plural(minutes / unit.minutes, unit)
        }
        val days = minutes / Span.DAY.minutes
        val hours = minutes % Span.DAY.minutes / 60
        val mins = minutes % 60
        val parts = listOfNotNull(
            days.takeIf { it > 0 }?.let { plural(it, Span.DAY) },
            hours.takeIf { it > 0 }?.let { plural(it, Span.HOUR) },
            mins.takeIf { it > 0 }?.let { plural(it, Span.MINUTE) },
        )
        return if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " e " + parts.last()
    }

    private fun format(amount: Double): String =
        if (amount == Math.floor(amount)) amount.toLong().toString() else amount.toString().replace('.', ',')

    private fun plural(n: Long, unit: Span) = "$n ${if (n == 1L) unit.singular else unit.plural}"

    /** Lower case, accents removed, "un'ora" → "un ora". */
    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('\'', ' ')
            .replace('’', ' ')
}
