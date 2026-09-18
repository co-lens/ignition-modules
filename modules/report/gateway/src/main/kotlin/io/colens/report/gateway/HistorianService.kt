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
import io.colens.report.common.HistorianPaths
import io.colens.report.common.RowAssembler
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
        returnSize?.let { builder.returnSize(it) }

        /*
         * A time window is MANDATORY, despite being optional on the builder: leaving it unset
         * throws deep inside the historian with "Cannot invoke TimeWindow.size() because
         * timeWindow is null", which reaches the caller as a 500 with no usable cause. Found by
         * calling the endpoint, not by reading the API.
         *
         * `system.historian.queryAggregatedPoints` has no windowSize parameter at all, so the
         * default has to reproduce what it does: one aggregate value per path across the whole
         * range. Hence the ladder — an explicit window wins, then returnSize divides the range
         * into that many equal windows, and otherwise the window IS the range.
         */
        builder.withTimeWindow(
            when {
                windowSize != null -> TimeWindow.of(windowSize.first, windowSize.second)
                returnSize != null && returnSize > 0 ->
                    TimeWindow.ofMillis(maxOf(1, java.time.Duration.between(startTime, endTime).toMillis() / returnSize))
                else -> TimeWindow.ofMillis(maxOf(1, java.time.Duration.between(startTime, endTime).toMillis()))
            },
        )

        val collector = AggregatedCollector(keys, columnNames)
        engine(historian).query(builder.build(), collector)
        collector.await(queryTimeout)
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
        collector.await(queryTimeout)
        return collector.result(startTime, endTime)
    }

    // -----------------------------------------------------------------------

    private fun manager(): HistorianManager =
        HistorianManager.get(context)
            ?: throw HistorianUnavailable(
                "The Historian module is not available on this gateway. This module declares a " +
                    "required dependency on it, so this generally means the historian failed to start.",
            )

    /**
     * A configured historian and a QUERYABLE one are different things — a historian that failed to
     * start is still listed by name. Reporting both cases with one message produced the genuinely
     * useless "No historian named 'Internal'. Available: Internal.", so they are separated here.
     */
    private fun engine(historian: String): QueryEngine {
        val known = historianNames()
        if (historian !in known) {
            throw HistorianUnavailable(
                "No historian named '$historian'. Available: ${known.joinToString(", ").ifEmpty { "none" }}.",
            )
        }
        return manager().getQueryEngine(historian).orElseThrow {
            HistorianUnavailable(
                "Historian '$historian' is configured but not queryable — it most likely failed to " +
                    "start. Check the gateway logs for 'Error adding historian: $historian'.",
            )
        }
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
     * Adapts the historian's streaming callbacks onto [RowAssembler].
     *
     * Deliberately thin. Everything with logic in it — placement, ordering, gaps, de-duplicated
     * warnings — lives in RowAssembler in `common`, where it is unit-tested with no Ignition and
     * no Historian module present. A live historian cannot be stood up in CI, so anything that
     * stays here is effectively untested.
     */
    private abstract class Collector<K>(keys: List<K>, columnNames: List<String>) {
        private val latch = CountDownLatch(1)
        protected val assembler = RowAssembler(keys, columnNames, ::heading)

        protected abstract fun heading(key: K): String

        fun fail(message: String) {
            assembler.recordFailure(message)
            // Counted down, not left hanging: onComplete is not guaranteed after onError, and a
            // caller blocked on the latch would sit there until the timeout for no reason.
            latch.countDown()
        }

        fun finish() = latch.countDown()

        fun await(timeout: java.time.Duration) {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw QueryFailed("The historian query did not complete within ${timeout.toMinutes()} minutes.")
            }
        }

        fun result(start: Instant, end: Instant) = QueryResult(
            startTime = start,
            endTime = end,
            columns = assembler.columns(),
            rows = assembler.rows(),
            warnings = assembler.warnings(),
        )
    }

    private class AggregatedCollector(
        keys: List<AggregatedQueryKey>,
        columnNames: List<String>,
    ) : Collector<AggregatedQueryKey>(keys, columnNames), AggregatedPointProcessor {

        /** Path plus aggregate, because one tag legitimately appears several times in a group. */
        override fun heading(key: AggregatedQueryKey): String =
            "${HistorianPaths.displayName(key.source().toString())} (${key.aggregationType().name()})"

        override fun onInitialize(ctx: ProcessingContext<AggregatedQueryKey, DataPointType>): Boolean = true

        override fun onPointAvailable(key: AggregatedQueryKey, point: AggregatedPoint<*, *>): Boolean {
            assembler.record(key, point.timestamp().toEpochMilli(), point.value())
            return true
        }

        override fun onKeyFailure(key: AggregatedQueryKey, quality: QualityCode) =
            assembler.recordKeyFailure(key, quality.name)

        override fun onError(quality: QualityCode) = fail("Historian query failed: ${quality.name}")

        override fun onError(e: Exception) = fail("Historian query failed: ${e.message}")

        override fun onComplete() = finish()
    }

    private class RawCollector(
        keys: List<RawQueryKey>,
        columnNames: List<String>,
    ) : Collector<RawQueryKey>(keys, columnNames), RawPointProcessor {

        override fun heading(key: RawQueryKey): String =
            HistorianPaths.displayName(key.source().toString())

        override fun onInitialize(ctx: ProcessingContext<RawQueryKey, DataPointType>): Boolean = true

        override fun onPointAvailable(key: RawQueryKey, point: AtomicPoint<*>): Boolean {
            assembler.record(key, point.timestamp().toEpochMilli(), point.value())
            return true
        }

        override fun onKeyFailure(key: RawQueryKey, quality: QualityCode) =
            assembler.recordKeyFailure(key, quality.name)

        override fun onError(quality: QualityCode) = fail("Historian query failed: ${quality.name}")

        override fun onError(e: Exception) = fail("Historian query failed: ${e.message}")

        override fun onComplete() = finish()
    }
}
