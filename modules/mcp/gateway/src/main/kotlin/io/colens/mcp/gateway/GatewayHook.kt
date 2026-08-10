package io.colens.mcp.gateway

import com.inductiveautomation.ignition.common.licensing.LicenseState
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
import io.colens.mcp.gateway.tools.PerfTools
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
 *  - `POST /data/mcp/mcp`          all tools, requires -Dmcp.gateway.writeSecret
 *  - `POST /data/mcp/mcp-readonly` read-only tools, requires either secret
 *
 * Write gating is structural: a read-scoped caller can't even *see* the mutating tools, because
 * they aren't in the registry behind that route.
 *
 * The credential is a shared bearer secret rather than an API token because **8.1 has no API
 * tokens at all** — see [BearerAccessControl] for what that costs and the recommended posture.
 */
@Suppress("unused")
class GatewayHook : AbstractGatewayModuleHook() {

    private val logger: Logger = LoggerFactory.getLogger("mcp.Gateway")

    private lateinit var context: GatewayContext

    @Volatile private var fullServer: McpServer? = null

    @Volatile private var readOnlyServer: McpServer? = null

    @Volatile private var trialWatchdog: TrialWatchdog? = null

    // Read at construction, not in mountRouteHandlers(): system properties are set at JVM launch,
    // and snapshotting means a later System.setProperty() — reachable from run_script on the write
    // endpoint — cannot widen who may authenticate.
    private val readSecret: String? = BearerAccessControl.secret(BearerAccessControl.READ_SECRET_PROPERTY)
    private val writeSecret: String? = BearerAccessControl.secret(BearerAccessControl.WRITE_SECRET_PROPERTY)

    private val writeAccess = BearerAccessControl(
        "write", listOfNotNull(writeSecret).map(BearerAccessControl::utf8),
    )

    // The write secret also opens the read endpoint. Not an escalation: the read-only registry is
    // a strict subset of the full one, so a write-secret holder can already call every read tool
    // through /mcp. Refusing it would only break a client configured with both endpoints and one
    // secret.
    private val readAccess = BearerAccessControl(
        "read", listOfNotNull(readSecret, writeSecret).map(BearerAccessControl::utf8),
    )

    override fun setup(context: GatewayContext) {
        this.context = context
    }

    override fun startup(activationState: LicenseState) {
        val registry = ToolRegistry()
            .addAll(TagTools(context).tools())
            .addAll(ProjectTools(context).tools())
            .addAll(DataTools(context).tools())
            .addAll(SystemTools(context).tools())
            .addAll(PerfTools(context).tools())
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

        warnAboutSecrets()

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
        // 8.1 has no ApiTokenManager, so the read/write split is carried by two shared secrets
        // rather than by token permissions:
        //
        //   -Dmcp.gateway.readSecret   -> the read-only tools on /mcp-readonly
        //   -Dmcp.gateway.writeSecret  -> all of them on /mcp, INCLUDING run_script and
        //                                 reset_trial, and also accepted on /mcp-readonly
        //
        // Deliberately not stating the counts here. They were "14" and "17" for long enough to be
        // wrong by more than half, because nothing fails when a comment goes stale. The true
        // numbers are logged at startup by the line in `startup` above.
        //
        // Routes are mounted even when the secrets are unset, and answer 401. Refusing to mount
        // would produce a 404, which an operator cannot tell apart from "the module isn't
        // installed" — the wrong symptom for a configuration mistake, and one with no log line at
        // the moment of the request.
        mountMcpRoute(routes, "/mcp", writeAccess) { fullServer }
        mountMcpRoute(routes, "/mcp-readonly", readAccess) { readOnlyServer }

        // Open. 8.1 has no OPEN_ROUTE constant because an unrestricted route simply never calls
        // restrict(). Nothing here is sensitive, and authConfigured is precisely what an operator
        // needs to see when everything else is answering 401.
        routes.newRoute("/health")
            .method(HttpMethod.GET)
            .type(RouteGroup.TYPE_JSON)
            .handler { _, _ ->
                McpJson.toString(jsonObject {
                    put("status", if (fullServer != null) "ok" else "starting")
                    put("server", Constants.SERVER_NAME)
                    put("version", moduleVersion())
                    put("platform", "8.1")
                    put("authConfigured", readAccess.configured)
                    put("writeEndpointEnabled", writeAccess.configured)
                    put("mcpEndpoint", "/data/${Constants.SHORT_MODULE_ID}/mcp")
                    put("mcpReadOnlyEndpoint", "/data/${Constants.SHORT_MODULE_ID}/mcp-readonly")
                })
            }
            .mount()
    }

    private fun mountMcpRoute(
        routes: RouteGroup,
        path: String,
        access: BearerAccessControl,
        server: () -> McpServer?,
    ) {
        routes.newRoute(path)
            .method(HttpMethod.POST)
            .type(RouteGroup.TYPE_JSON)
            .restrict(access)
            .handler(McpRouteHandler(server))
            .mount()

        // A spec-conformant client may GET the endpoint looking for a standalone SSE stream.
        // Answer 405 (what a modern-revision server is told to do) rather than 404, which the
        // client would read as "this isn't an MCP endpoint at all".
        //
        // Left unrestricted deliberately: the handler only ever replies 405 + Allow, so there is
        // nothing here to protect.
        routes.newRoute(path)
            .method(HttpMethod.GET)
            .type(RouteGroup.TYPE_JSON)
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

    /**
     * The 8.1 auth story is a shared secret, so the only place an operator learns they got it
     * wrong is the log. Say so loudly, name the exact property, and never print the value.
     */
    private fun warnAboutSecrets() {
        if (!readAccess.configured) {
            logger.error(
                "Neither -D{} nor -D{} is set: BOTH MCP endpoints will reject every request with " +
                    "401. Add them as wrapper.java.additional lines in ignition.conf and restart.",
                BearerAccessControl.READ_SECRET_PROPERTY,
                BearerAccessControl.WRITE_SECRET_PROPERTY,
            )
            return
        }

        if (!writeAccess.configured) {
            logger.info(
                "-D{} is unset, so /mcp is closed and only the read-only endpoint is reachable. " +
                    "That is the recommended posture on 8.1.",
                BearerAccessControl.WRITE_SECRET_PROPERTY,
            )
        } else {
            logger.warn(
                "-D{} is set: /mcp exposes run_script, which is arbitrary Jython in gateway " +
                    "scope. Anyone holding that secret has the gateway.",
                BearerAccessControl.WRITE_SECRET_PROPERTY,
            )
        }

        listOf(
            BearerAccessControl.READ_SECRET_PROPERTY to readSecret,
            BearerAccessControl.WRITE_SECRET_PROPERTY to writeSecret,
        ).forEach { (name, value) ->
            if (value != null && value.length < BearerAccessControl.MIN_SECRET_LENGTH) {
                logger.warn(
                    "-D{} is only {} characters. These endpoints are reachable from the network; " +
                        "use at least {} random characters.",
                    name, value.length, BearerAccessControl.MIN_SECRET_LENGTH,
                )
            }
        }
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
