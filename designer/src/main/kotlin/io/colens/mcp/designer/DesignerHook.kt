package io.colens.mcp.designer

import com.inductiveautomation.ignition.common.licensing.LicenseState
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook
import com.inductiveautomation.ignition.designer.model.DesignerContext
import com.inductiveautomation.ignition.designer.model.menu.JMenuMerge
import com.inductiveautomation.ignition.designer.model.menu.MenuBarMerge
import com.inductiveautomation.ignition.designer.model.menu.WellKnownMenuConstants
import io.colens.mcp.common.Constants
import io.colens.mcp.common.McpServer
import io.colens.mcp.common.ToolRegistry
import io.colens.mcp.designer.tools.DesignerTools
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

        val registry = ToolRegistry().addAll(DesignerTools(context).tools())

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
            instructions = INSTRUCTIONS,
        )

        val server = McpHttpServer(mcp, discoveryFile.secret)
        val port = try {
            server.start()
        } catch (e: Exception) {
            logger.error("Could not start the Designer MCP endpoint", e)
            discoveryFile.shutdown()
            discovery = null
            return
        }

        httpServer = server
        discoveryFile.write(port, context.projectName, gatewayAddress())

        logger.info(
            "Ignition MCP Designer endpoint ready: {} tools on http://127.0.0.1:{}/mcp",
            registry.size,
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
                ConnectDialog.Endpoint(server.port, disc.secret, disc.path.toString())
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
    }
}
