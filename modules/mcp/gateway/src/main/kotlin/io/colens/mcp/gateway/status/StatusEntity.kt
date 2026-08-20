package io.colens.mcp.gateway.status

import com.codahale.metrics.Gauge
import com.codahale.metrics.health.HealthCheck
import com.inductiveautomation.ignition.gateway.metrics.MetricBuilder
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import com.inductiveautomation.ignition.gateway.web.nav.Section
import org.slf4j.Logger

/**
 * What the status card and the health endpoint read. Supplied by the hook at startup so this file
 * needs no knowledge of how the server is assembled.
 *
 * The two counts are snapshots rather than lambdas over the registry because the card polls every
 * ten seconds and `ToolRegistry.readOnlyView()` allocates an entire new registry per call.
 */
class StatusSnapshot(
    val toolCount: Int,
    val readOnlyToolCount: Int,
    val perspectiveToolCount: Int,
    val counters: McpCounters,
    val anonymousRead: Boolean,
    val devMode: Boolean,
    val serversUp: () -> Boolean,
    val watchdogState: () -> String,
)

/**
 * Publishes the module on **Configure → Services → Overview** as a card, and its numbers on
 * **Diagnostics → Metrics Dashboard**.
 *
 * Four things about the 8.3 gateway UI are invisible from the Java API and each one was
 * established by disassembling the shipped bundles. They are why this file looks the way it does:
 *
 *  1. **Only four metrics render.** `OverviewCard` does
 *     `metrics.length > 4 ? metrics.slice(0, 4) : metrics`. Anything past the fourth is dropped
 *     silently, which is why [ANONYMOUS_READ] and [WATCHDOG] are registered but left off the card
 *     and reported on `/data/mcp/health` instead.
 *  2. **Order is alphabetical by alias, not the order listed here.** The builder collects into a
 *     `TreeMap` and the UI iterates the serialised map; the ordered alias list is only an
 *     include-filter. Hence `errors, readOnlyTools, requests, tools`.
 *  3. **A counter renders as "N/A".** `MetricCounterSerializer` emits `{"type":"counter","count":N}`
 *     with no `value` field, and the card's value switch falls through to `metric.value ?? 'N/A'`.
 *     Every metric here is therefore a gauge, whatever the underlying counter is.
 *  4. **The visible label is the metric's `description`**, falling back to a camel-case split of
 *     the alias.
 */
internal object StatusEntity {

    /** Also the key the gateway dedupes on — see [registerEntity]. */
    const val ENTITY_NAME = "Ignition MCP"

    // Namespaced because these land in the gateway's global registry alongside every other
    // module's metrics, and show up by name on the metrics dashboard and in a diagnostics bundle.
    private const val TOOLS = "mcp.gateway.tools"
    private const val READ_ONLY = "mcp.gateway.tools.readOnly"
    private const val REQUESTS = "mcp.gateway.requests"
    private const val ERRORS = "mcp.gateway.errors"
    private const val ANONYMOUS_READ = "mcp.gateway.anonymousRead"
    private const val DEV_MODE = "mcp.gateway.devMode"
    private const val WATCHDOG = "mcp.gateway.trialWatchdog"
    private const val HEALTH = "mcp.gateway.status"

    private val ALL_METRICS =
        listOf(TOOLS, READ_ONLY, REQUESTS, ERRORS, ANONYMOUS_READ, DEV_MODE, WATCHDOG)

    /**
     * (Re-)registers every gauge and the health check.
     *
     * Deliberately `remove`-then-`register` rather than `getOrAddGauge`: after a module *upgrade*
     * the old name is still present but bound to the previous classloader's gauge, and getOrAdd
     * would hand that dead object back — the card would keep rendering, frozen, with no error
     * anywhere. Removing first also makes this safe when a previous `shutdown()` never ran.
     */
    fun registerMetrics(context: GatewayContext, snapshot: StatusSnapshot) {
        val registry = context.metricRegistry

        fun <T> gauge(name: String, description: String, value: () -> T) {
            registry.remove(name)
            registry.register(
                name,
                MetricBuilder.newBuilder().description(description).buildGauge(Gauge { value() }),
            )
        }

        gauge(TOOLS, "Tools") { snapshot.toolCount }
        gauge(READ_ONLY, "Read-only tools") { snapshot.readOnlyToolCount }
        gauge(REQUESTS, "Requests") { snapshot.counters.requestCount }
        gauge(ERRORS, "Errors") { snapshot.counters.errorCount }
        gauge(ANONYMOUS_READ, "Anonymous read") { if (snapshot.anonymousRead) "on" else "off" }
        gauge(DEV_MODE, "Dev mode") { if (snapshot.devMode) "ON — no credential required" else "off" }
        gauge(WATCHDOG, "Trial watchdog") { snapshot.watchdogState() }

        context.healthCheckRegistry.unregister(HEALTH)
        context.healthCheckRegistry.register(
            HEALTH,
            object : HealthCheck() {
                // Runs on every ten-second poll of the overview page, so it stays allocation-light
                // and does no I/O.
                override fun check(): Result =
                    if (snapshot.serversUp()) {
                        Result.healthy("${snapshot.toolCount} tools, ${snapshot.readOnlyToolCount} read-only")
                    } else {
                        Result.unhealthy("MCP server did not start")
                    }
            },
        )
    }

    /**
     * Registers the card itself, once per gateway process.
     *
     * Two SDK facts shape this:
     *
     * **There is no `unregister`.** `EntityManager` exposes only `register`/`find`, and `register`
     * throws `IllegalArgumentException` on a duplicate name (compared case-insensitively). So the
     * `find` guard is not defensive tidiness — without it the second module start of a gateway's
     * life throws.
     *
     * **Which is fine, because this entity holds nothing of ours.** Every diagnostic is registered
     * *by name*, never as a `Supplier`, and the builder invokes its `Consumer`s eagerly — so the
     * permanently-registered entity references only SDK objects and strings. It cannot pin this
     * module's classloader, and because the names resolve against the gateway's live registry on
     * each poll, a restarted module's fresh gauges are picked up automatically. A module that is
     * stopped rather than restarted leaves the names unresolved, and the card degrades to a title
     * with no metric tiles rather than showing stale numbers.
     *
     * The consequence to know about: the card's *shape* is fixed for the life of the gateway
     * process. Adding an alias needs a gateway restart, not a module restart.
     */
    fun registerEntity(context: GatewayContext, logger: Logger) {
        if (context.entityManager.find(ENTITY_NAME).isPresent) {
            logger.debug("Status entity '{}' already registered; leaving it alone.", ENTITY_NAME)
            return
        }

        context.entityManager.register(ENTITY_NAME) { entity ->
            entity.description { description ->
                description
                    .description("Model Context Protocol server")
                    // A stock gateway icon, verified to serve 200. This module mounts no
                    // resources of its own, so /res/mcp/... would 404 and render nothing.
                    .iconUrl("/res/sys/icons/services.svg")
                    .addDetail("mcpEndpoint", "/data/mcp/mcp")
                    .addDetail("mcpReadOnlyEndpoint", "/data/mcp/mcp-readonly")
            }
            entity.navigation(Section.SERVICES) { navigation ->
                // includeInDiagnosticOverview() is deliberately absent. That page reads
                // `actions[0].url` without checking the array is non-empty, so tagging an entity
                // with no navAction throws inside its render and blanks the whole page — and this
                // module has no gateway page worth linking to.
                navigation.includeInSectionOverview()
            }
            entity.diagnostics(context) { diagnostics ->
                diagnostics
                    .metric("errors", ERRORS)
                    .metric("readOnlyTools", READ_ONLY)
                    .metric("requests", REQUESTS)
                    .metric("tools", TOOLS)
                    .healthcheck("status", HEALTH)
            }
        }

        logger.debug("Registered status entity '{}' on the {} section.", ENTITY_NAME, Section.SERVICES)
    }

    /**
     * Drops our metrics so a stopped module reports nothing rather than its last known numbers.
     * The entity itself cannot be removed — see [registerEntity].
     */
    fun removeMetrics(context: GatewayContext) {
        ALL_METRICS.forEach { context.metricRegistry.remove(it) }
        context.healthCheckRegistry.unregister(HEALTH)
    }
}
