package io.colens.report.common

/**
 * A data group: one fetch, placed at one spot in a workbook.
 *
 * This is the report-shaped request, as opposed to the `system.historian` mirror underneath it.
 * The difference that matters is the PERIOD: a group carries a symbolic one ("previous day,
 * hourly") rather than two timestamps, which is what makes a template reusable rather than
 * something the user re-dates every morning.
 *
 * The add-in never resolves that period itself. It sends the symbol and the gateway resolves it,
 * so an interactive refresh and a 06:00 scheduled render cannot disagree about which hours
 * "previous day" means — a disagreement that would look entirely plausible from either side.
 *
 * Mirrors src/report/definition.ts in the add-in repo.
 */
data class DataGroup(
    val id: String,
    val name: String,
    val period: Period,
    val interval: Interval,
    val columns: List<Column>,
    /** Which historian to query. Null means the gateway's first configured one. */
    val historian: String? = null,
    /** Defaults for expanding short tag paths; see [HistorianPaths]. */
    val tagProvider: String? = null,
    /**
     * Seconds of history fetched BEFORE the period start and discarded from the output.
     *
     * Every time-weighted calculation needs it: the stepwise curve has nothing to propagate into
     * the first interval without a sample from before the window. Omitting it makes the numbers
     * subtly low rather than missing — no error, just wrong data.
     */
    val leadTimeSeconds: Long = 0,
    /** Include the interval either side of the period. */
    val includeBounds: Boolean = false,
    /** How gaps between stored samples are filled. PREV is the stepwise last-value curve. */
    val fillMode: String? = null,
) {
    init {
        require(columns.isNotEmpty()) { "A data group needs at least one column." }
        require(columns.size <= MAX_COLUMNS) {
            "A data group is limited to $MAX_COLUMNS columns, was ${columns.size}."
        }
    }

    companion object {
        /** XLReporter caps a group at 40 items; kept so templates port across without surprise. */
        const val MAX_COLUMNS = 40
    }
}

/**
 * One output column: a tag, a calculation over it, and what to call it.
 *
 * Several columns routinely name the SAME tag with different calculations — Minimum and Maximum
 * and Average of one flow meter is the ordinary case, not an edge one.
 */
data class Column(
    val path: String,
    /** A `system.historian` aggregate name. Validated against [Aggregates]. */
    val aggregate: String = "Average",
    val heading: String? = null,
)

/**
 * How many rows the group produces.
 *
 * [Every] is calendar-aware where the unit is — one row per calendar MONTH, not per 30 days, which
 * a count-based division cannot express at all.
 */
sealed interface Interval {
    /** Divide the period into [count] equal intervals. */
    data class Count(val count: Int) : Interval {
        init {
            require(count > 0) { "Interval count must be positive, was $count." }
        }
    }

    /** One row per [duration]. */
    data class Every(val duration: Duration) : Interval

    /** Every stored value, with no aggregation. */
    data object All : Interval
}
