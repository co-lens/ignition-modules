package io.colens.report.gateway

import com.inductiveautomation.ignition.common.gson.Gson
import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.ignition.common.gson.JsonParser
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import com.inductiveautomation.ignition.gateway.model.GatewaySystemProperties
import com.inductiveautomation.ignition.gateway.auth.apitoken.ApiTokenManager
import io.colens.report.common.Aggregates
import io.colens.report.common.DataGroup
import io.colens.report.common.DataGroupCodec
import io.colens.report.common.DataGroupPlan
import io.colens.report.common.HistorianPaths
import io.colens.report.common.PeriodResolver
import io.colens.report.common.Constants
import io.colens.report.common.DevMode
import jakarta.servlet.http.HttpServletResponse
import org.apache.poi.Version as PoiVersion
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Route table for `/data/report/`.
 *
 * The query routes mirror `system.historian` deliberately, down to parameter names: an engineer
 * who has written `system.historianService.queryAggregatedPoints(...)` can move paths, aggregates,
 * columnNames and returnSize across unchanged. The report-shaped endpoints that come later
 * compose on top of these rather than replacing them.
 *
 * AUTH. The strategy pattern here is copied from the MCP module along with its reasoning, because
 * the trap is not obvious and has been fallen into once already: ApiTokenManager.TOKEN_ACCESS and
 * TOKEN_WRITE resolve to the gateway-wide accessPermissions and writePermissions properties, and a
 * permission set that is an empty AllOf is *vacuously true* — it grants everyone, including a
 * caller who presented no token at all. accessPermissions ships as exactly that empty AllOf. So a
 * TOKEN_* strategy is never used alone; it is always AND-ed with a check that a token actually
 * validated.
 *
 * /health is open on purpose. It carries no plant data, and the person diagnosing an add-in that
 * will not load has no token yet — which is exactly when they need it.
 */
class ReportRoutes(
    private val context: GatewayContext,
    private val moduleVersion: () -> String,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val gson = Gson()
    private val historianService = HistorianService(context)

    fun mount(routes: RouteGroup) {
        if (DevMode.enabled()) {
            logger.warn(
                "{} is set: every /data/{}/api route will answer requests that carry no API token, " +
                    "so anyone who can reach this gateway's web port can read historical process " +
                    "data through it. Intended for an isolated dev gateway and nothing else.",
                DevMode.PROPERTY,
                Constants.SHORT_MODULE_ID,
            )
        }

        routes.newRoute("/health")
            .method(HttpMethod.GET)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .handler { _, _ -> gson.toJson(health()) }
            .mount()

        json(routes, "/api/historians", HttpMethod.GET, read()) { _ ->
            mapOf(
                "historians" to historianService.historianNames().map { name ->
                    mapOf(
                        "name" to name,
                        // What THIS historian can aggregate, which is a subset of what the API
                        // accepts. Asking for one it lacks fails the whole query.
                        "nativeAggregates" to historianService.nativeAggregates(name).supported,
                        "providerOnlyAggregates" to historianService.nativeAggregates(name).providerOnly,
                    )
                },
            )
        }

        /** Mirrors `system.historian.queryAggregatedPoints`. */
        json(routes, "/api/queryAggregatedPoints", HttpMethod.POST, read()) { body ->
            val request = body ?: throw BadRequest("A JSON body is required.")
            historianService.queryAggregatedPoints(
                historian = request.string("historian") ?: defaultHistorian(),
                paths = request.stringList("paths"),
                startTime = request.instant("startTime"),
                endTime = request.instant("endTime"),
                aggregates = request.optionalStringList("aggregates"),
                columnNames = request.optionalStringList("columnNames"),
                fillMode = request.string("fillMode"),
                windowSize = request.windowSize(),
                returnSize = request.int("returnSize"),
                includeBounds = request.bool("includeBounds") ?: false,
            ).asMap()
        }

        /**
         * Mirrors `system.historian.storeDataPoints`.
         *
         * WRITE-scoped — the only route in this module that changes anything. A read token must
         * not be able to fabricate history: a report whose numbers can be edited by whoever can
         * read it is worth nothing to anybody auditing it.
         */
        /**
         * The report-shaped query: a DataGroup in, a block of cells out.
         *
         * Everything else here is the `system.historian` mirror. This is the layer that makes it a
         * REPORT tool — it takes a symbolic period ("previous day, hourly"), resolves it HERE, and
         * composes the historian calls. The add-in never resolves a period itself, so an
         * interactive refresh and a 06:00 scheduled render cannot disagree about which hours
         * "previous day" covers.
         */
        json(routes, "/api/query", HttpMethod.POST, read()) { body ->
            queryGroup(DataGroupCodec.read(body ?: throw BadRequest("A JSON body is required.")))
        }

        /**
         * Resolves a period without running a query.
         *
         * The taskpane's period picker uses it to show what a setting actually covers. Same
         * resolver the query path uses, so a preview cannot promise one range and the report
         * deliver another.
         */
        json(routes, "/api/resolvePeriod", HttpMethod.POST, read()) { body ->
            val request = body ?: throw BadRequest("A JSON body is required.")
            val period = DataGroupCodec.readPeriod(
                request.getAsJsonObject("period") ?: throw BadRequest("'period' is required."),
            )
            val resolved = PeriodResolver.resolve(period, ZonedDateTime.now(ZoneId.systemDefault()))
            mapOf(
                "start" to resolved.start.toInstant().toString(),
                "end" to resolved.end.toInstant().toString(),
                "zone" to ZoneId.systemDefault().id,
            )
        }

        json(routes, "/api/storeDataPoints", HttpMethod.POST, write()) { body ->
            val request = body ?: throw BadRequest("A JSON body is required.")
            val points = request.getAsJsonArray("points")
                ?: throw BadRequest("'points' is required and must be an array.")

            historianService.storeDataPoints(
                historian = request.string("historian") ?: defaultHistorian(),
                points = points.mapIndexed { index, element ->
                    val point = element.asJsonObject
                    HistorianService.PointToStore(
                        path = point.string("path") ?: throw BadRequest("points[$index].path is required."),
                        timestamp = point.instant("timestamp"),
                        value = point.get("value")?.takeIf { !it.isJsonNull }?.asJsonPrimitive?.let {
                            when {
                                it.isNumber -> it.asDouble
                                it.isBoolean -> it.asBoolean
                                else -> it.asString
                            }
                        } ?: throw BadRequest("points[$index].value is required."),
                    )
                },
            ).let { mapOf("stored" to it.stored, "failed" to it.failed, "quality" to it.quality) }
        }

        /** Mirrors `system.historian.queryRawPoints`. */
        json(routes, "/api/queryRawPoints", HttpMethod.POST, read()) { body ->
            val request = body ?: throw BadRequest("A JSON body is required.")
            historianService.queryRawPoints(
                historian = request.string("historian") ?: defaultHistorian(),
                paths = request.stringList("paths"),
                startTime = request.instant("startTime"),
                endTime = request.instant("endTime"),
                columnNames = request.optionalStringList("columnNames"),
                returnSize = request.int("returnSize"),
                includeBounds = request.bool("includeBounds") ?: false,
            ).asMap()
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Runs a planned group.
     *
     * The plan decides everything interesting — which period, how lead time shifts the query
     * start, what window the interval becomes — in `common`, where it is unit-tested without a
     * gateway. This is the translation into historian calls and nothing more.
     */
    private fun queryGroup(group: DataGroup): Map<String, Any?> {
        val historian = group.historian ?: defaultHistorian()

        val plan = DataGroupPlan.plan(
            group = group,
            now = ZonedDateTime.now(ZoneId.systemDefault()),
            defaults = HistorianPaths.Defaults(
                historian = historian,
                // The gateway's own name, which is the "/sys:" segment of every historian path.
                // Not a constant: a path naming the wrong gateway resolves to nothing, and the
                // query then returns an empty column rather than an error.
                gateway = gatewayName(),
                tagProvider = group.tagProvider ?: "default",
            ),
        )

        val result = if (plan.raw) {
            historianService.queryRawPoints(
                historian = historian,
                paths = plan.paths,
                startTime = plan.queryStart.toInstant(),
                endTime = plan.periodEnd.toInstant(),
                columnNames = plan.columnNames,
                includeBounds = plan.includeBounds,
            )
        } else {
            // queryColumns, NOT queryAggregatedPoints: a plan's paths and aggregates are
            // PARALLEL, one pair per column, where queryAggregatedPoints crosses them. Crossing a
            // four-column group gives sixteen columns and no error.
            historianService.queryColumns(
                historian = historian,
                columns = plan.paths.zip(historianService.resolveAggregates(plan.aggregates))
                    .map { (path, aggregate) -> HistorianService.ColumnSpec(path, aggregate) },
                startTime = plan.queryStart.toInstant(),
                endTime = plan.periodEnd.toInstant(),
                columnNames = plan.columnNames,
                fillMode = plan.fillMode,
                windowSize = plan.windowSize,
                returnSize = plan.returnSize,
                includeBounds = plan.includeBounds,
            )
        }

        /*
         * Lead time is extra history fetched to seed time-weighted calculations, NOT part of the
         * report. Trimmed here rather than left in, because rows dated before the period start
         * land in the user's sheet above their own header and look like a bug in the report.
         */
        val periodStartMillis = plan.periodStart.toInstant().toEpochMilli()
        val rows = result.rows.filter { row -> (row.firstOrNull() as? Long ?: 0L) >= periodStartMillis }

        return mapOf(
            "groupId" to group.id,
            "resolved" to mapOf(
                "start" to plan.periodStart.toInstant().toString(),
                "end" to plan.periodEnd.toInstant().toString(),
                "zone" to plan.periodStart.zone.id,
            ),
            "columns" to result.columns,
            "rows" to rows,
            "warnings" to result.warnings,
        )
    }

    /**
     * The gateway's own name, which is the `/sys:` segment of every historian path.
     *
     * Not hard-coded and not guessed: a path naming the wrong gateway resolves to nothing, and the
     * query then comes back as an empty column rather than an error — a report full of blanks with
     * nothing to explain them. The fallback is only for a gateway that has somehow not been named,
     * where an empty segment is at least as likely to work as an invented one.
     */
    private fun gatewayName(): String =
        context.systemPropertiesManager.getPropertyValue(GatewaySystemProperties.SystemName) ?: ""

    private class BadRequest(message: String) : Exception(message)

    private fun write(): AccessControlStrategy =
        if (DevMode.enabled()) {
            AccessControlStrategy.OPEN_ROUTE
        } else {
            AccessControlStrategy.and(requireValidToken(), ApiTokenManager.TOKEN_WRITE)
        }

    private fun read(): AccessControlStrategy =
        if (DevMode.enabled()) {
            // OPEN_ROUTE rather than a TOKEN_* strategy: those still consult the gateway's
            // permission sets, and the point of the flag is that nothing is consulted at all.
            AccessControlStrategy.OPEN_ROUTE
        } else {
            AccessControlStrategy.and(requireValidToken(), ApiTokenManager.TOKEN_ACCESS)
        }

    private fun requireValidToken() = AccessControlStrategy { request ->
        if (context.apiTokenManager.validateRequest(request).isPresent) {
            com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess.GRANTED
        } else {
            com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess.UNAUTHORIZED
        }
    }

    /**
     * Wraps a handler so every failure leaves as `{"error": {...}}` with a sensible status.
     *
     * Without this an exception becomes a 500 with a stack trace the caller cannot act on — and
     * the caller here is often a spreadsheet formula, which can only show the message it is given.
     */
    private fun json(
        routes: RouteGroup,
        path: String,
        method: HttpMethod,
        access: AccessControlStrategy,
        handler: (JsonObject?) -> Any,
    ) {
        routes.newRoute(path)
            .method(method)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(access)
            .handler { ctx, res -> gson.toJson(dispatch(ctx, res, handler)) }
            .mount()
    }

    private fun dispatch(ctx: RequestContext, res: HttpServletResponse, handler: (JsonObject?) -> Any): Any =
        try {
            handler(parseBody(ctx))
        } catch (e: BadRequest) {
            error(res, HttpServletResponse.SC_BAD_REQUEST, "BAD_REQUEST", e.message)
        } catch (e: IllegalArgumentException) {
            error(res, HttpServletResponse.SC_BAD_REQUEST, "BAD_REQUEST", e.message)
        } catch (e: DataGroupCodec.MalformedGroup) {
            error(res, HttpServletResponse.SC_BAD_REQUEST, "MALFORMED_GROUP", e.message)
        } catch (e: DataGroupPlan.InvalidGroup) {
            error(res, HttpServletResponse.SC_BAD_REQUEST, "INVALID_GROUP", e.message)
        } catch (e: PeriodResolver.UnresolvablePeriod) {
            error(res, HttpServletResponse.SC_BAD_REQUEST, "UNRESOLVABLE_PERIOD", e.message)
        } catch (e: HistorianService.QueryFailed) {
            error(res, HttpServletResponse.SC_BAD_REQUEST, "QUERY_FAILED", e.message)
        } catch (e: HistorianService.HistorianUnavailable) {
            error(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "HISTORIAN_UNAVAILABLE", e.message)
        } catch (e: Exception) {
            logger.warn("Unhandled error serving a report route.", e)
            error(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "INTERNAL", e.message)
        }

    private fun error(res: HttpServletResponse, status: Int, code: String, message: String?): Map<String, Any?> {
        res.status = status
        return mapOf("error" to mapOf("code" to code, "message" to (message ?: "No further detail.")))
    }

    /**
     * JSON bodies only. Deliberately `readBody()`, which returns a String — that is correct here
     * and WRONG for the template upload route added later: charset decoding silently corrupts a
     * zip, and the .xlsx then fails deep inside POI with an unrelated-looking error. Binary bodies
     * must read ctx.request.inputStream.
     */
    private fun parseBody(ctx: RequestContext): JsonObject? {
        val body = runCatching { ctx.readBody() }.getOrNull()
        if (body.isNullOrBlank()) return null
        return try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            throw BadRequest("Body was not a JSON object: ${e.message}")
        }
    }

    private fun defaultHistorian(): String =
        historianService.historianNames().firstOrNull()
            ?: throw HistorianService.HistorianUnavailable("No historians are configured on this gateway.")

    private fun health(): Map<String, Any?> = mapOf(
        "status" to "ok",
        "module" to Constants.MODULE_ID,
        "version" to moduleVersion(),
        "schemaVersion" to Constants.SCHEMA_VERSION,
        // POI is compileOnly against the 4.1.2 the platform ships in lib/core/common. If a future
        // Ignition moves it, code compiled here fails with NoSuchMethodError at RENDER time —
        // hours after a green build, inside a scheduled job nobody is watching. Reporting the
        // version actually loaded turns that into a five-second check.
        "poiVersion" to runCatching { PoiVersion.getVersion() }.getOrElse { "unavailable" },
        "historians" to runCatching { historianService.historianNames() }.getOrElse { emptyList() },
        "aggregates" to Aggregates.NAMES,
        "fillModes" to Aggregates.FILL_MODES,
        "addinUrl" to "/res/${Constants.SHORT_MODULE_ID}/addin/taskpane.html",
        "manifestUrl" to "/data/${Constants.SHORT_MODULE_ID}/manifest.xml",
        // The zone every schedule resolves against. Reported because a report that fires an hour
        // late twice a year is otherwise diagnosed by guesswork.
        "gatewayTimezone" to ZoneId.systemDefault().id,
        // Reported rather than hidden: an unauthenticated caller can already discover this by
        // simply calling a data route and being answered.
        "devMode" to DevMode.enabled(),
    )

    private fun HistorianService.QueryResult.asMap(): Map<String, Any?> = mapOf(
        "startTime" to startTime.toString(),
        "endTime" to endTime.toString(),
        "columns" to columns,
        "rows" to rows,
        "warnings" to warnings,
    )

    // --- request field accessors -------------------------------------------

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { !it.isJsonNull }?.asInt

    private fun JsonObject.bool(name: String): Boolean? = get(name)?.takeIf { !it.isJsonNull }?.asBoolean

    private fun JsonObject.stringList(name: String): List<String> =
        optionalStringList(name).ifEmpty { throw BadRequest("'$name' is required and must be a non-empty array.") }

    private fun JsonObject.optionalStringList(name: String): List<String> =
        getAsJsonArray(name)?.map { it.asString } ?: emptyList()

    /**
     * Accepts ISO-8601 (`2026-09-18T06:00:00Z`) or epoch millis.
     *
     * Both because the two callers differ: a spreadsheet formula has an Excel serial it converts
     * to millis, while a human writing curl writes ISO. Rejecting either would be a papercut on
     * every single call.
     */
    private fun JsonObject.instant(name: String): Instant {
        val element = get(name) ?: throw BadRequest("'$name' is required.")
        if (element.isJsonNull) throw BadRequest("'$name' is required.")
        return try {
            if (element.asJsonPrimitive.isNumber) Instant.ofEpochMilli(element.asLong)
            else Instant.parse(element.asString)
        } catch (e: Exception) {
            throw BadRequest("'$name' must be ISO-8601 or epoch milliseconds: ${e.message}")
        }
    }

    /**
     * `windowSize: {count, unit}` — the calendar-aware interval. MONTHS gives one row per calendar
     * month, which `returnSize` cannot express at all because it only divides a range into equal
     * parts.
     */
    private fun JsonObject.windowSize(): Pair<Long, ChronoUnit>? {
        val window = getAsJsonObject("windowSize") ?: return null
        val count = window.get("count")?.asLong ?: throw BadRequest("windowSize.count is required.")
        val rawUnit = window.string("unit") ?: throw BadRequest("windowSize.unit is required.")
        val unit = try {
            ChronoUnit.valueOf(rawUnit.uppercase())
        } catch (e: IllegalArgumentException) {
            throw BadRequest("Unknown windowSize.unit '$rawUnit'. Use MINUTES, HOURS, DAYS, WEEKS, MONTHS or YEARS.")
        }
        return count to unit
    }
}
