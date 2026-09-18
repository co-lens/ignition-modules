package io.colens.report.common

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Turns a [DataGroup] into the concrete arguments of a historian query.
 *
 * Kept apart from the gateway code, and free of every SDK type, so the interesting decisions —
 * which period the group covers, how lead time shifts the query start, what window an interval
 * becomes — are unit-testable with no Ignition and no historian present. The gateway layer is then
 * a translation of this into SDK calls and nothing more.
 */
object DataGroupPlan {

    data class Plan(
        /** What the caller asked for. The reported period, and what the output is trimmed to. */
        val periodStart: ZonedDateTime,
        val periodEnd: ZonedDateTime,
        /**
         * What is actually asked of the historian — [periodStart] less the group's lead time.
         *
         * Separate from [periodStart] because the extra history exists to seed time-weighted
         * calculations, not to appear in the report. Conflating them puts rows before the period
         * start into the user's sheet.
         */
        val queryStart: ZonedDateTime,
        val paths: List<String>,
        val aggregates: List<String>,
        val columnNames: List<String>,
        /** Null for a raw query. */
        val windowSize: Pair<Long, ChronoUnit>?,
        val returnSize: Int?,
        val raw: Boolean,
        val includeBounds: Boolean,
        val fillMode: String?,
    )

    class InvalidGroup(message: String) : Exception(message)

    fun plan(
        group: DataGroup,
        now: ZonedDateTime,
        defaults: HistorianPaths.Defaults,
        variables: Map<String, String> = emptyMap(),
    ): Plan {
        val resolved = PeriodResolver.resolve(group.period, now, clampEndToNow = true, variables = variables)

        val paths = group.columns.map { column ->
            try {
                HistorianPaths.qualify(column.path, defaults)
            } catch (e: IllegalArgumentException) {
                throw InvalidGroup("Column '${column.heading ?: column.path}': ${e.message}")
            }
        }

        val aggregates = group.columns.map { column ->
            Aggregates.canonical(column.aggregate)
                ?: throw InvalidGroup(
                    "Column '${column.heading ?: column.path}' asks for unknown aggregate " +
                        "'${column.aggregate}'. Valid values: ${Aggregates.NAMES.joinToString(", ")}.",
                )
        }

        /*
         * Headings are positional and must stay one-to-one with columns even when blank, or a
         * supplied name silently shifts onto the wrong series. A blank entry means "use the
         * default", which the query layer decides.
         */
        val columnNames = group.columns.mapIndexed { index, column ->
            column.heading ?: "${HistorianPaths.displayName(paths[index])} (${aggregates[index]})"
        }

        return Plan(
            periodStart = resolved.start,
            periodEnd = resolved.end,
            queryStart = resolved.start.minusSeconds(group.leadTimeSeconds),
            paths = paths,
            aggregates = aggregates,
            columnNames = columnNames,
            windowSize = windowSizeFor(group.interval),
            returnSize = (group.interval as? Interval.Count)?.count,
            raw = group.interval is Interval.All,
            includeBounds = group.includeBounds,
            fillMode = group.fillMode?.let {
                Aggregates.canonicalFillMode(it)
                    ?: throw InvalidGroup(
                        "Unknown fill mode '$it'. Valid values: ${Aggregates.FILL_MODES.joinToString(", ")}.",
                    )
            },
        )
    }

    /**
     * Only [Interval.Every] becomes a window. A count divides the range instead, and a raw pull
     * has no window at all — which is why the query layer must branch on `raw` rather than
     * treating a null window as "use a default".
     */
    private fun windowSizeFor(interval: Interval): Pair<Long, ChronoUnit>? = when (interval) {
        is Interval.Every -> interval.duration.toWindow()
        is Interval.Count, Interval.All -> null
    }
}

/**
 * A duration as a historian window.
 *
 * QUARTER has no ChronoUnit, so it becomes three MONTHS — and the COUNT has to be tripled with it.
 * Mapping the unit alone and keeping the count turns "every quarter" into "every month", which
 * produces a report with three times too many rows that each look perfectly reasonable.
 */
internal fun Duration.toWindow(): Pair<Long, ChronoUnit> = when (unit) {
    DurationUnit.MINUTE -> count to ChronoUnit.MINUTES
    DurationUnit.HOUR -> count to ChronoUnit.HOURS
    DurationUnit.DAY -> count to ChronoUnit.DAYS
    DurationUnit.WEEK -> count to ChronoUnit.WEEKS
    DurationUnit.MONTH -> count to ChronoUnit.MONTHS
    DurationUnit.QUARTER -> (count * 3) to ChronoUnit.MONTHS
    DurationUnit.YEAR -> count to ChronoUnit.YEARS
}
