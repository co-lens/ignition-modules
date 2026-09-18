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
import com.inductiveautomation.historian.common.model.data.DataPointFactory
import com.inductiveautomation.historian.gateway.api.query.QueryEngine
import com.inductiveautomation.historian.gateway.api.query.browsing.BrowsePublisher
import com.inductiveautomation.ignition.common.browsing.BrowseFilter
import com.inductiveautomation.historian.gateway.api.storage.StorageEngine
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
     * What each historian can actually aggregate.
     *
     * Aggregate support is PER PROVIDER, not per API — the module accepts all 17 of
     * `system.historian`'s names, and a given historian may implement a handful. Asking for one it
     * does not have fails the whole query with `Bad_Unsupported`, which the caller sees as a blank
     * column among correct ones.
     *
     * Exposing this is what lets the add-in offer only the aggregates a historian will honour,
     * turning a silent wrong-looking report into a choice the user never gets to make wrongly.
     */
    fun nativeAggregates(historian: String): NativeAggregates {
        val types = runCatching { engine(historian).nativeAggregates }.getOrElse { return NativeAggregates() }

        /*
         * A provider names its aggregates in its OWN vocabulary — the QuestDB historian reports
         * SIMPLE_AVERAGE, MAXIMUM, STANDARD_DEVIATION — while this API accepts the
         * system.historian spellings (SimpleAverage, Maximum, StdDev). Handing the raw list to a
         * caller would be actively misleading: every name in it is one the API rejects.
         *
         * They share an identifier space with AggregationMode, so the translation is by id rather
         * than by string munging, which would have to guess at StdDev vs STANDARD_DEVIATION.
         *
         * Names that map to nothing are still reported, separately: a provider may offer
         * aggregates with no legacy equivalent at all (QuestDB has KSUM, FIRST_NOT_NULL,
         * APPROX_COUNT_DISTINCT). They are unusable through this API today, and saying so is
         * better than hiding capability the gateway genuinely has.
         */
        val byId = AggregationMode.values().associateBy { it.id }
        val supported = mutableListOf<String>()
        val providerOnly = mutableListOf<String>()

        for (type in types) {
            val mode = byId[type.identifier()]
            if (mode != null) supported += mode.name else providerOnly += type.name()
        }

        return NativeAggregates(supported.sorted(), providerOnly.sorted())
    }

    /**
     * Mirrors `system.historian.storeDataPoints`.
     *
     * Writes points straight into a historian, bypassing tag logging entirely. That is what the
     * scripting function does and it is the whole reason it exists — backfilling a gap, importing
     * from another system, and (not least) putting known data in front of a report so its numbers
     * can be checked against an answer somebody worked out by hand.
     *
     * Write-scoped: this is the only route in the module that changes anything.
     */
    fun storeDataPoints(historian: String, points: List<PointToStore>): StoreResult {
        require(points.isNotEmpty()) { "At least one point is required." }

        val atomic = points.map { point ->
            DataPointFactory.createAtomicPoint<Any>(
                point.value,
                QualityCode.Good,
                point.timestamp,
                QualifiedPath.parse(point.path),
            )
        }

        /*
         * Blocking on the CompletionStage on purpose. The caller is an HTTP request that has to
         * report whether the write landed — answering 200 off the back of an unresolved future
         * would mean reporting success for points that are still in flight and may yet be
         * rejected.
         */
        val result = try {
            storage(historian).storeAtomic(atomic).toCompletableFuture()
                .get(queryTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            throw QueryFailed("Storing points failed: ${e.message}")
        }

        result.error().ifPresent { throw QueryFailed("Storing points failed: ${it.message}") }

        return StoreResult(
            stored = result.successfulData().size,
            failed = result.failedData().size,
            quality = result.qualityCode().name,
        )
    }

    /**
     * Mirrors `system.historian.browse`.
     *
     * Walks one level of a historian's node tree. One level, not recursive: a plant historian has
     * tens of thousands of tags, and a browser that fetches them all to show twelve is the
     * difference between a tag picker that opens instantly and one nobody uses.
     */
    fun browse(historian: String, path: String?, maxResults: Int = 500): List<BrowseNode> {
        val root = if (path.isNullOrBlank()) {
            QualifiedPath.Builder().build()
        } else {
            QualifiedPath.parse(path)
        }

        val filter = BrowseFilter()
        filter.maxResults = maxResults

        val collector = BrowseCollector(maxResults)
        engine(historian).browse(root, filter, collector)

        val parent = path?.trim().orEmpty()
        return collector.nodes
            /*
             * The provider hands back one SEGMENT — ("tag", "line2") — not a path. Accumulating it
             * here rather than in the client is deliberate: the qualified-path grammar
             * (`sys:x:/prov:y:/tag:z`, segments joined by ":/") is exactly the kind of thing three
             * different callers would each get subtly wrong.
             */
            .map { node ->
                val segment = "${node.name}:${node.value}"
                node.copy(
                    path = if (parent.isEmpty()) segment else "$parent:/$segment",
                    /*
                     * Only leaves get a queryPath. A folder has none — feeding one to a query
                     * returns nothing rather than erroring, so handing the client something that
                     * looks usable would invite exactly that.
                     */
                    queryPath = if (node.hasChildren) null else {
                        val full = if (parent.isEmpty()) segment else "$parent:/$segment"
                        "histprov:$historian:/$full"
                    },
                )
            }
            .sortedWith(compareBy({ !it.hasChildren }, { it.value.lowercase() }))
    }

    data class BrowseNode(
        /** The segment TYPE the provider reported — `sys`, `prov`, `tag`. */
        val name: String,
        /** The segment value: the gateway name, provider name, or tag/folder name. */
        val value: String,
        val hasChildren: Boolean,
        /** Full browse path of this node; pass it straight back to browse to drill in. */
        val path: String = "",
        /** Fully qualified path usable as a query `paths` entry. Null for folders. */
        val queryPath: String? = null,
        val displayPath: String? = null,
    )

    /**
     * Collects a browse into a list.
     *
     * The publisher contract is push-based and can be cancelled mid-walk, which is what
     * [isCanceled] is for — reporting true once the cap is reached stops the provider enumerating
     * a folder of fifty thousand tags we would only throw away.
     *
     * [recurse] deliberately returns `this` rather than a child collector. Recursion is not wanted
     * here (see [browse]), and returning this keeps any provider that calls it from failing on a
     * null.
     */
    private class BrowseCollector(private val maxResults: Int) : BrowsePublisher {
        val nodes = mutableListOf<BrowseNode>()

        override fun isCanceled(): Boolean = nodes.size >= maxResults

        override fun getResultCount(): Int = nodes.size

        override fun setContinuationPoint(point: String?) = Unit

        override fun setTotalResultsAvailable(total: Int) = Unit

        override fun newNode(name: String?, path: String?): BrowsePublisher.NodeBuilder =
            NodeCollector(name.orEmpty(), path.orEmpty())

        override fun recurse(): BrowsePublisher = this

        override fun recurse(path: QualifiedPath?): BrowsePublisher = this

        private inner class NodeCollector(
            private val name: String,
            private val value: String,
        ) : BrowsePublisher.NodeBuilder {
            private var hasChildren = false
            private var displayPath: String? = null

            /*
             * Every parameter here is NULLABLE, even though the Java interface does not say so.
             * The provider calls retiredTime(null) for any tag that has not been retired — which
             * is most of them — and Kotlin's generated non-null check turns that into
             * "Parameter specified as non-null is null" from inside the browse walk. The Java
             * signature is simply unannotated, so Kotlin's platform type defaulted to non-null and
             * was wrong.
             */
            override fun creationTime(instant: Instant?) = this

            override fun retiredTime(instant: Instant?) = this

            override fun metadata(point: com.inductiveautomation.historian.common.model.data.MetadataPoint?) = this

            override fun displayPath(path: com.inductiveautomation.ignition.common.Path?): BrowsePublisher.NodeBuilder {
                displayPath = path?.toString()
                return this
            }

            override fun hasChildren(value: Boolean): BrowsePublisher.NodeBuilder {
                hasChildren = value
                return this
            }

            /** Nothing is collected until add(); a builder that is never added is a node the
             *  provider decided against. */
            override fun add() {
                nodes += BrowseNode(name = name, value = value, hasChildren = hasChildren, displayPath = displayPath)
            }
        }
    }

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

        /*
         * CROSS PRODUCT: every aggregate applied to every path, path-major, which is what
         * `system.historian.queryAggregatedPoints` means by "a column per requested calculation".
         *
         * A REPORT does not want this. Its column grid pairs one path with one calculation per
         * column, so it goes through [queryColumns] instead. Conflating the two turned a
         * four-column group into sixteen columns, with the intended ones empty and the data off to
         * the right — and no error anywhere, which is the whole reason the two shapes are now
         * separate functions rather than one clever one.
         */
        return queryColumns(
            historian = historian,
            columns = resolveAggregates(aggregates).let { modes ->
                paths.flatMap { path -> modes.map { mode -> ColumnSpec(path, mode) } }
            },
            startTime = startTime,
            endTime = endTime,
            columnNames = columnNames,
            fillMode = fillMode,
            windowSize = windowSize,
            returnSize = returnSize,
            includeBounds = includeBounds,
        )
    }

    /** One (path, aggregate) pair, producing one output column. */
    data class ColumnSpec(val path: String, val aggregate: AggregationType)

    /**
     * Aggregated query over an explicit list of columns.
     *
     * The shape a report actually wants: every column names its own path AND its own calculation,
     * so one tag can appear three times as Minimum, Maximum and Average while a different tag in
     * the same group carries something else entirely.
     */
    fun queryColumns(
        historian: String,
        columns: List<ColumnSpec>,
        startTime: Instant,
        endTime: Instant,
        columnNames: List<String> = emptyList(),
        fillMode: String? = null,
        windowSize: Pair<Long, ChronoUnit>? = null,
        returnSize: Int? = null,
        includeBounds: Boolean = false,
    ): QueryResult {
        require(columns.isNotEmpty()) { "At least one column is required." }

        val fill = fillMode?.let { resolveFillMode(it) } ?: FillMode.PREV
        val keys = columns.map { column ->
            AggregatedQueryKey.of(QualifiedPath.parse(column.path), column.aggregate, fill)
        }

        /*
         * ONE QUERY PER KEY, even though the API takes a list of them.
         *
         * The QuestDB-backed Core historian cannot answer an AGGREGATED query carrying more than a
         * single key. Two aggregates on one path fail with
         *
         *   Cannot invoke "io.questdb.cairo.sql.Function.getTimestamp(...)" because the return
         *   value of "io.questdb.cairo.sql.VirtualFunctionRecord.getFunction(int)" is null
         *
         * and two paths with one aggregate fail with a bare null. Every key works perfectly alone.
         * Verified key by key against a live gateway; the raw query path has no such limit and
         * happily takes many paths at once, so this is specific to aggregation.
         *
         * This is fatal for this product if unhandled, because a report column grid is ENTIRELY
         * about asking for Minimum and Maximum and Average of several tags at once — the failure
         * would land on precisely the query every real report makes.
         *
         * The cost is one round trip per column. A forty-column group is forty queries, which is
         * acceptable for a report and is not negotiable while the provider behaves this way;
         * running them concurrently is the obvious optimisation, deliberately not taken yet
         * because correctness here was hard enough to establish.
         *
         * Unconditional rather than "only when there is more than one key", so the single-key case
         * takes the same path as every other and cannot rot. Results merge into one shared
         * assembler, which aligns them by timestamp.
         */
        val assembler = RowAssembler(keys, columnNames, ::aggregatedHeading)

        for (key in keys) {
            val builder = AggregatedQueryOptions.builder()
                .addQueryKey(key)
                .startTime(startTime)
                .endTime(endTime)

            if (includeBounds) builder.includeBounds() else builder.excludeBounds()
            returnSize?.let { builder.returnSize(it) }
            builder.withTimeWindow(timeWindow(windowSize, returnSize, startTime, endTime))

            val collector = AggregatedCollector(assembler)
            engine(historian).query(builder.build(), collector)
            collector.await(queryTimeout)
        }

        return QueryResult(
            startTime = startTime,
            endTime = endTime,
            columns = assembler.columns(),
            rows = assembler.rows(),
            warnings = assembler.warnings(),
        )
    }

    /** Path plus aggregate, because one tag legitimately appears several times in a group. */
    private fun aggregatedHeading(key: AggregatedQueryKey): String =
        "${HistorianPaths.displayName(key.source().toString())} (${key.aggregationType().name()})"

    /**
     * A time window is MANDATORY, despite being optional on the builder: leaving it unset throws
     * inside the historian with "Cannot invoke TimeWindow.size() because timeWindow is null",
     * which reaches the caller as a 500 with no usable cause.
     *
     * `system.historian.queryAggregatedPoints` has no windowSize parameter, so the default has to
     * reproduce what it does. Note the range is HALVED when nothing is specified rather than used
     * whole: a window equal to the full range is another shape QuestDB rejects, so "one value for
     * the period" has to be expressed as a window that at least nominally divides it.
     */
    private fun timeWindow(
        windowSize: Pair<Long, ChronoUnit>?,
        returnSize: Int?,
        startTime: Instant,
        endTime: Instant,
    ): TimeWindow {
        val rangeMillis = java.time.Duration.between(startTime, endTime).toMillis()
        return when {
            windowSize != null -> TimeWindow.of(windowSize.first, windowSize.second)
            returnSize != null && returnSize > 0 -> TimeWindow.ofMillis(maxOf(1, rangeMillis / returnSize))
            else -> TimeWindow.ofMillis(maxOf(1, rangeMillis / 2))
        }
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

        val assembler = RowAssembler(keys, columnNames) { HistorianPaths.displayName(it.source().toString()) }
        val collector = RawCollector(assembler)
        engine(historian).query(builder.build(), collector)
        collector.await(queryTimeout)

        return QueryResult(
            startTime = startTime,
            endTime = endTime,
            columns = assembler.columns(),
            rows = assembler.rows(),
            warnings = assembler.warnings(),
        )
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
    private fun storage(historian: String): StorageEngine {
        requireKnown(historian)
        return manager().getStorageEngine(historian).orElseThrow {
            HistorianUnavailable(
                "Historian '$historian' is configured but cannot store data — it most likely failed " +
                    "to start. Check the gateway logs for \"Error adding historian: $historian\".",
            )
        }
    }

    private fun requireKnown(historian: String) {
        val known = historianNames()
        if (historian !in known) {
            throw HistorianUnavailable(
                "No historian named '$historian'. Available: ${known.joinToString(", ").ifEmpty { "none" }}.",
            )
        }
    }

    private fun engine(historian: String): QueryEngine {
        requireKnown(historian)
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
    fun resolveAggregates(names: List<String>): List<AggregationType> {
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

    /**
     * `supported` uses this API's own vocabulary and is directly usable as an `aggregates` value.
     * `providerOnly` is what the historian offers under names with no system.historian equivalent.
     */
    data class NativeAggregates(
        val supported: List<String> = emptyList(),
        val providerOnly: List<String> = emptyList(),
    )

    data class PointToStore(val path: String, val timestamp: Instant, val value: Any)

    data class StoreResult(val stored: Int, val failed: Int, val quality: String)

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
     * Adapts the historian's streaming callbacks onto a SHARED [RowAssembler].
     *
     * Shared, not owned, because an aggregated query fans out into one call per aggregate and all
     * of them have to land in the same grid — the assembler aligns them by timestamp, so column 2
     * from the Minimum pass lines up with column 1 from the Average pass on the same row.
     *
     * Deliberately thin. Everything with logic in it lives in RowAssembler in `common`, where it
     * is unit-tested with no Ignition and no Historian module present. A live historian cannot be
     * stood up in CI, so anything that stays here is effectively untested.
     */
    private abstract class Collector<K>(protected val assembler: RowAssembler<K>) {
        private val latch = CountDownLatch(1)

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
    }

    private class AggregatedCollector(
        assembler: RowAssembler<AggregatedQueryKey>,
    ) : Collector<AggregatedQueryKey>(assembler), AggregatedPointProcessor {

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
        assembler: RowAssembler<RawQueryKey>,
    ) : Collector<RawQueryKey>(assembler), RawPointProcessor {

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
