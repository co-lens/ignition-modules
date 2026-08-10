package io.colens.mcp.gateway.tools

import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.mcp.common.Tool
import io.colens.mcp.common.optBoolean
import io.colens.mcp.common.optInt
import io.colens.mcp.common.optString
import io.colens.mcp.common.schema
import io.colens.mcp.gateway.perf.JvmProbe

/**
 * Performance tools for the gateway's JVM.
 *
 * These are read-only in the strict sense — they observe, they never change anything, including
 * JVM-level switches — so they serve from the read-only endpoint too. The two sampling tools do
 * block the request thread for their window; the caps in their schemas are the whole defence,
 * since `McpServer` runs handlers synchronously and has no timeout of its own.
 *
 * [context] is unused today and held only so this class matches the shape of every other tool
 * class in the scope. Note the constructor must stay context-free: the doc generator builds it
 * against a stub and fails the build if construction touches the context.
 */
class PerfTools(@Suppress("unused") private val context: GatewayContext) {

    private val probe = JvmProbe()

    fun tools(): List<Tool> = listOf(
        threadDump(),
        threadHotspots(),
        jvmHealth(),
    )

    private fun threadDump() = Tool(
        name = "thread_dump",
        title = "Dump gateway threads",
        description = "Every thread in the gateway JVM: a state histogram, a per-subsystem " +
            "breakdown grouped by thread name (Ignition names threads like 'perspective-worker-3', " +
            "so the groups read as subsystems), any deadlock, and stack traces for the threads " +
            "using the most CPU. Cumulative CPU here is measured since gateway startup — use " +
            "thread_hotspots to see what is busy right now.",
        inputSchema = schema {
            string("nameContains", "Only include threads whose name contains this substring.")
            integer("topN", "How many threads to report in full, ranked by cumulative CPU time.", default = 25)
            boolean("includeStacks", "Include stack traces for the reported threads.", default = true)
            integer("maxFrames", "Truncate each stack trace to this many frames.", default = 12)
        },
        handler = { args ->
            probe.threadDump(
                nameContains = args.optString("nameContains"),
                topN = args.optInt("topN", 25).coerceIn(0, 200),
                includeStacks = args.optBoolean("includeStacks", true),
                maxFrames = args.optInt("maxFrames", 12).coerceIn(0, 100),
            )
        },
    )

    private fun threadHotspots() = Tool(
        name = "thread_hotspots",
        title = "Sample thread CPU usage",
        description = "Samples per-thread CPU time twice over a short window and ranks threads by " +
            "how much they burned in between, with the stack each was running at the end. This is " +
            "the tool for 'what is pegging this gateway right now'. Note it blocks for the sample " +
            "window before returning.",
        inputSchema = schema {
            integer("sampleSeconds", "Length of the sampling window, 1-30 seconds.", default = 5)
            integer("topN", "How many threads to report, ranked by CPU used during the window.", default = 15)
            integer("maxFrames", "Truncate each stack trace to this many frames.", default = 12)
        },
        handler = { args ->
            probe.hotspots(
                sampleSeconds = args.optInt("sampleSeconds", 5).coerceIn(1, MAX_SAMPLE_SECONDS),
                topN = args.optInt("topN", 15).coerceIn(0, 200),
                maxFrames = args.optInt("maxFrames", 12).coerceIn(0, 100),
            )
        },
    )

    private fun jvmHealth() = Tool(
        name = "jvm_health",
        title = "JVM memory, GC and class loading",
        description = "Heap and non-heap usage, per-pool occupancy (which tells you which " +
            "generation is under pressure), garbage collector counts and times, loaded class " +
            "counts, direct buffer usage, uptime and the JVM's own launch arguments. Pass " +
            "sampleSeconds to also measure GC time and process CPU as a percentage of wall clock " +
            "over a window — a gateway spending a large fraction of its time collecting garbage " +
            "is slow for a reason no log will show.",
        inputSchema = schema {
            integer(
                "sampleSeconds",
                "Measure GC and CPU across a window of this many seconds (0-30). " +
                    "0 reports instantaneous values only and returns immediately.",
                default = 0,
            )
        },
        handler = { args ->
            probe.health(args.optInt("sampleSeconds", 0).coerceIn(0, MAX_SAMPLE_SECONDS))
        },
    )

    private companion object {
        /**
         * Handlers run on the Jetty request thread and nothing upstream will time them out, so the
         * sampling window has to be bounded here or a client can pin a request thread indefinitely.
         */
        const val MAX_SAMPLE_SECONDS = 30
    }
}
