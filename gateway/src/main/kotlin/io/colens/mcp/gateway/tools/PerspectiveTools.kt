package io.colens.mcp.gateway.tools

import com.inductiveautomation.ignition.gateway.model.GatewayContext
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext
import io.colens.mcp.common.Tool
import io.colens.mcp.common.optString
import io.colens.mcp.common.perspective.PerspectiveReadTools
import io.colens.mcp.common.requireString
import io.colens.mcp.common.schema
import io.colens.mcp.common.perspective.PerspectiveComponentCatalog
import io.colens.mcp.gateway.perspective.GatewayViewSource
import io.colens.mcp.gateway.perspective.LiveSessionInspector

/**
 * Gateway-side Perspective tools: the shared read set, plus the two that only make sense where
 * sessions actually run.
 *
 * Constructing this class touches Perspective types, so the caller must build it inside a
 * try/catch — see `GatewayHook`. That is what keeps Perspective an optional dependency.
 */
class PerspectiveTools(private val context: GatewayContext) {

    private val perspective: () -> PerspectiveContext? = {
        try {
            PerspectiveContext.get(context)
        } catch (t: Throwable) {
            null
        }
    }

    private val catalog = PerspectiveComponentCatalog { perspective()?.componentRegistry }
    private val inspector = LiveSessionInspector(perspective)

    fun tools(): List<Tool> =
        PerspectiveReadTools(GatewayViewSource(context), catalog).tools() +
            listOf(listSessions(), diagnoseLiveView())

    private fun listSessions() = Tool(
        name = "perspective_list_sessions",
        title = "List Perspective sessions",
        description = "Lists the Perspective sessions currently running on this gateway, with their " +
            "ids, project, user and open page count. Use an id with perspective_diagnose_live_view.",
        inputSchema = schema {
            string("project", "Only return sessions for this project.")
        },
        handler = { args -> inspector.listSessions(args.optString("project")) },
    )

    private fun diagnoseLiveView() = Tool(
        name = "perspective_diagnose_live_view",
        title = "Diagnose a running Perspective view",
        description = "For a view open in a running session, reports every configured property " +
            "alongside its binding and its CURRENT value and quality. This is how you find out why " +
            "a binding isn't working: Perspective reports binding failures as bad quality rather " +
            "than as errors, so a bad quality next to its binding config is usually the whole " +
            "answer. Only views a user currently has open are visible.",
        inputSchema = schema {
            string("sessionId", "Session id from perspective_list_sessions.", required = true)
            string("view", "Only report views whose path contains this substring.")
        },
        handler = { args ->
            inspector.diagnoseView(args.requireString("sessionId"), args.optString("view"))
        },
    )
}
