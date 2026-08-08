package io.colens.mcp.gateway.project

import com.inductiveautomation.ignition.gateway.model.GatewayContext
import com.inductiveautomation.ignition.gateway.resourcecollection.ResourceCollectionManager
import io.colens.mcp.common.McpArgumentException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What one scan changed, measured by diffing resource signatures either side of it. */
data class ScanResult(
    val target: String,
    val available: Boolean,
    val unavailableReason: String? = null,
    val collectionsAdded: List<String> = emptyList(),
    val collectionsDeleted: List<String> = emptyList(),
    val resourcesAdded: List<String> = emptyList(),
    val resourcesModified: List<String> = emptyList(),
    val resourcesDeleted: List<String> = emptyList(),
    val timedOut: Boolean = false,
    val waitedMs: Long = 0,
) {
    val changedCount: Int
        get() = collectionsAdded.size + collectionsDeleted.size +
            resourcesAdded.size + resourcesModified.size + resourcesDeleted.size
}

/**
 * Makes the gateway re-read its resource collections from disk, and reports what changed.
 *
 * Two collections are scannable through the *same* interface — `ProjectManager` and
 * `ConfigurationManager` each extend [ResourceCollectionManager] — so one code path serves both.
 *
 * **Why this diffs signatures instead of listening for events.** The obvious implementation is to
 * register a `ResourceCollectionListener` around the scan and report its callbacks. That was tried,
 * and measured against a real gateway it is wrong: editing a resource inside a project applies
 * correctly but fires **no** `collectionUpdated`, so the tool reported "nothing changed" about a
 * scan that had just changed something. Those callbacks track collections appearing and
 * disappearing, not resources moving within one.
 *
 * Snapshotting `ResourceSignature` per resource either side of the scan reports the truth, and at
 * resource rather than project granularity, which is what the caller actually wants to know. The
 * signature is metadata, so a snapshot costs a walk of the resource table rather than a read of
 * every file.
 *
 * **Version note.** `CompletableFuture<Void> requestScan()` is identical on both lines. Enumerating
 * collections is not: 8.3 has the generic `getNames()`/`find(name)` on [ResourceCollectionManager],
 * where 8.1 has `getProjectNames()`/`getProject(name)` on `ProjectManagerBase` and no generic form
 * — and no config collection at all, since 8.1 keeps gateway config in `config.idb` rather than on
 * disk. **That divergence is confined to [snapshot] and [configManager]**, so the tool calling this
 * is character-identical on both branches. Same rule `licensing/TrialResetter.kt` states for
 * licensing internals.
 *
 * Nothing here touches [GatewayContext] at construction: the doc generator builds the tool classes
 * against a stub context and fails the build on any context call made outside a handler.
 */
class ResourceScanner(private val context: GatewayContext) {

    /** Project resources — `data/projects/<name>/`. Present on every version. */
    private fun projectManager(): ResourceCollectionManager = context.projectManager

    /**
     * Gateway config — `data/config/`. 8.3 only, and `runCatching` rather than a version check
     * because the honest question is "does this gateway expose one", not "which version is this".
     */
    private fun configManager(): ResourceCollectionManager? =
        runCatching { context.configurationManager }.getOrNull()

    fun scan(target: String, timeoutSeconds: Int): List<ScanResult> = when (target) {
        TARGET_PROJECTS -> listOf(scanProjects(timeoutSeconds))
        TARGET_CONFIG -> listOf(scanConfig(timeoutSeconds))
        TARGET_BOTH -> listOf(scanProjects(timeoutSeconds), scanConfig(timeoutSeconds))
        else -> throw McpArgumentException(
            "Unknown target '$target'. Use '$TARGET_PROJECTS', '$TARGET_CONFIG' or '$TARGET_BOTH'."
        )
    }

    private fun scanProjects(timeoutSeconds: Int): ScanResult =
        scanCollection(TARGET_PROJECTS, projectManager(), timeoutSeconds)

    private fun scanConfig(timeoutSeconds: Int): ScanResult {
        val manager = configManager()
            ?: return ScanResult(
                target = TARGET_CONFIG,
                available = false,
                unavailableReason = "This gateway has no file-based config collection. Gateway " +
                    "config only lives on disk from Ignition 8.3; before that it is held in " +
                    "config.idb, where there is nothing to scan.",
            )
        return scanCollection(TARGET_CONFIG, manager, timeoutSeconds)
    }

    /**
     * Scans one collection and waits up to [timeoutSeconds].
     *
     * A timeout is not an error. The scan keeps running and would have completed later; the caller
     * gets the diff as it stands plus `timedOut`, which is more useful than an exception that says
     * nothing about the state the gateway is now in. Note the diff is then taken mid-scan, so it
     * may be partial — hence the caller being told to re-read rather than re-scan.
     */
    private fun scanCollection(
        target: String,
        manager: ResourceCollectionManager,
        timeoutSeconds: Int,
    ): ScanResult {
        val startedNanos = System.nanoTime()
        val before = snapshot(manager)
        val timedOut = !await(manager.requestScan(), timeoutSeconds)
        val after = snapshot(manager)

        val collectionsAdded = (after.keys - before.keys).sorted()
        val collectionsDeleted = (before.keys - after.keys).sorted()

        val resourcesAdded = mutableListOf<String>()
        val resourcesModified = mutableListOf<String>()
        val resourcesDeleted = mutableListOf<String>()

        // Only collections present on both sides: a whole collection appearing or disappearing is
        // reported at collection level rather than as hundreds of individual resources.
        for (name in before.keys.intersect(after.keys)) {
            val old = before.getValue(name)
            val new = after.getValue(name)
            (new.keys - old.keys).forEach { resourcesAdded += "$name/$it" }
            (old.keys - new.keys).forEach { resourcesDeleted += "$name/$it" }
            old.keys.intersect(new.keys)
                .filter { old[it] != new[it] }
                .forEach { resourcesModified += "$name/$it" }
        }

        return ScanResult(
            target = target,
            available = true,
            collectionsAdded = collectionsAdded,
            collectionsDeleted = collectionsDeleted,
            resourcesAdded = resourcesAdded.sorted(),
            resourcesModified = resourcesModified.sorted(),
            resourcesDeleted = resourcesDeleted.sorted(),
            timedOut = timedOut,
            waitedMs = (System.nanoTime() - startedNanos) / 1_000_000,
        )
    }

    /**
     * `collection name -> (resource path -> signature)`.
     *
     * **The 8.1 port point.** On 8.1 this becomes `manager.projectNames` and
     * `manager.getProject(name)`; there is no generic `find`. Everything else is unchanged.
     *
     * Failures are swallowed per collection: a collection that vanishes between listing and
     * reading is exactly what a scan does, and it should show up as a diff rather than as an error.
     */
    private fun snapshot(manager: ResourceCollectionManager): Map<String, Map<String, String>> =
        manager.names.associateWith { name ->
            runCatching {
                manager.find(name).orElse(null)
                    ?.allResources
                    ?.entries
                    ?.associate { (id, resource) ->
                        id.resourcePath.toString() to resource.resourceSignature.toString()
                    }
                    .orEmpty()
            }.getOrDefault(emptyMap())
        }

    /** True if the scan completed inside the timeout. Throws only if the scan itself failed. */
    private fun await(scan: CompletableFuture<Void>, timeoutSeconds: Int): Boolean = try {
        scan.get(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        true
    } catch (e: TimeoutException) {
        false
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (e: ExecutionException) {
        val cause = e.cause ?: e
        throw McpArgumentException("Scan failed: ${cause.javaClass.simpleName}: ${cause.message}")
    }

    companion object {
        const val TARGET_PROJECTS = "projects"
        const val TARGET_CONFIG = "config"
        const val TARGET_BOTH = "both"
    }
}
