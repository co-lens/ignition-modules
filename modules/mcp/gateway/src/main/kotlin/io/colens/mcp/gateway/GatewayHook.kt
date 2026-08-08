package io.colens.mcp.gateway

import com.inductiveautomation.ignition.common.licensing.LicenseState
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy
import com.inductiveautomation.ignition.gateway.auth.apitoken.ApiTokenManager
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod
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
 *  - `POST /data/mcp/mcp`          all tools, requires an API token with **write** permission
 *  - `POST /data/mcp/mcp-readonly` read-only tools, requires **read** permission
 *
 * That is the entire write-gating mechanism. Ignition's own API token permissions (managed in
 * the gateway UI) decide who can mutate; a read-scoped token can't even *see* the mutating
 * tools, because they aren't in the registry behind that route.
 */
@Suppress("unused")
class GatewayHook : AbstractGatewayModuleHook() {

    private val logger: Logger = LoggerFactory.getLogger("mcp.Gateway")

    private lateinit var context: GatewayContext

    @Volatile private var fullServer: McpServer? = null

    @Volatile private var readOnlyServer: McpServer? = null

    @Volatile private var trialWatchdog: TrialWatchdog? = null

    override fun setup(context: GatewayContext) {
        this.context = context
    }

    override fun startup(activationState: LicenseState) {
        val registry = ToolRegistry()
            .addAll(TagTools(context).tools())
            .addAll(ProjectTools(context).tools())
            .addAll(DataTools(context).tools())
            .addAll(SystemTools(context).tools())
            .addAll(perspectiveTools())

        val version = moduleVersion()
        val origins = extraAllowedOrigins()

        fullServer = McpServer(
            tools = registry,
            serverVersion = version,
            instructions = INSTRUCTIONS,
            extraAllowedOrigins = origins,
        )
        readOnlyServer = McpServer(
            tools = registry.readOnlyView(),
            serverVersion = version,
            instructions = INSTRUCTIONS,
            extraAllowedOrigins = origins,
        )

        logger.info(
            "Ignition MCP started: {} tools ({} read-only) at /data/{}/mcp",
            registry.size,
            registry.readOnlyView().size,
            Constants.SHORT_MODULE_ID,
        )

        // Opt-in, and only on a gateway actually running a trial. This works at all because
        // isFreeModule() below keeps this hook running while the platform trial is expired — a
        // demo-limited module would be shut down at the exact moment it needed to act.
        trialWatchdog = TrialWatchdog.createIfEnabled(context, logger)?.also { it.start() }
    }

    override fun shutdown() {
        trialWatchdog?.stop()
        trialWatchdog = null
        fullServer = null
        readOnlyServer = null
        logger.info("Ignition MCP gateway hook shut down.")
    }

    override fun mountRouteHandlers(routes: RouteGroup) {
        // ApiTokenManager's token strategies rather than requirePermission(): the latter bundles
        // browser-session strategies that validate HTTP method safety — READ accepts only
        // GET/HEAD/OPTIONS and WRITE (being CSRF-aware) only unsafe methods — so neither can
        // guard a POST-only JSON-RPC endpoint. These validate the X-Ignition-API-Token header
        // and the token's security levels.
        //
        // The read-only endpoint uses TOKEN_ACCESS, not TOKEN_READ. Those names describe gateway
        // *configuration* rights, not data access: TOKEN_READ resolves to the gateway-wide
        // readPermissions property, which ships requiring Administrator — far too high a bar for
        // reading tag values. TOKEN_ACCESS resolves to accessPermissions, which is an empty
        // AllOf by default and so admits any valid token. Net effect:
        //
        //   any valid API token                    -> the read-only tools
        //   token with gateway write permission    -> all of them, including tag writes,
        //                                             run_script, reset_trial and the file scan
        //
        // Deliberately no counts here: they went stale twice. The generated tool reference on the
        // docs site is the number that can't rot.
        mountMcpRoute(routes, "/mcp", ApiTokenManager.TOKEN_WRITE) { fullServer }
        mountMcpRoute(routes, "/mcp-readonly", ApiTokenManager.TOKEN_ACCESS) { readOnlyServer }

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
