package io.colens.report.common

/**
 * The historian vocabulary, as plain strings.
 *
 * Deliberately strings rather than SDK types: this module's `common` scope is unit-testable with
 * no Ignition and no Historian module on the classpath, and that property is worth more than the
 * small amount of type safety given up. The gateway scope maps these onto `AggregationType` and
 * `FillMode` at the edge, where it can also ask the provider what it actually supports.
 *
 * Names match `system.historian` exactly — the REST surface mirrors those functions, so a user who
 * knows Ignition scripting already knows this API and can paste aggregate names straight across.
 */
object Aggregates {

    /**
     * The 17 aggregates `system.historian.queryAggregatedPoints` accepts, in its own order.
     *
     * A provider need not implement all of them — `QueryEngine.getNativeAggregates()` reports what
     * a given one does — so this list is what the API ACCEPTS, not what will always succeed.
     */
    val NAMES: List<String> = listOf(
        "Average", // time-weighted
        "SimpleAverage", // unweighted mean of the raw samples
        "Sum",
        "Minimum",
        "Maximum",
        "MinMax",
        "LastValue",
        "Range",
        "Count",
        "CountOn",
        "CountOff",
        "DurationOn",
        "DurationOff",
        "Variance",
        "StdDev",
        "PctGood",
        "PctBad",
    )

    private val BY_LOWER: Map<String, String> = NAMES.associateBy { it.lowercase() }

    /**
     * Case-insensitive, because these arrive from a spreadsheet formula where nobody types
     * `SimpleAverage` with the capital A. Returns the canonical spelling, or null — callers turn
     * that into a 400 naming the valid values rather than quietly substituting a default, which
     * would return a plausible number computed the wrong way.
     */
    fun canonical(name: String): String? = BY_LOWER[name.trim().lowercase()]

    /**
     * How gaps between stored samples are filled, from `com.inductiveautomation.ignition.common.FillMode`.
     *
     * `PREV` is the one that matters most here: it is the stepwise curve with last-value
     * propagation that XLReporter's time-weighted calculations assume, and having it native means
     * not rebuilding it from raw samples.
     */
    val FILL_MODES: List<String> = listOf("DERIVED", "LINEAR", "PREV", "NULL", "NONE")

    private val FILL_BY_LOWER: Map<String, String> = FILL_MODES.associateBy { it.lowercase() }

    fun canonicalFillMode(name: String): String? = FILL_BY_LOWER[name.trim().lowercase()]

    /** `system.historian`'s return shapes. WIDE is one row per interval, a column per path. */
    val RETURN_FORMATS: List<String> = listOf("WIDE", "TALL", "CALCULATION")

    fun canonicalReturnFormat(name: String): String? =
        RETURN_FORMATS.firstOrNull { it.equals(name.trim(), ignoreCase = true) }
}
