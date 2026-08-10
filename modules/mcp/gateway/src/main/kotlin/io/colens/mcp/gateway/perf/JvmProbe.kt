package io.colens.mcp.gateway.perf

import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonObject
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonArrayOfStrings
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.put
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage
import java.lang.management.ThreadInfo
import java.lang.management.ThreadMXBean
import kotlin.math.roundToLong

/**
 * Everything this module knows about the JVM it is running inside.
 *
 * Deliberately free of Ignition imports. The platform's own [PerformanceMonitor][
 * com.inductiveautomation.ignition.gateway.util.PerformanceMonitor] exposes thread *counts* and
 * nothing else — no per-thread listing, no stacks, no deadlock detection — so the useful half of
 * this file would have to be written against `java.lang.management` regardless. Writing all of it
 * that way means it cannot break on an SDK change.
 *
 * Two rules hold throughout:
 *
 * - **Nothing here mutates JVM state.** In particular [ThreadMXBean.setThreadContentionMonitoringEnabled]
 *   is never called: it is off by default, it costs on every lock operation, and switching it on
 *   would make the `readOnly = true` on these tools a lie. Blocked and waited *counts* need no such
 *   switch and are enough to see contention; blocked and waited *times* are simply not reported.
 * - **Output is bounded by construction.** `McpServer` serializes every payload twice — once as
 *   `structuredContent`, once pretty-printed into `content[0].text` — and imposes no size cap, so a
 *   naive dump of 400 threads with full stacks would be megabytes. Stacks are fetched only for the
 *   threads actually being returned, already truncated by the JVM.
 */
class JvmProbe {

    private val threads: ThreadMXBean get() = ManagementFactory.getThreadMXBean()

    // -----------------------------------------------------------------------
    // Threads
    // -----------------------------------------------------------------------

    /**
     * A census of every thread, plus stacks for the [topN] burning the most CPU.
     *
     * The census uses `getThreadInfo(ids, 0)`, which skips stack collection entirely — that is what
     * makes it cheap enough to run on a busy gateway. Stacks are then requested only for the
     * handful of threads that make the cut.
     */
    fun threadDump(
        nameContains: String?,
        topN: Int,
        includeStacks: Boolean,
        maxFrames: Int,
    ): JsonObject {
        val bean = threads
        val ids = bean.allThreadIds
        val census = bean.getThreadInfo(ids, 0).filterNotNull()

        val filter = nameContains?.lowercase()
        val matched = census.filter { filter == null || it.threadName.lowercase().contains(filter) }

        val cpu = cpuTimes(bean, matched.map { it.threadId })
        val ranked = matched.sortedByDescending { cpu[it.threadId] ?: -1L }.take(topN.coerceAtLeast(0))
        val stacks = if (includeStacks) stacksFor(bean, ranked.map { it.threadId }, maxFrames) else emptyMap()

        return jsonObject {
            put("total", census.size)
            put("daemon", bean.daemonThreadCount)
            put("peak", bean.peakThreadCount)
            put("totalStarted", bean.totalStartedThreadCount)
            put("cpuTimeAvailable", cpu.isNotEmpty())
            if (filter != null) {
                put("nameContains", nameContains)
                put("matched", matched.size)
            }
            put("states", stateHistogram(census))
            put("deadlocked", deadlocked(bean, maxFrames))
            put("groups", groupSummary(census, cpu))
            put("threads", jsonArrayOf(ranked.map { info ->
                threadJson(info, cpu[info.threadId], stacks[info.threadId])
            }))
        }
    }

    /**
     * Which threads actually consumed CPU over a window, rather than since the gateway started.
     *
     * Cumulative CPU time is dominated by whatever has been running longest, which on a gateway
     * that has been up for a week tells you nothing. Sampling twice and ranking by the delta is
     * what answers "what is eating this gateway *now*".
     */
    fun hotspots(sampleSeconds: Int, topN: Int, maxFrames: Int): JsonObject {
        val bean = threads
        if (!bean.isThreadCpuTimeSupported || !bean.isThreadCpuTimeEnabled) {
            return jsonObject {
                put("supported", false)
                put(
                    "note",
                    "This JVM does not report per-thread CPU time, so hotspots cannot be sampled. " +
                        "Call thread_dump for thread states and stacks instead.",
                )
            }
        }

        val before = bean.allThreadIds.associateWith { bean.getThreadCpuTime(it) }
        val startedAt = System.nanoTime()
        Thread.sleep(sampleSeconds * 1000L)
        val elapsedNanos = System.nanoTime() - startedAt
        val after = bean.allThreadIds.associateWith { bean.getThreadCpuTime(it) }

        // Threads that started mid-window have no `before` sample; charging them their whole
        // cumulative time would overstate them, so they are measured from zero.
        val deltas = after.mapNotNull { (id, end) ->
            if (end < 0) return@mapNotNull null
            val start = before[id]?.takeIf { it >= 0 } ?: 0L
            val delta = end - start
            if (delta > 0) id to delta else null
        }.sortedByDescending { it.second }.take(topN.coerceAtLeast(0))

        val cores = Runtime.getRuntime().availableProcessors()
        val infos = bean.getThreadInfo(deltas.map { it.first }.toLongArray(), maxFrames.coerceAtLeast(0))
            .filterNotNull()
            .associateBy { it.threadId }

        return jsonObject {
            put("supported", true)
            put("sampleSeconds", sampleSeconds)
            put("elapsedMillis", elapsedNanos / 1_000_000)
            put("availableProcessors", cores)
            put("totalCpuPercent", percent(deltas.sumOf { it.second }.toDouble(), elapsedNanos.toDouble() * cores))
            put("threads", jsonArrayOf(deltas.map { (id, delta) ->
                val info = infos[id]
                jsonObject {
                    put("id", id)
                    put("name", info?.threadName)
                    put("state", info?.threadState?.name)
                    put("cpuMillis", delta / 1_000_000)
                    // Of one core: a thread pegging a single core reads as 100, not as 1/cores.
                    put("cpuPercent", percent(delta.toDouble(), elapsedNanos.toDouble()))
                    put("stack", info?.let { stackJson(it, maxFrames) })
                }
            }))
        }
    }

    private fun cpuTimes(bean: ThreadMXBean, ids: List<Long>): Map<Long, Long> {
        if (!bean.isThreadCpuTimeSupported || !bean.isThreadCpuTimeEnabled) return emptyMap()
        return ids.mapNotNull { id ->
            val nanos = runCatching { bean.getThreadCpuTime(id) }.getOrDefault(-1L)
            if (nanos >= 0) id to nanos else null
        }.toMap()
    }

    private fun stacksFor(bean: ThreadMXBean, ids: List<Long>, maxFrames: Int): Map<Long, JsonArray> {
        if (ids.isEmpty()) return emptyMap()
        return bean.getThreadInfo(ids.toLongArray(), maxFrames.coerceAtLeast(0))
            .filterNotNull()
            .associate { it.threadId to stackJson(it, maxFrames) }
    }

    private fun deadlocked(bean: ThreadMXBean, maxFrames: Int): JsonArray {
        // Monitors *and* ownable synchronizers: a deadlock over ReentrantLocks is invisible to
        // findMonitorDeadlockedThreads, and Ignition's internals use both.
        val ids = runCatching { bean.findDeadlockedThreads() }.getOrNull()
            ?: return JsonArray()
        val infos = bean.getThreadInfo(ids, maxFrames.coerceAtLeast(1)).filterNotNull()
        return jsonArrayOf(infos.map { info ->
            jsonObject {
                put("id", info.threadId)
                put("name", info.threadName)
                put("state", info.threadState?.name)
                put("lockName", info.lockName)
                put("lockOwnerId", info.lockOwnerId.takeIf { it >= 0 })
                put("lockOwnerName", info.lockOwnerName)
                put("stack", stackJson(info, maxFrames))
            }
        })
    }

    private fun threadJson(info: ThreadInfo, cpuNanos: Long?, stack: JsonArray?): JsonObject = jsonObject {
        put("id", info.threadId)
        put("name", info.threadName)
        put("state", info.threadState?.name)
        put("daemon", runCatching { info.isDaemon }.getOrNull())
        put("cpuMillis", cpuNanos?.let { it / 1_000_000 })
        put("blockedCount", info.blockedCount)
        put("waitedCount", info.waitedCount)
        put("lockName", info.lockName)
        put("lockOwnerName", info.lockOwnerName)
        put("stack", stack)
    }

    private fun stackJson(info: ThreadInfo, maxFrames: Int): JsonArray =
        jsonArrayOfStrings(info.stackTrace.take(maxFrames.coerceAtLeast(0)).map { it.toString() })

    private fun stateHistogram(infos: List<ThreadInfo>): JsonObject {
        val counts = infos.groupingBy { it.threadState?.name ?: "UNKNOWN" }.eachCount()
        return jsonObject { counts.toSortedMap().forEach { (state, n) -> put(state, n) } }
    }

    /**
     * Threads bucketed by name prefix.
     *
     * Ignition names its threads by subsystem and appends an index — `perspective-worker-3`,
     * `gateway-scheduled-1` — so dropping the numeric segments turns a flat list of hundreds of
     * threads into a per-subsystem breakdown, which is almost always the thing you actually wanted
     * to look at.
     */
    private fun groupSummary(infos: List<ThreadInfo>, cpu: Map<Long, Long>): JsonArray {
        val groups = infos.groupBy { groupName(it.threadName) }
        return jsonArrayOf(
            groups.entries
                .sortedWith(compareByDescending<Map.Entry<String, List<ThreadInfo>>> { (_, members) ->
                    members.sumOf { cpu[it.threadId] ?: 0L }
                }.thenByDescending { it.value.size })
                .map { (name, members) ->
                    jsonObject {
                        put("group", name)
                        put("count", members.size)
                        put("cpuMillis", members.sumOf { cpu[it.threadId] ?: 0L } / 1_000_000)
                        put("blockedCount", members.sumOf { it.blockedCount })
                        put("states", stateHistogram(members))
                    }
                },
        )
    }

    private fun groupName(threadName: String?): String {
        val raw = threadName ?: return "unnamed"
        val stripped = raw.split(SEPARATORS)
            .filter { it.isNotEmpty() && !it.matches(NUMERIC) && !it.matches(UUID_LIKE) }
            .joinToString("-")
        return stripped.ifEmpty { raw }
    }

    // -----------------------------------------------------------------------
    // Memory, GC and the JVM itself
    // -----------------------------------------------------------------------

    /**
     * Heap, memory pools, GC, class loading and JVM configuration.
     *
     * With [sampleSeconds] above zero this also reports GC time as a percentage of wall clock,
     * which is the single most diagnostic number available: a gateway spending 30% of its time in
     * GC is not slow because of anything you will find in the logs.
     */
    fun health(sampleSeconds: Int): JsonObject {
        val memory = ManagementFactory.getMemoryMXBean()
        val runtime = ManagementFactory.getRuntimeMXBean()
        val classes = ManagementFactory.getClassLoadingMXBean()
        val os = ManagementFactory.getOperatingSystemMXBean()

        val sample = if (sampleSeconds > 0) sample(sampleSeconds) else null

        return jsonObject {
            put("uptimeMillis", runtime.uptime)
            put("startTime", runtime.startTime)
            put("vmName", runtime.vmName)
            put("vmVersion", runtime.vmVersion)
            put("heap", usageJson(memory.heapMemoryUsage))
            put("nonHeap", usageJson(memory.nonHeapMemoryUsage))
            put("memoryPools", jsonArrayOf(ManagementFactory.getMemoryPoolMXBeans().map { pool ->
                jsonObject {
                    put("name", pool.name)
                    put("type", pool.type?.name)
                    put("usage", usageJson(runCatching { pool.usage }.getOrNull()))
                    put("peak", usageJson(runCatching { pool.peakUsage }.getOrNull()))
                    // Usage right after the last collection — what survived, i.e. the real
                    // occupancy of the pool as opposed to garbage not yet reclaimed.
                    put("afterLastCollection", usageJson(runCatching { pool.collectionUsage }.getOrNull()))
                }
            }))
            put("garbageCollectors", jsonArrayOf(ManagementFactory.getGarbageCollectorMXBeans().map { gc ->
                jsonObject {
                    put("name", gc.name)
                    put("collectionCount", gc.collectionCount.takeIf { it >= 0 })
                    put("collectionTimeMillis", gc.collectionTime.takeIf { it >= 0 })
                }
            }))
            put("classLoading", jsonObject {
                put("loaded", classes.loadedClassCount)
                put("totalLoaded", classes.totalLoadedClassCount)
                put("unloaded", classes.unloadedClassCount)
            })
            put("bufferPools", jsonArrayOf(
                ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java).map { pool ->
                    jsonObject {
                        put("name", pool.name)
                        put("count", pool.count)
                        put("memoryUsedBytes", pool.memoryUsed.takeIf { it >= 0 })
                        put("totalCapacityBytes", pool.totalCapacity.takeIf { it >= 0 })
                    }
                },
            ))
            put("operatingSystem", jsonObject {
                put("name", os.name)
                put("arch", os.arch)
                put("availableProcessors", os.availableProcessors)
                put("systemLoadAverage", os.systemLoadAverage.takeIf { it >= 0 })
                // HotSpot-only extras. Ignition ships a HotSpot-family JVM, but the cast is the
                // kind of thing that should degrade to null rather than fail the tool.
                runCatching {
                    val sun = os as com.sun.management.OperatingSystemMXBean
                    put("processCpuLoadPercent", percent(sun.processCpuLoad, 1.0))
                    put("systemCpuLoadPercent", percent(sun.cpuLoad, 1.0))
                    put("totalMemoryBytes", sun.totalMemorySize)
                    put("freeMemoryBytes", sun.freeMemorySize)
                }
            })
            put("jvmArguments", jsonArrayOfStrings(runCatching { runtime.inputArguments }.getOrDefault(emptyList())))
            put("sample", sample)
        }
    }

    /**
     * GC and process CPU measured across a window. Cumulative GC time since startup is nearly
     * useless on its own; the same number as a fraction of elapsed time is a diagnosis.
     */
    private fun sample(seconds: Int): JsonObject {
        val gcs = ManagementFactory.getGarbageCollectorMXBeans()
        fun gcTime() = gcs.sumOf { it.collectionTime.coerceAtLeast(0) }
        fun gcCount() = gcs.sumOf { it.collectionCount.coerceAtLeast(0) }
        fun processCpu() = runCatching {
            (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean)
                .processCpuTime
        }.getOrDefault(-1L)

        val gcTimeBefore = gcTime()
        val gcCountBefore = gcCount()
        val cpuBefore = processCpu()
        val startedAt = System.nanoTime()

        Thread.sleep(seconds * 1000L)

        val elapsedNanos = System.nanoTime() - startedAt
        val elapsedMillis = elapsedNanos / 1_000_000
        val gcMillis = gcTime() - gcTimeBefore
        val cpuAfter = processCpu()
        val cores = Runtime.getRuntime().availableProcessors()

        return jsonObject {
            put("sampleSeconds", seconds)
            put("elapsedMillis", elapsedMillis)
            put("gcCollections", gcCount() - gcCountBefore)
            put("gcMillis", gcMillis)
            put("gcTimePercent", percent(gcMillis.toDouble(), elapsedMillis.toDouble()))
            put(
                "processCpuPercent",
                if (cpuBefore >= 0 && cpuAfter >= 0) {
                    percent((cpuAfter - cpuBefore).toDouble(), elapsedNanos.toDouble() * cores)
                } else {
                    null
                },
            )
        }
    }

    private fun usageJson(usage: MemoryUsage?): JsonObject? {
        if (usage == null) return null
        return jsonObject {
            put("initBytes", usage.init.takeIf { it >= 0 })
            put("usedBytes", usage.used)
            put("committedBytes", usage.committed)
            put("maxBytes", usage.max.takeIf { it >= 0 })
            put("usedPercentOfMax", if (usage.max > 0) percent(usage.used.toDouble(), usage.max.toDouble()) else null)
        }
    }

    /** A percentage rounded to two places, or null when the denominator makes it meaningless. */
    private fun percent(part: Double, whole: Double): Double? {
        if (whole <= 0.0 || part < 0.0 || !part.isFinite() || !whole.isFinite()) return null
        return (part / whole * 10_000.0).roundToLong() / 100.0
    }

    private companion object {
        val SEPARATORS = Regex("[-_#\\s]+")
        val NUMERIC = Regex("\\d+")

        /**
         * A hex chunk of a UUID. The digit requirement keeps ordinary words that happen to be
         * spelled in hex letters (`facade`, `decade`) from being mistaken for identifiers.
         */
        val UUID_LIKE = Regex("(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{8,}")
    }
}
