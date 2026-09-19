package io.colens.report.common

/**
 * Time periods, as authored in Excel and resolved here.
 *
 * The gateway is AUTHORITATIVE: the add-in sends a symbolic period and never a resolved range.
 * If the browser resolved "previous day" in the user's zone while a schedule resolved it in the
 * gateway's, an interactive refresh and the 06:00 email would cover different hours and BOTH
 * would look plausible. The add-in keeps a local resolver purely so the period picker can show a
 * range without a round trip, and fixtures/period-cases.json pins the two together.
 *
 * Mirrors src/report/period.ts in the add-in repo.
 */

enum class DurationUnit { MINUTE, HOUR, DAY, WEEK, MONTH, QUARTER, YEAR }

data class Duration(val count: Long, val unit: DurationUnit)

/**
 * Wall-clock time of day, not an offset from UTC.
 *
 * A plant's 06:00 shift start is 06:00 local on both sides of a DST boundary. Storing an offset
 * would silently shift every shift report by an hour, twice a year.
 */
data class TimeOfDay(val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23) { "hour must be 0..23, was $hour" }
        require(minute in 0..59) { "minute must be 0..59, was $minute" }
    }
}

enum class PeriodAnchor { CURRENT, PREVIOUS }

sealed interface Period {
    /**
     * Anchored to the current or previous unit, optionally snapped to a time of day.
     *
     * [align] decides whether the unit is CALENDAR or ROLLING, and the difference is the whole
     * point of the flag. Unaligned reproduces XLReporter's Relative type exactly: "current month"
     * on 12 April runs 12 April to 12 May. Aligned, the same settings give month-to-date, and
     * `previous` gives the whole previous calendar month — the periods reports are actually
     * written against, which XLReporter reaches only through its separate Endpoint type.
     */
    data class Relative(
        val duration: Duration,
        val anchor: PeriodAnchor,
        val startAt: TimeOfDay? = null,
        val align: Boolean = false,
    ) : Period

    /** Relative, shifted back by a further duration — "the day before yesterday". */
    data class Offset(
        val duration: Duration,
        val anchor: PeriodAnchor,
        val offset: Duration,
        val startAt: TimeOfDay? = null,
        val align: Boolean = false,
    ) : Period

    /** Both ends pinned, for a one-off report. Local wall-clock, no offset. */
    data class Absolute(val start: String, val end: String) : Period

    /** Both ends deferred to values supplied at run time — a cell, a prompt, or a batch record. */
    data class Variable(val startVariable: String, val endVariable: String) : Period
}
