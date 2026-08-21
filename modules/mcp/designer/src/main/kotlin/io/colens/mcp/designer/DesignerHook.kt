package io.colens.mcp.designer

import com.inductiveautomation.ignition.common.licensing.LicenseState
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook
import com.inductiveautomation.ignition.designer.model.DesignerContext
import com.inductiveautomation.ignition.designer.model.menu.JMenuMerge
import com.inductiveautomation.ignition.designer.model.menu.MenuBarMerge
import com.inductiveautomation.ignition.designer.model.menu.WellKnownMenuConstants
import io.colens.mcp.common.Constants
import io.colens.mcp.common.DesignerAuth
import io.colens.mcp.common.DevMode
import io.colens.mcp.common.McpServer
import io.colens.mcp.common.ToolRegistry
import io.colens.mcp.designer.tools.DesignerTools
import io.colens.mcp.designer.tools.PerspectiveEditTools
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Runs an MCP endpoint inside the Designer, on loopback only, and publishes it to
 * `~/.ignition/mcp/designer-<pid>.json` so a client can find the port and secret.
 *
 * This is separate from the gateway endpoint on purpose. Relaying Designer calls through the
 * gateway would need asynchronous gateway-to-client push and roughly double the code, for no
 * benefit the user can see — two endpoints are simply two lines of client config.
 */
@Suppress("unused")
class DesignerHook : AbstractDesignerModuleHook() {

    private val logger: Logger = LoggerFactory.getLogger("mcp.Designer")

    private lateinit var context: DesignerContext

    private var discovery: DiscoveryFile? = null

    /**
     * Read once, before startup. The endpoint, the discovery file and the connect dialog must
     * agree on one answer, and `getModuleMenu()` is not ordered against `startup()`.
     */
    private val auth: DesignerAuth = DesignerAuth.fromSystemProperties()
    private var httpServer: McpHttpServer? = null
    private var connectDialog: ConnectDialog? = null

    override fun startup(context: DesignerContext, activationState: LicenseState) {
        this.context = context

        // Cheap insurance: every JRE Ignition 8.3 ships includes jdk.httpserver, but if someone
        // launches the Designer on a stripped custom runtime, fail with a legible message rather
        // than a NoClassDefFoundError halfway through startup.
        try {
            Class.forName("com.sun.net.httpserver.HttpServer")
        } catch (e: ClassNotFoundException) {
            logger.error(
                "This JRE has no jdk.httpserver module, so the Designer MCP endpoint cannot start. " +
                    "The gateway endpoint at /data/{}/mcp is unaffected.",
                Constants.SHORT_MODULE_ID,
            )
            return
        }

        val designerTools = DesignerTools(context)
        val registry = ToolRegistry()
            .addAll(designerTools.tools())
            .addAll(perspectiveTools())

        // Gated here rather than inside tools(), so the generated tool reference documents
        // save_project either way. A feature nobody can read about is worse than one they have to
        // switch on.
        if (DesignerTools.saveAllowed()) {
            registry.add(designerTools.saveTool())
            logger.warn(
                "-D{}=true is set: save_project is available, so a connected client can commit " +
                    "this Designer's staged changes to the gateway with nobody reviewing them. " +
                    "That is the one guarantee the Designer scope otherwise makes. Intended for " +
                    "unattended use — a VM, CI, or a scheduled run — not for a Designer somebody " +
                    "is sitting at. If you ARE working in this Designer, note that save_project " +
                    "pushes the project tree and does NOT flush editors you have open and " +
                    "unsaved: their contents would be left behind by a save you did not perform.",
                if (DevMode.enabled()) DevMode.PROPERTY else DesignerTools.SAVE_PROPERTY,
            )
        }

        val discoveryFile = DiscoveryFile()
        if (!discoveryFile.initialize()) {
            logger.error("Could not initialise the MCP discovery directory; Designer endpoint disabled.")
            return
        }
        discovery = discoveryFile

        val mcp = McpServer(
            tools = registry,
            serverVersion = moduleVersion(),
            serverName = "${Constants.SERVER_NAME}-designer",
            // Instructions are served on every connect, so they have to match what is actually
            // registered — a model told "nothing is ever committed" while holding save_project
            // gets contradictory guidance.
            instructions = if (DesignerTools.saveAllowed()) INSTRUCTIONS_WITH_SAVE else INSTRUCTIONS,
            allowAnyOrigin = DevMode.enabled(),
        )

        val server = McpHttpServer(mcp, auth)
        val port = try {
            server.start()
        } catch (e: Exception) {
            logger.error("Could not start the Designer MCP endpoint", e)
            discoveryFile.shutdown()
            discovery = null
            return
        }

        httpServer = server
        discoveryFile.write(port, server.boundHost, server.loopbackOnly, auth, context.projectName, gatewayAddress())

        // Deliberately here and not beside the other save warning: `loopbackOnly` is not known
        // until the bind succeeds, so this check cannot move up with it.
        if (DesignerTools.saveAllowed() && !server.loopbackOnly && !auth.required) {
            logger.error(
                "save_project is enabled on a Designer MCP endpoint bound to {}:{} that requires " +
                    "no credential. Anything that can route to this machine can commit changes to " +
                    "the gateway with nobody reviewing them. Set -D{}, or drop -D{}.",
                server.boundHost,
                port,
                DesignerAuth.SECRET_PROPERTY,
                McpHttpServer.BIND_ADDRESS_PROPERTY,
            )
        }

        logger.info(
            "Ignition MCP Designer endpoint ready: {} tools on http://{}:{}/mcp",
            registry.size,
            server.boundHost,
            port,
        )
    }

    override fun shutdown() {
        httpServer?.stop()
        httpServer = null
        discovery?.shutdown()
        discovery = null
        logger.info("Ignition MCP Designer endpoint shut down.")
    }

    override fun getModuleMenu(): MenuBarMerge {
        val dialog = ConnectDialog(context) {
            val server = httpServer
            val disc = discovery
            if (server == null || disc == null || server.port <= 0) {
                null
            } else {
                ConnectDialog.Endpoint(server.boundHost, server.port, auth.secret, disc.path.toString())
            }
        }
        connectDialog = dialog

        return MenuBarMerge(Constants.SHORT_MODULE_ID).apply {
            add(
                WellKnownMenuConstants.TOOLS_MENU_LOCATION,
                JMenuMerge(WellKnownMenuConstants.TOOLS_MENU_NAME).apply { add(dialog.menuItem) },
            )
        }
    }

    /**
     * Perspective is an optional module dependency, so constructing these tools can throw
     * NoClassDefFoundError on a Designer connected to a gateway without Perspective. Catch it and
     * leave them out of tools/list rather than failing the whole hook.
     */
    private fun perspectiveTools(): List<io.colens.mcp.common.Tool> = try {
        PerspectiveEditTools(context).tools()
    } catch (t: Throwable) {
        logger.info("Perspective tools unavailable in this Designer: {}", t.toString())
        emptyList()
    }

    private fun gatewayAddress(): String? = runCatching {
        com.inductiveautomation.ignition.client.gateway_interface.GatewayConnectionManager
            .getInstance()
            .gatewayInterface
            .gatewayAddress
            ?.toString()
    }.getOrNull()

    private fun moduleVersion(): String =
        javaClass.`package`?.implementationVersion ?: "dev"

    private companion object {
        const val INSTRUCTIONS = """
This server is attached to a running Ignition Designer with a project open.

Reads reflect the Designer's current state, including edits not yet saved to the gateway.
Writes are staged as unsaved Designer changes — they are never committed for you. After
write_resource or delete_resource, tell the user to review the change in the Designer and save it.

Use list_resources with no filter first to discover the moduleId/type pairs in this project.
"""

        const val INSTRUCTIONS_WITH_SAVE = """
This server is attached to a running Ignition Designer with a project open.

Reads reflect the Designer's current state, including edits not yet saved to the gateway.
Writes are staged as unsaved Designer changes and are not committed until something saves them.

This Designer was started with saving enabled, so save_project is available and commits staged
changes to the gateway. Prefer letting a human save when there is one: use save_project when you
are operating unattended, or when the user has asked you to save. Review with
list_pending_changes first, and expect save_project to refuse if a staged edit conflicts with a
change waiting on the gateway.

Use list_resources with no filter first to discover the moduleId/type pairs in this project.
"""
    }
}
