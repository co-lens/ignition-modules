package io.colens.mcp.gateway

import com.inductiveautomation.ignition.common.licensing.LicenseState
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy
import com.inductiveautomation.ignition.gateway.auth.apitoken.ApiTokenManager
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.mcp.common.Constants
import io.colens.mcp.common.McpJson
import io.colens.mcp.common.McpServer
import io.colens.mcp.common.ToolRegistry
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.put
import io.colens.mcp.gateway.licensing.TrialWatchdog
import io.colens.mcp.gateway.status.McpCounters
import io.colens.mcp.gateway.status.StatusEntity
import io.colens.mcp.gateway.status.StatusSnapshot
import io.colens.mcp.gateway.tools.DataTools
import io.colens.mcp.gateway.tools.PerspectiveTools
import io.colens.mcp.gateway.tools.ProjectTools
import io.colens.mcp.gateway.tools.SystemTools
import io.colens.mcp.gateway.tools.TagTools
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Optional

/**
 * Mounts the MCP server on the gateway's own Jetty, under `/data/mcp/`.
 *
 * Two endpoints, distinguished only by which tool registry they serve:
 *
 *  - `POST /data/mcp/mcp`          all tools, requires a valid API token with **write** permission
 *  - `POST /data/mcp/mcp-readonly` read-only tools, requires a valid API token
 *
 * That is the entire write-gating mechanism. Ignition's own API token permissions (managed in
 * the gateway UI) decide who can mutate; a read-scoped token can't even *see* the mutating
 * tools, because they aren't in the registry behind that route.
 *
 * `-Dmcp.gateway.allowAnonymousRead=true` drops the token requirement from the read-only
 * endpoint. Off by default, and loud when on — see [allowAnonymousRead].
 */
@Suppress("unused")
class GatewayHook : AbstractGatewayModuleHook() {

    private val logger: Logger = LoggerFactory.getLogger("mcp.Gateway")

    private lateinit var context: GatewayContext

    @Volatile private var fullServer: McpServer? = null

    @Volatile private var readOnlyServer: McpServer? = null

    @Volatile private var trialWatchdog: TrialWatchdog? = null

    /** Shared by both servers, so the card's "requests" counts everything this module answered. */
    private val counters = McpCounters()

    // Snapshotted at startup rather than recomputed per poll: readOnlyView() builds a whole new
    // registry, and the status card asks every ten seconds.
    @Volatile private var toolCount = 0

    @Volatile private var readOnlyToolCount = 0

    @Volatile private var perspectiveToolCount = 0

    override fun setup(context: GatewayContext) {
        this.context = context
    }

    override fun startup(activationState: LicenseState) {
        val perspective = perspectiveTools()
        val registry = ToolRegistry()
            .addAll(TagTools(context).tools())
            .addAll(ProjectTools(context).tools())
            .addAll(DataTools(context).tools())
            .addAll(SystemTools(context).tools())
            .addAll(perspective)

        val version = moduleVersion()
        val origins = extraAllowedOrigins()
        val readOnly = registry.readOnlyView()

        toolCount = registry.size
        readOnlyToolCount = readOnly.size
        perspectiveToolCount = perspective.size

        fullServer = McpServer(
            tools = registry,
            serverVersion = version,
            instructions = INSTRUCTIONS,
            extraAllowedOrigins = origins,
            listener = counters,
        )
        readOnlyServer = McpServer(
            tools = readOnly,
            serverVersion = version,
            instructions = INSTRUCTIONS,
            extraAllowedOrigins = origins,
            listener = counters,
        )

        logger.info(
            "Ignition MCP started: {} tools ({} read-only) at /data/{}/mcp",
            toolCount,
            readOnlyToolCount,
            Constants.SHORT_MODULE_ID,
        )

        // Opt-in, and only on a gateway actually running a trial. This works at all because
        // isFreeModule() below keeps this hook running while the platform trial is expired — a
        // demo-limited module would be shut down at the exact moment it needed to act.
        trialWatchdog = TrialWatchdog.createIfEnabled(context, logger)?.also { it.start() }

        publishStatus()
    }

    override fun shutdown() {
        // Before the servers go, so a stopped module reports nothing rather than its last numbers.
        runCatching { StatusEntity.removeMetrics(context) }
            .onFailure { logger.debug("Could not remove status metrics: {}", it.toString()) }
        trialWatchdog?.stop()
        trialWatchdog = null
        fullServer = null
        readOnlyServer = null
        logger.info("Ignition MCP gateway hook shut down.")
    }

    /**
     * Puts the module on the gateway's Services overview. Wrapped so it can never be the reason
     * the MCP server fails to come up: a card is a convenience, and this is the one part of
     * startup that depends on gateway UI internals rather than on anything we control.
     */
    private fun publishStatus() {
        val snapshot = StatusSnapshot(
            toolCount = toolCount,
            readOnlyToolCount = readOnlyToolCount,
            perspectiveToolCount = perspectiveToolCount,
            counters = counters,
            anonymousRead = allowAnonymousRead(),
            serversUp = { fullServer != null && readOnlyServer != null },
            watchdogState = { trialWatchdog?.state() ?: TrialWatchdog.State.OFF.label },
        )

        runCatching {
            StatusEntity.registerMetrics(context, snapshot)
            StatusEntity.registerEntity(context, logger)
        }.onFailure {
            logger.warn("Could not publish the gateway status card; the MCP server is unaffected.", it)
        }
    }

    override fun mountRouteHandlers(routes: RouteGroup) {
        // ApiTokenManager's token strategies rather than requirePermission(): the latter bundles
        // browser-session strategies that validate HTTP method safety — READ accepts only
        // GET/HEAD/OPTIONS and WRITE (being CSRF-aware) only unsafe methods — so neither can
        // guard a POST-only JSON-RPC endpoint. These validate the X-Ignition-API-Token header
        // and the token's security levels.
        //
        // These strategies are NOT sufficient on their own, which is the trap this code fell into
        // once already. TOKEN_ACCESS and TOKEN_WRITE resolve to the gateway-wide accessPermissions
        // and writePermissions properties, and a permission set that is an empty AllOf is
        // *vacuously true* — it grants everyone, including a caller who presented no token at all.
        // accessPermissions ships as exactly that empty AllOf, so TOKEN_ACCESS alone left the
        // read-only endpoint completely unauthenticated on a default gateway.
        //
        // So both routes are additionally gated on a token actually validating. TOKEN_READ is not
        // the alternative: those names describe gateway *configuration* rights, not data access,
        // and it resolves to readPermissions, which ships requiring Administrator — far too high a
        // bar for reading tag values. Net effect:
        //
        //   any valid API token                    -> the read-only tools
        //   token with gateway write permission    -> all of them, including tag writes,
        //                                             run_script, reset_trial and the file scan
        //
        // Deliberately no counts here: they went stale twice. The generated tool reference on the
        // docs site is the number that can't rot.
        val readAccess = if (allowAnonymousRead()) {
            logger.warn(
                "mcp.gateway.allowAnonymousRead is set: {}/mcp-readonly will answer requests that " +
                    "carry no API token. Every read-only tool — including run_query, " +
                    "read_project_resource and read_tags — is then available to anyone who can " +
                    "reach this gateway's web port. Intended for isolated dev gateways only.",
                "/data/${Constants.SHORT_MODULE_ID}",
            )
            ApiTokenManager.TOKEN_ACCESS
        } else {
            AccessControlStrategy.and(requireValidToken(), ApiTokenManager.TOKEN_ACCESS)
        }

        mountMcpRoute(
            routes,
            "/mcp",
            AccessControlStrategy.and(requireValidToken(), ApiTokenManager.TOKEN_WRITE),
        ) { fullServer }
        mountMcpRoute(routes, "/mcp-readonly", readAccess) { readOnlyServer }

        routes.newRoute("/health")
            .method(HttpMethod.GET)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .handler { _, _ ->
                McpJson.toString(jsonObject {
                    put("status", if (fullServer != null) "ok" else "starting")
                    put("server", Constants.SERVER_NAME)
                    put("version", moduleVersion())
                    put("mcpEndpoint", "/data/${Constants.SHORT_MODULE_ID}/mcp")
                    put("mcpReadOnlyEndpoint", "/data/${Constants.SHORT_MODULE_ID}/mcp-readonly")
                    put("tools", toolCount)
                    put("readOnlyTools", readOnlyToolCount)
                    put("perspectiveTools", perspectiveToolCount)
                    put("requests", counters.requestCount)
                    put("errors", counters.errorCount)
                    put("toolErrors", counters.toolErrorCount)
                    put("protocolErrors", counters.protocolErrorCount)
                    // Reported rather than hidden: an unauthenticated caller can already discover
                    // this by simply POSTing to the read-only endpoint and being answered.
                    put("anonymousRead", allowAnonymousRead())
                    put("trialWatchdog", trialWatchdog?.state() ?: TrialWatchdog.State.OFF.label)
                })
            }
            .mount()
    }

    private fun mountMcpRoute(
        routes: RouteGroup,
        path: String,
        access: AccessControlStrategy,
        server: () -> McpServer?,
    ) {
        routes.newRoute(path)
            .method(HttpMethod.POST)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(access)
            .handler(McpRouteHandler(server))
            .mount()

        // A spec-conformant client may GET the endpoint looking for a standalone SSE stream.
        // Answer 405 (what a modern-revision server is told to do) rather than 404, which the
        // client would read as "this isn't an MCP endpoint at all".
        //
        // This one is OPEN_ROUTE deliberately: requirePermission() bundles a CSRF strategy that
        // refuses to guard safe methods ("Mounted HTTP method 'GET' is not in the unsafe set"),
        // and the handler only ever replies 405 + Allow, so there is nothing here to protect.
        routes.newRoute(path)
            .method(HttpMethod.GET)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .handler(McpRouteHandler(server))
            .mount()
    }

    override fun getMountPathAlias(): Optional<String> = Optional.of(Constants.SHORT_MODULE_ID)

    override fun isFreeModule(): Boolean = true

    override fun isMakerEditionCompatible(): Boolean = true

    /**
     * Perspective is an optional dependency (compileOnly, and deliberately not declared in
     * `moduleDependencies`), so this module must still load on a gateway without it. Merely
     * constructing `PerspectiveTools` resolves Perspective classes, which is what would throw
     * `NoClassDefFoundError` — hence catching `Throwable` here rather than checking a flag.
     * The tools are then simply absent from `tools/list`, which is the honest answer.
     */
    private fun perspectiveTools(): List<io.colens.mcp.common.Tool> = try {
        PerspectiveTools(context).tools()
    } catch (t: Throwable) {
        logger.info("Perspective tools unavailable on this gateway: {}", t.toString())
        emptyList()
    }

    private fun moduleVersion(): String =
        javaClass.`package`?.implementationVersion ?: "dev"

    /**
     * Grants only when the request carries an `X-Ignition-API-Token` that Ignition validates.
     *
     * This is the check the `TOKEN_*` strategies do *not* make: they evaluate a permission set
     * against whatever identity the request has, and an empty permission set is satisfied by the
     * anonymous identity. Composed with them via [AccessControlStrategy.and] so a caller needs
     * both a real token and the permissions that token carries.
     */
    private fun requireValidToken() = AccessControlStrategy { request ->
        if (context.apiTokenManager.validateRequest(request).isPresent) {
            RouteAccess.GRANTED
        } else {
            RouteAccess.UNAUTHORIZED
        }
    }

    /**
     * `-Dmcp.gateway.allowAnonymousRead=true` serves the read-only endpoint without requiring an
     * API token, for a dev gateway where issuing one per client is friction with no benefit.
     *
     * Off by default: on a default gateway `accessPermissions` is an empty AllOf, so leaving the
     * token requirement off means no credential of any kind is checked. Note this relaxes only
     * *this* module's requirement — a gateway whose accessPermissions have been tightened still
     * enforces them, because TOKEN_ACCESS remains in the chain either way.
     */
    private fun allowAnonymousRead(): Boolean =
        System.getProperty("mcp.gateway.allowAnonymousRead").toBoolean()

    /**
     * Origins to allow in addition to loopback, e.g. when a browser-based MCP client is served
     * from elsewhere: `-Dmcp.allowedOrigins=https://tools.example.com,https://other.example.com`
     */
    private fun extraAllowedOrigins(): Set<String> =
        System.getProperty("mcp.allowedOrigins")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    private companion object {
        const val INSTRUCTIONS = """
This server exposes an Ignition gateway: tags, project resources, databases, tag history,
alarms and gateway logs.

Tag paths use Ignition syntax: [provider]Folder/TagName. Omitting [provider] uses 'default'.

Project resources are addressed by module id and path, e.g. module 'com.inductiveautomation.perspective'
type 'views' path 'Page/Main'. read_project_resource returns the raw resource data — view.json for
Perspective views, code.py for scripts.

Prefer browse_tags before read_tags so you use real paths rather than guessing them.
"""
    }
}
