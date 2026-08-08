package io.colens.mcp.gateway.status

import io.colens.mcp.common.McpOutcome
import io.colens.mcp.common.McpRequestListener
import java.util.concurrent.atomic.AtomicLong

/**
 * Running totals for every MCP request this gateway has answered, feeding the status card and
 * `/data/mcp/health`.
 *
 * One instance is shared by both the full and read-only servers: "requests" on the card means
 * requests this module answered, not requests on one of two routes, and a reader comparing the
 * number against their own client's activity would be confused by a per-endpoint split.
 *
 * Totals are process-lifetime and reset when the module restarts. That is deliberate — persisting
 * them would mean owning storage and a reset story for a number whose only job is to show that
 * traffic is arriving.
 */
class McpCounters : McpRequestListener {

    private val requests = AtomicLong()
    private val toolErrors = AtomicLong()
    private val protocolErrors = AtomicLong()

    override fun onRequest(method: String, outcome: McpOutcome) {
        requests.incrementAndGet()
        when (outcome) {
            McpOutcome.OK -> Unit
            McpOutcome.TOOL_ERROR -> toolErrors.incrementAndGet()
            McpOutcome.PROTOCOL_ERROR -> protocolErrors.incrementAndGet()
        }
    }

    val requestCount: Long get() = requests.get()

    /** A tool ran and failed. Answered HTTP 200 with `isError`, so invisible to the transport. */
    val toolErrorCount: Long get() = toolErrors.get()

    /** The request never reached a tool: unparseable, unknown method, bad params. */
    val protocolErrorCount: Long get() = protocolErrors.get()

    val errorCount: Long get() = toolErrorCount + protocolErrorCount
}
