package io.colens.report.common

/**
 * Assembles streaming historian points into a rectangular, time-ordered result.
 *
 * The historian publishes points through a callback, per key, with no guarantee about how the keys
 * interleave or that timestamps arrive in order — so rows cannot simply be appended. Each point is
 * placed by (timestamp, column), and any cell nothing was published for stays null rather than
 * shifting its neighbours left, which is what a naive append does when one tag has fewer points
 * than another.
 *
 * Deliberately free of every SDK type. The gateway's collectors are thin adapters over this, so
 * the part with the actual logic is testable with no Ignition, no Historian module and no gateway
 * — which is the only reason it has real test coverage at all, since a live historian is
 * expensive to stand up and impossible in CI.
 */
class RowAssembler<K>(
    keys: List<K>,
    columnNames: List<String>,
    private val defaultHeading: (K) -> String,
) {
    /**
     * Sorted so output is in time order without a separate sort pass, and without assuming the
     * provider emits in order — which is not part of its contract.
     */
    private val rowsByTime = sortedMapOf<Long, Array<Any?>>()

    /**
     * First index wins for a duplicated key. A caller that asks for the same (path, aggregate)
     * twice gets two columns, but the historian publishes one key — so the second column stays
     * empty rather than both silently showing the same values.
     */
    private val columnIndex: Map<K, Int> = buildMap {
        // putIfAbsent, NOT associate — associate keeps the LAST index for a repeated key, so the
        // data would land in the rightmost duplicate column and the first would read as empty.
        keys.forEachIndexed { index, key -> putIfAbsent(key, index) }
    }

    private val width = keys.size

    private val problems = LinkedHashSet<String>()

    val headings: List<String> = keys.indices.map { index ->
        columnNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: defaultHeading(keys[index])
    }

    /** Points for a key the query did not ask for are dropped rather than throwing. */
    fun record(key: K, epochMillis: Long, value: Any?) {
        val index = columnIndex[key] ?: return
        rowsByTime.getOrPut(epochMillis) { arrayOfNulls(width) }[index] = value
    }

    /**
     * A quality problem on one key.
     *
     * Collected, not thrown: one bad tag path must not blank an entire report, and a column that is
     * silently empty is indistinguishable from a tag that genuinely has no data in the window.
     * De-duplicated because a failing key can report per point.
     */
    fun recordKeyFailure(key: K, quality: String) {
        problems += "${defaultHeading(key)}: $quality"
    }

    fun recordFailure(message: String) {
        problems += message
    }

    fun columns(): List<String> = listOf(TIMESTAMP_COLUMN) + headings

    /** Row-major. Element 0 of each row is the timestamp in epoch millis. */
    fun rows(): List<List<Any?>> =
        rowsByTime.map { (millis, values) -> listOf<Any?>(millis) + values.asList() }

    fun warnings(): List<String> = problems.toList()

    companion object {
        const val TIMESTAMP_COLUMN = "timestamp"
    }
}
