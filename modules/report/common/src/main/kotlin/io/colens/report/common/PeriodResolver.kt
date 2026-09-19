package io.colens.report.common

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Resolves a [Period] against a wall-clock instant.
 *
 * Arithmetic goes through [ZonedDateTime] rather than epoch milliseconds, and that is load
 * bearing twice over: adding a month to 31 January must land on the last day of February rather
 * than overflowing into March, and adding a day across a DST boundary must keep the wall-clock
 * time rather than the elapsed hours. Millisecond arithmetic gets the first wrong always and the
 * second wrong twice a year, and in both cases the report is merely a bit off — never an error.
 *
 * Kept free of Ignition and POI imports so it is unit-testable on its own.
 */
object PeriodResolver {

    data class Resolved(val start: ZonedDateTime, val end: ZonedDateTime)

    class UnresolvablePeriod(message: String) : Exception(message)

    /**
     * @param clampEndToNow see [clampEnd]. On by default because it is what makes a template
     *   reusable rather than something the user re-dates every morning.
     * @param variables values for a [Period.Variable], by name.
     */
    fun resolve(
        period: Period,
        now: ZonedDateTime,
        clampEndToNow: Boolean = true,
        variables: Map<String, String> = emptyMap(),
    ): Resolved {
        val nominal = resolveNominal(period, now, variables)
        return if (clampEndToNow) clampEnd(nominal, now) else nominal
    }

    /**
     * An end that cannot be used becomes now.
     *
     * Three separate real situations collapse to the same sensible answer — an unparseable
     * variable, a range the user inverted, and a period that legitimately runs past the present —
     * and that answer is "report what exists so far". This single rule is what turns "current
     * month" into month-to-date, growing by a row each morning, instead of a month of empty
     * future intervals.
     */
    fun clampEnd(period: Resolved, now: ZonedDateTime): Resolved =
        if (!period.end.isAfter(period.start) || period.end.isAfter(now)) {
            period.copy(end = now)
        } else {
            period
        }

    private fun resolveNominal(
        period: Period,
        now: ZonedDateTime,
        variables: Map<String, String>,
    ): Resolved = when (period) {
        is Period.Absolute -> Resolved(
            parseLocal(period.start, now.zone, "start"),
            parseLocal(period.end, now.zone, "end"),
        )

        is Period.Variable -> Resolved(
            parseLocal(variables[period.startVariable], now.zone, period.startVariable),
            // An unset or unparseable END is recoverable — clampEnd turns it into now. An unset
            // START is not, and parseLocal throws for it above.
            runCatching { parseLocal(variables[period.endVariable], now.zone, period.endVariable) }
                .getOrElse { now },
        )

        is Period.Relative -> {
            val start = relativeStart(period.duration, period.anchor, period.startAt, period.align, now)
            Resolved(start, start.plus(period.duration))
        }

        is Period.Offset -> {
            val base = relativeStart(period.duration, period.anchor, period.startAt, period.align, now)
            val start = base.minus(period.offset)
            Resolved(start, start.plus(period.duration))
        }
    }

    /**
     * Where the current (or previous) period begins.
     *
     * With no [startAt] the period simply hangs off now. With one it snaps to the most recent
     * occurrence of that wall-clock time, which is what makes shift-aligned reports come out
     * right — and note the case that is easy to miss: evaluated at 05:00 against an 08:00 start,
     * today's 08:00 has not happened yet, so the period underway began at 08:00 YESTERDAY.
     * Snapping forwards there would report a period that has not begun.
     */
    private fun relativeStart(
        duration: Duration,
        anchor: PeriodAnchor,
        startAt: TimeOfDay?,
        align: Boolean,
        now: ZonedDateTime,
    ): ZonedDateTime {
        /*
         * Aligned: snap to the unit boundary FIRST, then apply the time of day. That ordering is
         * what lets "the month starting on the 1st at 06:00" be said at all — snapping afterwards
         * would move the boundary to whichever day startAt last occurred on.
         */
        if (align) {
            var current = startOfUnit(now, duration.unit)
            if (startAt != null) current = current.withTimeOfDay(startAt)

            // A boundary still ahead of now belongs to the period that has not begun: on the 1st
            // at 03:00 with an 06:00 start, the month underway began last month.
            if (current.isAfter(now)) current = current.minus(duration)

            return if (anchor == PeriodAnchor.CURRENT) current else current.minus(duration)
        }

        if (startAt == null) {
            return if (anchor == PeriodAnchor.CURRENT) now else now.minus(duration)
        }

        val candidate = now.withTimeOfDay(startAt)
        val current = if (candidate.isAfter(now)) candidate.minus(duration) else candidate

        return if (anchor == PeriodAnchor.CURRENT) current else current.minus(duration)
    }

    /**
     * The start of the calendar unit containing [at].
     *
     * WEEK uses Monday, following ISO 8601 — a real choice rather than an obvious one, since plenty
     * of places treat Sunday as the first day. It is pinned by a golden case rather than left to
     * whatever the platform's locale happens to say.
     */
    private fun startOfUnit(at: ZonedDateTime, unit: DurationUnit): ZonedDateTime {
        val midnight = at.truncatedTo(ChronoUnit.DAYS)
        return when (unit) {
            DurationUnit.MINUTE -> at.truncatedTo(ChronoUnit.MINUTES)
            DurationUnit.HOUR -> at.truncatedTo(ChronoUnit.HOURS)
            DurationUnit.DAY -> midnight
            DurationUnit.WEEK -> midnight.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            DurationUnit.MONTH -> midnight.withDayOfMonth(1)
            DurationUnit.QUARTER -> midnight.withDayOfMonth(1)
                .withMonth(((at.monthValue - 1) / 3) * 3 + 1)
            DurationUnit.YEAR -> midnight.withDayOfYear(1)
        }
    }

    private fun ZonedDateTime.withTimeOfDay(time: TimeOfDay): ZonedDateTime =
        withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)

    private fun parseLocal(text: String?, zone: ZoneId, what: String): ZonedDateTime {
        if (text.isNullOrBlank()) throw UnresolvablePeriod("No value supplied for $what.")
        return try {
            LocalDateTime.parse(text).atZone(zone)
        } catch (e: DateTimeParseException) {
            throw UnresolvablePeriod("Could not read $what as a local date-time: '$text'. ${e.message}")
        }
    }
}

/**
 * Calendar-aware addition. QUARTER and YEAR go through [ZonedDateTime.plusMonths] so they inherit
 * its end-of-month clamping — plusYears on 29 February behaves the same way for the same reason.
 */
internal operator fun ZonedDateTime.plus(duration: Duration): ZonedDateTime = when (duration.unit) {
    DurationUnit.MINUTE -> plusMinutes(duration.count)
    DurationUnit.HOUR -> plusHours(duration.count)
    DurationUnit.DAY -> plusDays(duration.count)
    DurationUnit.WEEK -> plusWeeks(duration.count)
    DurationUnit.MONTH -> plusMonths(duration.count)
    DurationUnit.QUARTER -> plusMonths(duration.count * 3)
    DurationUnit.YEAR -> plusMonths(duration.count * 12)
}

internal operator fun ZonedDateTime.minus(duration: Duration): ZonedDateTime =
    plus(Duration(-duration.count, duration.unit))
