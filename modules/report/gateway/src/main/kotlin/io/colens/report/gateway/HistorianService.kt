package io.colens.report.gateway

import com.inductiveautomation.historian.common.model.AggregationType
import com.inductiveautomation.historian.common.model.LegacyAggregateAdapter
import com.inductiveautomation.historian.common.model.TimeWindow
import com.inductiveautomation.historian.common.model.data.AggregatedPoint
import com.inductiveautomation.historian.common.model.data.AtomicPoint
import com.inductiveautomation.historian.common.model.data.DataPointType
import com.inductiveautomation.historian.common.model.options.AggregatedQueryKey
import com.inductiveautomation.historian.common.model.options.AggregatedQueryOptions
import com.inductiveautomation.historian.common.model.options.RawQueryKey
import com.inductiveautomation.historian.common.model.options.RawQueryOptions
import com.inductiveautomation.historian.gateway.api.HistorianManager
import com.inductiveautomation.historian.gateway.api.query.QueryEngine
import com.inductiveautomation.historian.gateway.api.query.processor.AggregatedPointProcessor
import com.inductiveautomation.historian.gateway.api.query.processor.ProcessingContext
import com.inductiveautomation.historian.gateway.api.query.processor.RawPointProcessor
import com.inductiveautomation.ignition.common.FillMode
import com.inductiveautomation.ignition.common.QualifiedPath
import com.inductiveautomation.ignition.common.model.values.QualityCode
import com.inductiveautomation.ignition.common.sqltags.history.AggregationMode
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.report.common.Aggregates
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Queries the 8.3 Historian, mirroring `system.historian`.
 *
 * The REST surface deliberately reflects `queryAggregatedPoints` and `queryRawPoints` parameter
 * for parameter, so an engineer who has written the scripting call already knows this API and can
 * move aggregate names, fill modes and return formats across unchanged. The report-shaped
 * `DataGroup` endpoint composes ON TOP of these rather than replacing them.
 *
 * Note this targets the Historian module's own API, not the legacy `tagHistoryManager`. That is
 * what makes per-column aggregation, calendar-aligned windows and fill modes native rather than
 * things this module would have to rebuild from raw samples.
 */
class HistorianService(private val context: GatewayContext) {

    /** How long a query may run before the caller gets an error instead of a hung request. */
    private val queryTimeout = java.time.Duration.ofMinutes(2)

    class HistorianUnavailable(message: String) : Exception(message)

    class QueryFailed(message: String) : Exception(message)

    fun historianNames(): List<String> = manager().historianNames.sorted()

    /**
     * Mirrors `system.historian.queryAggregatedPoints`.
     *
     * @param windowSize when set, aggregates are computed per window across the range — this is
     *   XLReporter's "Interval: Every 1 hour". Calendar-aware, so a MONTHS window gives one row
     *   per calendar month rather than per 30 days, which `returnSize` alone cannot express.
     * @param returnSize when set instead, the range is divided into this many equal intervals.
     */
    fun queryAggregatedPoints(
        historian: String,
        paths: List<String>,
        startTime: Instant,
        endTime: Instant,
        aggregates: List<String>,
        columnNames: List<String> = emptyList(),
        fillMode: String? = null,
        windowSize: Pair<Long, ChronoUnit>? = null,
        returnSize: Int? = null,
        includeBounds: Boolean = false,
    ): QueryResult {
        require(paths.isNotEmpty()) { "At least one path is required." }

        val fill = fillMode?.let { resolveFillMode(it) } ?: FillMode.PREV
        val modes = resolveAggregates(aggregates)

        /*
         * One key per (path, aggregate) pair, which is what makes per-column calculations
         * first-class here: the same tag can appear twice with Minimum and Maximum without
         * issuing two queries. Column order follows path-major order so it matches the order the
         * caller listed, which is the order the spreadsheet block expects.
         */
        val keys = paths.flatMap { path ->
            val qualified = QualifiedPath.parse(path)
            modes.map { mode -> AggregatedQueryKey.of(qualified, mode, fill) }
        }

        val builder = AggregatedQueryOptions.builder()
            .addQueryKeys(keys)
            .startTime(startTime)
            .endTime(endTime)

        if (includeBounds) builder.includeBounds() else builder.excludeBounds()
        windowSize?.let { (count, unit) -> builder.withTimeWindow(TimeWindow.of(count, unit)) }
        returnSize?.let { builder.returnSize(it) }

        val collector = AggregatedCollector(keys, columnNames)
        engine(historian).query(builder.build(), collector)
        collector.await()
        return collector.result(startTime, endTime)
    }

    /** Mirrors `system.historian.queryRawPoints`. */
    fun queryRawPoints(
        historian: String,
        paths: List<String>,
        startTime: Instant,
        endTime: Instant,
        columnNames: List<String> = emptyList(),
        returnSize: Int? = null,
        includeBounds: Boolean = false,
    ): QueryResult {
        require(paths.isNotEmpty()) { "At least one path is required." }

        val keys = paths.map { RawQueryKey(QualifiedPath.parse(it)) }

        val builder = RawQueryOptions.builder()
            .addQueryKeys(keys)
            .startTime(startTime)
            .endTime(endTime)

        if (includeBounds) builder.includeBounds() else builder.excludeBounds()
        returnSize?.let { builder.returnSize(it) }

        val collector = RawCollector(keys, columnNames)
        engine(historian).query(builder.build(), collector)
        collector.await()
        return collector.result(startTime, endTime)
    }

    // -----------------------------------------------------------------------

    private fun manager(): HistorianManager =
        HistorianManager.get(context)
            ?: throw HistorianUnavailable(
                "The Historian module is not available on this gateway. This module declares a " +
                    "required dependency on it, so this generally means the historian failed to start.",
            )

    private fun engine(historian: String): QueryEngine =
        manager().getQueryEngine(historian).orElseThrow {
            HistorianUnavailable(
                "No historian named '$historian'. Available: ${historianNames().joinToString(", ").ifEmpty { "none" }}.",
            )
        }

    /**
     * `system.historian`'s aggregate names ARE the legacy `AggregationMode` names, bridged by
     * `LegacyAggregateAdapter`. Validating through [Aggregates] first means an unknown name is a
     * readable error listing the valid ones, rather than an enum exception from deep inside a query.
     */
    private fun resolveAggregates(names: List<String>): List<AggregationType> {
        val requested = names.ifEmpty { listOf("Average") }
        return requested.map { raw ->
            val canonical = Aggregates.canonical(raw)
                ?: throw QueryFailed("Unknown aggregate '$raw'. Valid values: ${Aggregates.NAMES.joinToString(", ")}.")
            LegacyAggregateAdapter.of(AggregationMode.valueOf(canonical))
        }
    }

    private fun resolveFillMode(name: String): FillMode {
        val canonical = Aggregates.canonicalFillMode(name)
            ?: throw QueryFailed("Unknown fill mode '$name'. Valid values: ${Aggregates.FILL_MODES.joinToString(", ")}.")
        return FillMode.valueOf(canonical)
    }

    // -----------------------------------------------------------------------

    data class QueryResult(
        val startTime: Instant,
        val endTime: Instant,
        val columns: List<String>,
        /** Row-major. Element 0 of each row is the timestamp in epoch millis. */
        val rows: List<List<Any?>>,
        /** Per-column quality problems, keyed by column name. Reported, never silently zeroed. */
        val warnings: List<String>,
    )

    /**
     * Collects a streaming query into a WIDE result.
     *
     * The historian publishes points as it finds them, per key, in no guaranteed interleaving — so
     * rows are assembled into a timestamp-keyed map rather than appended. Using a sorted map keeps
     * the output in time order without a separate sort pass and without assuming the provider
     * emits in order, which is not part of the contract.
     */
    private abstract class Collector<K>(keys: List<K>, columnNames: List<String>) {
        private val latch = CountDownLatch(1)
        private val rowsByTime = sortedMapOf<Long, Array<Any?>>()
        protected val columnIndex: Map<K, Int> = keys.withIndex().associate { (i, k) -> k to i }
        private val headings: List<String> = keys.indices.map { i ->
            columnNames.getOrNull(i)?.takeIf { it.isNotBlank() } ?: defaultHeading(keys[i])
        }
        private val problems = mutableListOf<String>()

        protected abstract fun defaultHeading(key: K): String

        protected fun record(key: K, timestamp: Instant, value: Any?) {
            val index = columnIndex[key] ?: return
            val row = rowsByTime.getOrPut(timestamp.toEpochMilli()) { arrayOfNulls(columnIndex.size) }
            row[index] = value
        }

        fun recordKeyFailure(key: K, quality: QualityCode) {
            // Recorded rather than thrown: one bad tag path must not blank an entire report, and a
            // column that is silently empty is indistinguishable from a tag with no data.
            problems += "${defaultHeading(key)}: ${quality.name}"
        }

        fun fail(message: String) {
            problems += message
            latch.countDown()
        }

        fun finish() = latch.countDown()

        fun await(timeoutMinutes: Long = 2) {
            if (!latch.await(timeoutMinutes, TimeUnit.MINUTES)) {
                throw QueryFailed("The historian query did not complete within $timeoutMinutes minutes.")
            }
        }

        fun result(start: Instant, end: Instant) = QueryResult(
            startTime = start,
            endTime = end,
            columns = listOf("timestamp") + headings,
            rows = rowsByTime.map { (millis, values) -> listOf<Any?>(millis) + values.toList() },
            warnings = problems.toList(),
        )
    }

    private class AggregatedCollector(
        keys: List<AggregatedQueryKey>,
        columnNames: List<String>,
    ) : Collector<AggregatedQueryKey>(keys, columnNames), AggregatedPointProcessor {

        override fun defaultHeading(key: AggregatedQueryKey): String =
            "${io.colens.report.common.HistorianPaths.displayName(key.source().toString())} (${key.aggregationType().name()})"

        override fun onInitialize(ctx: ProcessingContext<AggregatedQueryKey, DataPointType>): Boolean = true

        override fun onPointAvailable(key: AggregatedQueryKey, point: AggregatedPoint<*, *>): Boolean {
            record(key, point.timestamp(), point.value())
            return true
        }

        override fun onKeyFailure(key: AggregatedQueryKey, quality: QualityCode) = recordKeyFailure(key, quality)

        override fun onError(quality: QualityCode) = fail("Historian query failed: ${quality.name}")

        override fun onError(e: Exception) = fail("Historian query failed: ${e.message}")

        override fun onComplete() = finish()
    }

    private class RawCollector(
        keys: List<RawQueryKey>,
        columnNames: List<String>,
    ) : Collector<RawQueryKey>(keys, columnNames), RawPointProcessor {

        override fun defaultHeading(key: RawQueryKey): String =
            io.colens.report.common.HistorianPaths.displayName(key.source().toString())

        override fun onInitialize(ctx: ProcessingContext<RawQueryKey, DataPointType>): Boolean = true

        override fun onPointAvailable(key: RawQueryKey, point: AtomicPoint<*>): Boolean {
            record(key, point.timestamp(), point.value())
            return true
        }

        override fun onKeyFailure(key: RawQueryKey, quality: QualityCode) = recordKeyFailure(key, quality)

        override fun onError(quality: QualityCode) = fail("Historian query failed: ${quality.name}")

        override fun onError(e: Exception) = fail("Historian query failed: ${e.message}")

        override fun onComplete() = finish()
    }
}
