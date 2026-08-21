package io.colens.mcp.gateway

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext
import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler
import io.colens.mcp.common.McpHttpRequest
import io.colens.mcp.common.McpServer
import javax.servlet.http.HttpServletResponse

/**
 * Bridges an Ignition data route to [McpServer].
 *
 * There is no authentication here on purpose: the route is mounted with
 * `restrict(BearerAccessControl)`, so the bearer secret was validated and a bad one rejected with
 * 401 before we're reached. (On the 8.3 line the same slot is filled by the platform's own API
 * token strategies, which 8.1 does not have — see [BearerAccessControl].)
 *
 * The server is resolved per request rather than captured, because routes are mounted before
 * `startup()` has necessarily finished building the tool registry.
 */
class McpRouteHandler(private val server: () -> McpServer?) : RouteHandler {

    override fun handle(ctx: RequestContext, res: HttpServletResponse): Any? {
        val mcp = server() ?: run {
            res.status = 503
            res.contentType = "application/json"
            res.writer.write("""{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"Gateway starting up"}}""")
            res.writer.flush()
            return null
        }

        val result = mcp.handle(
            McpHttpRequest(
                method = ctx.method?.name ?: "POST",
                body = if (ctx.method?.name.equals("POST", ignoreCase = true)) ctx.readBody() else null,
                origin = ctx.request.getHeader("Origin"),
                contentType = ctx.request.getHeader("Content-Type"),
            )
        )

        res.status = result.status
        result.contentType?.let { res.contentType = it }
        result.headers.forEach { (name, value) -> res.setHeader(name, value) }
        if (result.body.isNotEmpty()) {
            res.writer.write(result.body)
            res.writer.flush()
        }
        return null
    }
}
