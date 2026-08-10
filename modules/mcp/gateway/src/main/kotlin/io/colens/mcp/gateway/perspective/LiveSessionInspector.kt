package io.colens.mcp.gateway.perspective

import com.codahale.metrics.Meter
import com.codahale.metrics.Timer
import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.perspective.common.config.ComponentConfig
import com.inductiveautomation.perspective.gateway.api.Component
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext
import com.inductiveautomation.perspective.gateway.model.ViewModel
import com.inductiveautomation.perspective.gateway.session.InternalSession
import com.inductiveautomation.perspective.gateway.session.PerspectiveSessionMonitor
import com.inductiveautomation.perspective.gateway.session.SessionStats
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.put
import io.colens.mcp.common.toJsonValue
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Reads running Perspective sessions.
 *
 * **Every use of Perspective's session and model internals is confined to this file.** Those
 * types (`InternalSession`, `PageModel`, `ViewModel`) are public but sit below the documented API
 * surface and could shift between Ignition releases; keeping them here means a future break is
 * one file to fix, and the two tools backed by it can fail without taking the rest down.
 *
 * Note there is no reflection here. Flint spends ~34 KB of Java reaching into `ComponentModel`'s
 * protected fields; walking the `ComponentConfig` tree alongside the live `Component` tree gets
 * the same information through public methods.
 */
class LiveSessionInspector(private val perspective: () -> PerspectiveContext?) {

    private val logger = LoggerFactory.getLogger("mcp.Perspective.Sessions")

    private fun context(): PerspectiveContext =
        perspective() ?: throw McpArgumentException("Perspective is not running on this gateway")

    /** Sessions, serialized with Perspective's own Gson so the shape matches the status page. */
    fun listSessions(projectFilter: String?): JsonObject {
        val ctx = context()
        val gson = ctx.sharedGson
        val monitor = ctx.sessionMonitor

        val sessions = JsonArray()
        monitor.sessionInfos.forEach { info ->
            val json = try {
                gson.toJsonTree(info).takeIf { it.isJsonObject }?.asJsonObject
            } catch (t: Throwable) {
                logger.debug("Could not serialize session info: {}", t.toString())
                null
            } ?: return@forEach

            if (projectFilter != null && json.get("project")?.asString != projectFilter) return@forEach
            sessions.add(json)
        }

        return jsonObject {
            put("count", sessions.size())
            put("pageCount", monitor.pageCount)
            put("viewCount", monitor.viewCount)
            put("componentCount", monitor.componentCount)
            put("bindingCount", monitor.bindingCount)
            put("sessions", sessions)
        }
    }

    /**
     * Walks a running view and reports, for every configured property, its binding and its
     * **current value and quality**.
     *
     * Perspective surfaces binding failures as quality overlays rather than exceptions, so a bad
     * quality next to the binding that produced it is usually the entire diagnosis.
     */
    fun diagnoseView(sessionId: String, viewFilter: String?): JsonObject {
        val session = findSession(sessionId)

        val views = JsonArray()
        var badCount = 0

        session.pages.forEach { page ->
            page.views.forEach { view ->
                val viewPath = viewPathOf(view)
                if (viewFilter != null && !viewPath.contains(viewFilter, ignoreCase = true)) return@forEach

                val properties = JsonArray()
                try {
                    walk(view.config?.root, view.rootContainer, "root", properties)
                } catch (t: Throwable) {
                    logger.debug("Could not walk view {}: {}", viewPath, t.toString())
                }

                badCount += properties.count {
                    it.asJsonObject.get("qualityGood")?.let { g -> g.isJsonPrimitive && !g.asBoolean } == true
                }

                views.add(jsonObject {
                    put("view", viewPath)
                    put("pageId", page.id)
                    put("propertyCount", properties.size())
                    put("properties", properties)
                })
            }
        }

        return jsonObject {
            put("sessionId", sessionId)
            put("project", session.projectName)
            put("viewCount", views.size())
            put("badQualityCount", badCount)
            put("views", views)
            if (views.size() == 0) {
                put("note", "No open views matched. Sessions only hold views that are currently displayed.")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Performance
    // -----------------------------------------------------------------------

    /**
     * What every running session is costing the gateway right now.
     *
     * The number to read first is `queueDepth`. Each session owns a single [ExecutionQueue][
     * com.inductiveautomation.ignition.common.util.ExecutionQueue] through which all its work is
     * serialized, so a queue that is not draining *is* the definition of a laggy session — and it
     * says so before any timer average moves.
     *
     * The gateway-wide timers come from [PerspectiveContext] itself, which is the documented API
     * surface; everything per-session reaches below it. Each field is read defensively for that
     * reason: one session that will not introspect degrades to nulls instead of failing the tool.
     */
    fun sessionPerformance(sortBy: String, limit: Int, includeViews: Boolean): JsonObject {
        // Nothing validates a tool's JSON Schema on the way in, so an enum argument is only an
        // enum if the handler says so. Rejecting beats silently sorting by something else.
        if (sortBy !in SORT_KEYS) {
            throw McpArgumentException(
                "Unknown sortBy '$sortBy'. Use one of: ${SORT_KEYS.joinToString(", ")}."
            )
        }

        val ctx = context()
        val monitor = ctx.sessionMonitor

        val samples = liveSessions(monitor).mapNotNull { session ->
            runCatching { sample(session) }.getOrElse {
                logger.debug("Could not sample session: {}", it.toString())
                null
            }
        }

        val ranked = samples.sortedByDescending {
            when (sortBy) {
                "uptime" -> it.uptimeSeconds?.toDouble()
                "bindings" -> it.bindingCount?.toDouble()
                "scriptTime" -> it.scriptMeanMillis
                else -> it.queueDepth?.toDouble()
            } ?: -1.0
        }

        return jsonObject {
            put("sortBy", sortBy)
            put("sessionCount", samples.size)
            put("truncated", samples.size > limit)
            put("gateway", jsonObject {
                put("pageCount", monitor.pageCount)
                put("viewCount", monitor.viewCount)
                put("componentCount", monitor.componentCount)
                put("bindingCount", monitor.bindingCount)
                put("scripts", timerJson(runCatching { ctx.scriptTimer }.getOrNull()))
                put("expressions", timerJson(runCatching { ctx.expressionTimer }.getOrNull()))
                put("propertyChanges", meterJson(runCatching { ctx.propertyChangeMeter }.getOrNull()))
            })
            put("sessions", jsonArrayOf(ranked.take(limit).map { sessionJson(it, includeViews) }))
        }
    }

    /**
     * Every running session, as the live objects rather than the info snapshots.
     *
     * The monitor has no "list all sessions" method: `getSessionInfos` returns
     * `PerspectiveSessionInfo`, which exposes no accessors at all — [listSessions] can only read it
     * by handing it to Perspective's own Gson — and `findSession` needs an id you would have to dig
     * back out of that JSON. `getSessionCount` takes a predicate over the real sessions and must
     * evaluate it against each one to produce a count, so it doubles as the visitor the API doesn't
     * otherwise offer. Public method, no reflection, no dependence on field names.
     */
    private fun liveSessions(monitor: PerspectiveSessionMonitor): List<InternalSession> {
        val found = mutableListOf<InternalSession>()
        runCatching {
            monitor.getSessionCount { session ->
                found += session
                true
            }
        }.onFailure { logger.debug("Could not enumerate sessions: {}", it.toString()) }
        return found
    }

    private fun sample(session: InternalSession): SessionSample {
        val stats = runCatching { session.sessionStats }.getOrNull()
        return SessionSample(
            session = session,
            stats = stats,
            queueDepth = runCatching { session.queue()?.size }.getOrNull(),
            queueIdle = runCatching { session.queue()?.isIdle }.getOrNull(),
            uptimeSeconds = runCatching { session.getUptime(TimeUnit.SECONDS) }.getOrNull(),
            bindingCount = runCatching { session.bindingCount }.getOrNull(),
            scriptMeanMillis = runCatching { stats?.scriptTimer?.snapshot?.mean?.let { it / 1_000_000.0 } }
                .getOrNull(),
        )
    }

    private fun sessionJson(sample: SessionSample, includeViews: Boolean): JsonObject {
        val session = sample.session
        val stats = sample.stats
        return jsonObject {
            put("sessionId", runCatching { session.sessionId?.toString() }.getOrNull())
            put("project", runCatching { session.projectName }.getOrNull())
            put("running", runCatching { session.isRunning }.getOrNull())
            put("uptimeSeconds", sample.uptimeSeconds)
            put("lastCommSeconds", runCatching { session.getLastComm(TimeUnit.SECONDS) }.getOrNull())
            put("pageCount", runCatching { session.pageCount }.getOrNull())
            put("viewCount", runCatching { session.viewCount }.getOrNull())
            put("componentCount", runCatching { session.componentCount }.getOrNull())
            put("bindingCount", sample.bindingCount)
            // The backpressure signal: all of a session's work runs through this one queue.
            put("queueDepth", sample.queueDepth)
            put("queueIdle", sample.queueIdle)
            put("queueTasks", timerJson(runCatching { stats?.queueTaskTimer }.getOrNull()))
            put("scripts", timerJson(runCatching { stats?.scriptTimer }.getOrNull()))
            put("expressions", timerJson(runCatching { stats?.expressionTimer }.getOrNull()))
            put("fetches", timerJson(runCatching { stats?.fetchTimer }.getOrNull()))
            put("propertyChanges", meterJson(runCatching { stats?.propertyChanges }.getOrNull()))
            put("messagesReceived", meterJson(runCatching { stats?.messagesRecv }.getOrNull()))
            put("messagesSent", meterJson(runCatching { stats?.messagesSent }.getOrNull()))
            if (includeViews) put("pages", pagesJson(session))
        }
    }

    /**
     * Open pages and the views mounted in them, with each view's age.
     *
     * `birthDate` is the closest thing Perspective exposes to a load time — there is no view-open
     * duration anywhere in the API. What it does answer is which views have been resident longest,
     * which is what you want when a session's component count has been climbing.
     *
     * **No per-view or per-page component and binding counts**, deliberately. `ViewModel` and
     * `PageModel` both guard those getters with `Must be executed in execution queue` — the walk
     * has to happen on the session's own queue. Submitting to that queue to satisfy a *diagnostic*
     * inverts the tool: the sessions worth measuring are exactly the ones whose queue is backed up,
     * and the tool would hang on precisely those. The session-level totals in [sessionJson] are
     * computed without the guard, so they are reported instead.
     */
    private fun pagesJson(session: InternalSession): JsonArray {
        val now = System.currentTimeMillis()
        val pages = runCatching { session.pages }.getOrNull().orEmpty()
        return jsonArrayOf(pages.map { page ->
            jsonObject {
                put("pageId", runCatching { page.id }.getOrNull())
                put("connected", runCatching { page.isConnected }.getOrNull())
                put("createdAgeSeconds", runCatching { (now - page.created) / 1000 }.getOrNull())
                put("viewCount", runCatching { page.viewCount }.getOrNull())
                put("views", jsonArrayOf(runCatching { page.views }.getOrNull().orEmpty().map { view ->
                    jsonObject {
                        put("view", runCatching { view.qualifiedPath }.getOrNull() ?: viewPathOf(view))
                        put("ageSeconds", runCatching { (now - view.birthDate) / 1000 }.getOrNull())
                    }
                }))
            }
        })
    }

    /** Dropwizard timers hold nanoseconds; nobody reading a report wants to divide by a billion. */
    private fun timerJson(timer: Timer?): JsonObject? {
        if (timer == null) return null
        val snapshot = runCatching { timer.snapshot }.getOrNull()
        return jsonObject {
            put("count", timer.count)
            put("meanRatePerSecond", round(timer.meanRate))
            put("oneMinuteRatePerSecond", round(timer.oneMinuteRate))
            put("meanMillis", snapshot?.mean?.let { round(it / 1_000_000.0) })
            put("p95Millis", snapshot?.get95thPercentile()?.let { round(it / 1_000_000.0) })
            put("p99Millis", snapshot?.get99thPercentile()?.let { round(it / 1_000_000.0) })
            put("maxMillis", snapshot?.max?.let { round(it / 1_000_000.0) })
        }
    }

    private fun meterJson(meter: Meter?): JsonObject? {
        if (meter == null) return null
        return jsonObject {
            put("count", meter.count)
            put("meanRatePerSecond", round(meter.meanRate))
            put("oneMinuteRatePerSecond", round(meter.oneMinuteRate))
        }
    }

    private fun round(value: Double): Double? =
        if (value.isFinite()) Math.round(value * 1000.0) / 1000.0 else null

    private companion object {
        val SORT_KEYS = listOf("queueDepth", "uptime", "bindings", "scriptTime")
    }

    private class SessionSample(
        val session: InternalSession,
        val stats: SessionStats?,
        val queueDepth: Int?,
        val queueIdle: Boolean?,
        val uptimeSeconds: Long?,
        val bindingCount: Int?,
        val scriptMeanMillis: Double?,
    )

    // -----------------------------------------------------------------------

    private fun findSession(sessionId: String): InternalSession {
        val monitor = context().sessionMonitor
        val uuid = try {
            UUID.fromString(sessionId)
        } catch (e: IllegalArgumentException) {
            throw McpArgumentException(
                "'$sessionId' is not a session id. Call perspective_list_sessions to get one."
            )
        }
        return monitor.findSession(uuid).orElse(null)
            ?: throw McpArgumentException("No running session with id '$sessionId'")
    }

    private fun viewPathOf(view: ViewModel): String =
        try {
            view.id?.resourcePath ?: view.name ?: "?"
        } catch (t: Throwable) {
            "?"
        }

    /**
     * Walks the static config tree and the live component tree together. The config supplies
     * *which* properties are configured; the live component supplies what they currently hold.
     */
    private fun walk(config: ComponentConfig?, component: Component?, path: String, into: JsonArray) {
        if (config == null) return

        val propConfig = config.propConfig
        propConfig?.properties?.forEach { key ->
            val propertyConfig = propConfig.findConfig(key).orElse(null)
            val binding = propertyConfig?.binding

            val live = try {
                component?.getPropertyTreeOf(key.scope)?.read(key.path)?.orElse(null)
            } catch (t: Throwable) {
                null
            }

            // A property with neither a binding nor a change script isn't interesting here.
            if (binding == null && propertyConfig?.onChange == null) return@forEach

            into.add(jsonObject {
                put("component", path)
                put("property", key.toString())
                put("bindingType", binding?.type)
                put("bindingConfig", binding?.config)
                put("bindingEnabled", binding?.let { runCatching { it.isEnabled }.getOrNull() })
                put("transformCount", binding?.transforms?.size)
                put("hasChangeScript", propertyConfig?.onChange != null)
                put("value", toJsonValue(live?.value))
                put("quality", live?.quality?.toString())
                put("qualityGood", live?.quality?.isGood)
                put("timestamp", live?.timestamp?.toInstant()?.toString())
            })
        }

        config.children?.forEachIndexed { index, childConfig ->
            val childComponent = try {
                component?.getChild(index)?.orElse(null)
            } catch (t: Throwable) {
                null
            }
            val name = childConfig.name ?: index.toString()
            walk(childConfig, childComponent, "$path/$name", into)
        }
    }
}
