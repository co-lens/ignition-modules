package io.colens.mcp.designer.tools

import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.client.gateway_interface.GatewayConnectionManager
import com.inductiveautomation.ignition.common.project.ChangeOperation
import com.inductiveautomation.ignition.common.project.resource.ProjectResource
import com.inductiveautomation.ignition.common.project.resource.ResourcePath
import com.inductiveautomation.ignition.common.project.resource.ResourceType
import com.inductiveautomation.ignition.designer.IgnitionDesigner
import com.inductiveautomation.ignition.designer.project.DesignerProjectTreeImpl
import com.inductiveautomation.ignition.designer.model.DesignerContext
import io.colens.mcp.common.Constants
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.Tool
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonArrayOfStrings
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.optInt
import io.colens.mcp.common.optString
import io.colens.mcp.common.put
import io.colens.mcp.common.requireString
import io.colens.mcp.common.schema
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities

/**
 * Tools backed by the open Designer.
 *
 * The point of these over the gateway equivalents is that writes land as **unsaved Designer
 * changes**: they show up in the Designer exactly as if a person had typed them, and a human
 * still has to review and Save before anything reaches the gateway. Nothing here commits.
 */
class DesignerTools(private val context: DesignerContext) {

    fun tools(): List<Tool> = listOf(
        designerInfo(),
        listResources(),
        readResource(),
        listPendingChanges(),
        mergeGatewayChanges(),
        writeResource(),
        deleteResource(),
    )

    private fun designerInfo() = Tool(
        name = "designer_info",
        title = "Designer info",
        description = "Which project this Designer has open, and how many unsaved changes it holds.",
        inputSchema = schema(),
        handler = {
            onEdt {
                jsonObject {
                    put("project", context.projectName)
                    put("pendingChanges", project().changes.orEmpty().size)
                }
            }
        },
    )

    private fun listResources() = Tool(
        name = "list_resources",
        title = "List resources in the open project",
        description = "Lists project resources as the Designer currently sees them, including " +
            "unsaved edits. Filter by moduleId and/or type; call with no filter first to see " +
            "which types exist.",
        inputSchema = schema {
            string("moduleId", "Only return resources owned by this module id.")
            string("type", "Only return resources of this resource type.")
            string("pathContains", "Only return resources whose path contains this substring (case-insensitive).")
            integer("limit", "Maximum number of resources to return.", default = 500)
        },
        handler = { args ->
            val moduleId = args.optString("moduleId")
            val type = args.optString("type")
            val pathContains = args.optString("pathContains")?.lowercase()
            val limit = args.optInt("limit", 500)

            onEdt {
                val project = project()
                val all = project.allResources

                val matching = all.keys
                    .asSequence()
                    .filter { moduleId == null || it.resourcePath.moduleId == moduleId }
                    .filter { type == null || it.resourcePath.type == type }
                    .filter { pathContains == null || it.resourcePath.path.toString().lowercase().contains(pathContains) }
                    .sortedBy { it.resourcePath.toString() }
                    .toList()

                val listed = JsonArray()
                matching.take(limit).forEach { id ->
                    listed.add(jsonObject {
                        put("moduleId", id.resourcePath.moduleId)
                        put("type", id.resourcePath.type)
                        put("path", id.resourcePath.path.toString())
                        put("changed", project.isChanged(id.resourcePath))
                        put("inherited", project.isInherited(id.resourcePath))
                        put("definedIn", project.getDefiningProject(id.resourcePath))
                    })
                }

                jsonObject {
                    put("project", context.projectName)
                    put("total", matching.size)
                    put("count", listed.size())
                    put("truncated", matching.size > listed.size())
                    put("availableTypes", jsonArrayOfStrings(
                        all.keys.map { "${it.resourcePath.moduleId}/${it.resourcePath.type}" }.distinct().sorted()
                    ))
                    put("resources", listed)
                }
            }
        },
    )

    private fun readResource() = Tool(
        name = "read_resource",
        title = "Read a resource from the open project",
        description = "Returns a resource's contents as the Designer sees it, including unsaved " +
            "edits. Perspective views come back as view.json, scripts as code.py.",
        inputSchema = schema {
            string("moduleId", "Module id that owns the resource.", required = true)
            string("type", "Resource type.", required = true)
            string("path", "Resource path, e.g. 'Page/Main'.", required = true)
            string("dataKey", "Which data file to read. Defaults to the resource's primary file.")
            integer("maxBytes", "Truncate the returned content beyond this many bytes.", default = 262144)
        },
        handler = { args ->
            val resourcePath = resourcePath(args)
            val dataKey = args.optString("dataKey")
            val maxBytes = args.optInt("maxBytes", 262_144)

            onEdt {
                val project = project()
                val resource = project.getResource(resourcePath).orElse(null)
                    ?: throw McpArgumentException("No resource '$resourcePath' in the open project")

                val keys = resource.dataKeys.toList()
                val key = dataKey ?: primaryDataKey(keys)
                    ?: throw McpArgumentException("Resource '$resourcePath' has no data")

                // 8.1 returns byte[] directly, not Optional<ImmutableBytes>, and may throw
                // rather than return null for an absent key.
                val bytes = runCatching { resource.getData(key) }.getOrNull()
                    ?: throw McpArgumentException("Resource '$resourcePath' has no data under key '$key'")

                jsonObject {
                    put("moduleId", resourcePath.moduleId)
                    put("type", resourcePath.type)
                    put("path", resourcePath.path.toString())
                    put("dataKey", key)
                    put("dataKeys", jsonArrayOfStrings(keys))
                    put("changed", project.isChanged(resourcePath))
                    put("inherited", project.isInherited(resourcePath))
                    put("sizeBytes", bytes.size)
                    put("truncated", bytes.size > maxBytes)
                    put("content", String(bytes, 0, minOf(bytes.size, maxBytes), StandardCharsets.UTF_8))
                }
            }
        },
    )

    private fun listPendingChanges() = Tool(
        name = "list_pending_changes",
        title = "List unsaved Designer changes",
        description = "Lists edits made in this Designer that have not been saved to the gateway yet. " +
            "Use this to review what write_resource has staged before telling the user to save.",
        inputSchema = schema(),
        handler = {
            onEdt {
                val changes = project().changes.orEmpty()
                jsonObject {
                    put("project", context.projectName)
                    put("count", changes.size)
                    put("changes", jsonArrayOf(changes.map { change ->
                        val id = ChangeOperation.getResourceIdFromChange(change)
                        jsonObject {
                            put("operation", change.operationType?.toString())
                            put("moduleId", id?.resourcePath?.moduleId)
                            put("type", id?.resourcePath?.type)
                            put("path", id?.resourcePath?.path?.toString())
                        }
                    }))
                }
            }
        },
    )

    private fun writeResource() = Tool(
        name = "write_resource",
        title = "Create or update a resource in the open Designer",
        description = "Writes a project resource in the open Designer. The edit is NOT committed — " +
            "it appears as an unsaved change for the user to review and save, which is the point: " +
            "keep a human in the loop. Read the resource first so you preserve its existing shape.",
        inputSchema = schema {
            string("moduleId", "Module id that owns the resource.", required = true)
            string("type", "Resource type.", required = true)
            string("path", "Resource path, e.g. 'Page/Main'.", required = true)
            string("content", "New contents for the resource.", required = true)
            string("dataKey", "Which data file to write. Defaults to the resource's primary file, " +
                "or 'data.json' when creating a new resource.")
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val resourcePath = resourcePath(args)
            val content = args.requireString("content")
            val requestedKey = args.optString("dataKey")

            onEdt {
                val project = project()
                val existing = project.getResource(resourcePath).orElse(null)
                val key = requestedKey
                    ?: existing?.dataKeys?.toList()?.let { primaryDataKey(it) }
                    ?: Constants.DEFAULT_JSON_KEY

                project.createOrModify(resourcePath) { builder ->
                    builder.putData(key, content.toByteArray(StandardCharsets.UTF_8))
                }

                jsonObject {
                    put("project", context.projectName)
                    put("path", resourcePath.toString())
                    put("dataKey", key)
                    put("created", existing == null)
                    put("bytesWritten", content.toByteArray(StandardCharsets.UTF_8).size)
                    put("committed", false)
                    put("note", "Staged as an unsaved Designer change. Save in the Designer to apply it.")
                }
            }
        },
    )

    private fun deleteResource() = Tool(
        name = "delete_resource",
        title = "Delete a resource in the open Designer",
        description = "Deletes a project resource in the open Designer. Like write_resource, this is " +
            "staged as an unsaved change rather than committed.",
        inputSchema = schema {
            string("moduleId", "Module id that owns the resource.", required = true)
            string("type", "Resource type.", required = true)
            string("path", "Resource path to delete.", required = true)
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val resourcePath = resourcePath(args)
            onEdt {
                val project = project()
                project.getResource(resourcePath).orElse(null)
                    ?: throw McpArgumentException("No resource '$resourcePath' in the open project")
                project.deleteResource(resourcePath)

                jsonObject {
                    put("project", context.projectName)
                    put("path", resourcePath.toString())
                    put("committed", false)
                    put("note", "Staged as an unsaved Designer change. Save in the Designer to apply it.")
                }
            }
        },
    )

    // -----------------------------------------------------------------------

    /**
     * [DesignerContext.getProject] is nullable — it is empty before a project finishes opening.
     * Fail with a message the model can act on rather than a NullPointerException.
     */
    private fun project(): com.inductiveautomation.ignition.designer.project.DesignableProject =
        context.project
            ?: throw McpArgumentException("No project is open in this Designer yet.")

    private fun resourcePath(args: com.inductiveautomation.ignition.common.gson.JsonObject) = ResourcePath(
        ResourceType(args.requireString("moduleId"), args.requireString("type")),
        args.requireString("path"),
    )

    private fun primaryDataKey(keys: List<String>): String? =
        keys.singleOrNull()
            ?: keys.firstOrNull { it == ProjectResource.DEFAULT_DATA_KEY || it == Constants.DEFAULT_JSON_KEY }
            ?: keys.firstOrNull()

    /**
     * **Deliberately not in [tools].** `DesignerHook` adds it only when [SAVE_PROPERTY] is set, so
     * a Designer that has not opted in never exposes it at all.
     *
     * **8.1 port point.** 8.3 pushes through `PlatformRpcInstances.PROJECTS_RPC.push`; 8.1 has no
     * `PlatformRpcInstances`, so this goes through
     * `GatewayConnectionManager.getInstance().gatewayInterface.pushProject` — the same chain
     * `conflictingPaths` already uses for the pull, and the one `PushTask` itself uses.
     *
     * 8.3 also pre-flights with `PROJECTS_RPC.canSaveProject`, which has no counterpart on this
     * line: 8.1's `GatewayInterface` exposes `pushProject` and `pullProject` and no permission
     * probe. Rather than approximate one, the push is simply attempted and its failure reported.
     * The practical exposure is unchanged — the tool is not registered at all unless the operator
     * set [SAVE_PROPERTY] — but the refusal arrives as whatever the gateway says rather than as a
     * specific "you lack save rights", so the description says so instead of implying a check that
     * is not happening.
     */
    fun saveTool() = Tool(
        name = "save_project",
        title = "Save the open project to the gateway",
        description = "Commits this Designer's staged changes to the gateway — the equivalent of " +
            "a human pressing Save. Available only because this Designer was started with " +
            "-D$SAVE_PROPERTY=true.\n\n" +
            "Refuses when a staged edit conflicts with a change already waiting on the gateway, " +
            "naming the resources, so a merge is never silently resolved in your favour; run " +
            "merge_gateway_changes first in that case. Does nothing and reports zero when there " +
            "is nothing staged. Reports the resource paths it committed, so a script can check " +
            "rather than assume — list_pending_changes returning 0 afterwards is the invariant.\n\n" +
            "On this Ignition 8.1 line there is no permission pre-check: 8.1 exposes no equivalent " +
            "of it, so a save the gateway will not accept fails at the push and is reported as " +
            "the gateway's own error rather than as a specific rights refusal. Nothing is " +
            "committed in that case and the changes stay staged here.\n\n" +
            "One limitation worth knowing: this pushes what is in the project tree, and does not " +
            "flush editors a human has open and unsaved in this Designer. On an unattended " +
            "Designer there are none. If somebody is working in this one, their open editor's " +
            "content will not be included.",
        inputSchema = schema(),
        readOnly = false,
        destructive = true,
        handler = {
            // Read project state on the EDT; do the RPC off it. onEdt blocks with no timeout, and
            // a push can take as long as the gateway takes — see the note in merge_gateway_changes
            // about parking the bridge's HTTP threads.
            val changes = onEdt { project().changes.orEmpty().toList() }

            if (changes.isEmpty()) {
                jsonObject {
                    put("project", context.projectName)
                    put("committed", 0)
                    put("resources", JsonArray())
                    put("note", "Nothing was staged, so nothing was committed.")
                }
            } else {
                // Same pre-flight as merge_gateway_changes, and for the same reason: this is the
                // case where "a human reviews it" was doing real work, so it survives unattended.
                val conflicts = onEdt { conflictingPaths() }
                if (conflicts.isNotEmpty()) {
                    throw McpArgumentException(
                        // Deliberately does NOT send the caller to merge_gateway_changes: it
                        // refuses on this same predicate, so here it is guaranteed to refuse too.
                        "Refusing to save: ${conflicts.size} staged edit(s) conflict with changes " +
                            "waiting on the gateway. Saving would resolve that in this Designer's " +
                            "favour without anyone looking. merge_gateway_changes refuses on the " +
                            "same conflict, so ask the user to save or discard these in the " +
                            "Designer. Conflicting resources: ${conflicts.joinToString(", ")}"
                    )
                }

                val paths = changes.mapNotNull {
                    ChangeOperation.getResourceIdFromChange(it)?.resourcePath?.toString()
                }.distinct().sorted()

                try {
                    GatewayConnectionManager.getInstance().gatewayInterface.pushProject(changes.toList())
                } catch (e: Exception) {
                    throw McpArgumentException(
                        "The gateway rejected the save: ${e.message ?: e.javaClass.simpleName}. " +
                            "Nothing was committed and the changes are still staged here."
                    )
                }

                // Tells the Designer its changes landed, so its own change list drains and the UI
                // stops showing them as pending.
                onEdt {
                    (project() as? DesignerProjectTreeImpl)?.notifyPushComplete(changes.toList())
                }

                val remaining = onEdt { project().changes.orEmpty().size }

                jsonObject {
                    put("project", context.projectName)
                    put("committed", paths.size)
                    put("resources", jsonArrayOfStrings(paths))
                    put("pendingAfter", remaining)
                    put(
                        "note",
                        if (remaining == 0) {
                            "Committed to the gateway. Nothing is staged in this Designer now."
                        } else {
                            "Committed to the gateway, but $remaining change(s) are still staged — " +
                                "most likely edits made while the save was in flight. Call this " +
                                "again to commit those too."
                        },
                    )
                }
            }
        },
    )

    private fun mergeGatewayChanges() = Tool(
        name = "merge_gateway_changes",
        title = "Merge gateway changes into this Designer",
        description = "Pulls changes made on the gateway into the open project — the same action " +
            "as the Designer's 'update available' notification button. Use it after " +
            "scan_resource_files on the gateway picked up edited files, or when the user says the " +
            "Designer is showing stale resources. This REFUSES to run when an unsaved Designer " +
            "edit conflicts with an incoming change, and names the resources, so your edits are " +
            "never silently overwritten; on a refusal, tell the user to save or discard those " +
            "resources in the Designer and call this again. Ignition reports no completion for an " +
            "update, so this watches the project and tells you which resources actually arrived.",
        inputSchema = schema {
            integer(
                "waitSeconds",
                "How long to watch for merged resources to arrive. The merge carries on " +
                    "regardless — only the wait is bounded.",
                default = 15,
            )
            integer(
                "settleSeconds",
                "How long to let a just-finished gateway scan's notification reach this Designer " +
                    "before the conflict check runs. Only paid when there are unsaved edits.",
                default = 3,
            )
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val waitSeconds = args.optInt("waitSeconds", 15).coerceIn(0, 120)
            val settleSeconds = args.optInt("settleSeconds", 3).coerceIn(0, 60)

            // Resolve the frame first: a cast failure inside the invokeLater below would vanish
            // into the EDT's exception handler with nothing to return.
            val designer = onEdt { context.frame } as? IgnitionDesigner
                ?: throw McpArgumentException(
                    "This Designer's frame is not an IgnitionDesigner, so the update action is " +
                        "unavailable. Ask the user to use the update notification button instead."
                )

            val localChanges = onEdt { project().changes.orEmpty().size }

            // isConflict() reflects only what the gateway has already PUSHED to this Designer.
            // Straight after a gateway scan that push may still be in flight, so checking now
            // could read clean and merge over an edit. An unsaved edit is the only thing a
            // conflict can destroy, so pay the settle only when one exists.
            val settleWaitedMs =
                if (localChanges > 0 && settleSeconds > 0) sleepMillis(settleSeconds * 1000L) else 0L

            val conflicts = onEdt { conflictingPaths() }
            if (conflicts.isNotEmpty()) {
                throw McpArgumentException(
                    "Refusing to merge: ${conflicts.size} unsaved Designer edit(s) conflict with " +
                        "changes waiting on the gateway, and merging would discard them. Ask the " +
                        "user to save or discard these in the Designer, then call this again. " +
                        "Conflicting resources: ${conflicts.joinToString(", ")}"
                )
            }

            val before = onEdt { resourceSignatures() }
            val startedNanos = System.nanoTime()

            // invokeLater, never invokeAndWait or onEdt. On 8.1 updateProject() runs commitAll()
            // and pullAndResolve() inline, and pullAndResolve opens a MODAL ProgressDialog — it
            // does not return until that dialog is dismissed. Blocking here would park one of the
            // bridge's four HTTP threads behind a human; four such calls and the bridge is dead
            // with no error anywhere. On 8.3 it returns immediately regardless.
            SwingUtilities.invokeLater { designer.updateProject() }

            // Ignition gives no completion signal either way, so observe rather than assume:
            // poll the resource table until it changes and then stops changing. The same
            // signature-diff technique the gateway scan uses, and for the same reason — the
            // listener callbacks cannot be relied on to fire for this.
            val after = awaitQuiet(before, waitSeconds * 1000L)
            val (added, modified, deleted) = diff(before, after)
            val observed = added.isNotEmpty() || modified.isNotEmpty() || deleted.isNotEmpty()

            jsonObject {
                put("project", context.projectName)
                put("requested", true)
                put("localChanges", localChanges)
                put("resourcesAdded", jsonArrayOfStrings(added))
                put("resourcesModified", jsonArrayOfStrings(modified))
                put("resourcesDeleted", jsonArrayOfStrings(deleted))
                put("changesObserved", observed)
                put("settleWaitedMs", settleWaitedMs)
                put("waitedMs", (System.nanoTime() - startedNanos) / 1_000_000)
                put("note", if (observed) {
                    "The Designer merged these resources from the gateway. Any unsaved edits you " +
                        "already had are untouched and still need saving."
                } else {
                    "No resources changed within ${waitSeconds}s. Ignition gives no completion " +
                        "signal for an update, so this means one of: this Designer was already up " +
                        "to date; the merge is still running; or a dialog is waiting on screen. " +
                        "Read a resource you expected to change to tell which, and ask the user " +
                        "to look at the Designer window."
                })
            }
        },
    )

    /**
     * Resource paths where an unsaved Designer edit collides with a change waiting on the gateway.
     * Merging one of these is what would destroy the local edit.
     *
     * This asks the gateway for the incoming changes and runs them through the project's own
     * conflict test — the same two steps Ignition's `pullAndResolve` performs before it decides
     * whether to open its resolution dialog. `pull` is the read half only: it returns diffs and
     * applies nothing, which is why `pullAndResolve` can still abort afterwards without having
     * changed the project.
     *
     * Two cheaper signals were tried first and measured against a real Designer. Both were wrong:
     *
     *  - `DesignableProject.isConflict(path)` walks up from the path's PARENT, so a sibling
     *    changing under the same folder marked an untouched resource as conflicted — a false
     *    refusal.
     *  - `DesignerResourceEditManager.hasConflict(path)` reads state built from gateway push
     *    notifications and never reported a genuine same-resource conflict at all, even ten
     *    seconds after the change had landed. Not a timing problem — the wrong signal.
     *
     * Requires the concrete [DesignerProjectTreeImpl] for `getProjectSnapshots()`;
     * `DesignerContextImpl` returns exactly that type, so the cast holds in a real Designer and
     * degrades to "no conflicts detected" rather than an error if it ever doesn't.
     *
     * **8.1 port point.** 8.3 reaches the pull through `PlatformRpcInstances.PROJECTS_RPC.pull` and
     * `getSnapshots()`; 8.1 goes through
     * `GatewayConnectionManager.getInstance().gatewayInterface.pullProject` and
     * `getProjectSnapshots()`, returning `ProjectDiff` rather than `ResourceCollectionDiff`. Same
     * three steps, same meaning — this is the chain `PullTask` itself uses.
     */
    private fun conflictingPaths(): List<String> {
        val project = project()
        val localChanges = project.changes.orEmpty()
        if (localChanges.isEmpty()) return emptyList()

        val tree = project as? DesignerProjectTreeImpl ?: return emptyList()
        val incoming = runCatching {
            GatewayConnectionManager.getInstance().gatewayInterface
                .pullProject(tree.projectSnapshots)
                .flatMap { it.changeOperations.orEmpty() }
        }.getOrElse { return emptyList() }

        return project.getConflicts(incoming)
            .mapNotNull { ChangeOperation.getResourceIdFromChange(it)?.resourcePath }
            .map { it.toString() }
            .distinct()
            .sorted()
    }

    /** `resource path -> signature`, for the open project. */
    private fun resourceSignatures(): Map<String, String> =
        runCatching {
            project().allResources.entries.associate { (id, resource) ->
                id.resourcePath.toString() to resource.resourceSignature.toString()
            }
        }.getOrDefault(emptyMap())

    /**
     * Polls until the resource table has changed and then stopped changing, or the budget runs out.
     * A merge lands as a burst, so "changed, then quiet for a moment" is a better completion signal
     * than a fixed sleep — and it returns immediately in the common case of nothing to do.
     */
    private fun awaitQuiet(before: Map<String, String>, budgetMillis: Long): Map<String, String> {
        val deadline = System.nanoTime() + budgetMillis * 1_000_000
        var latest = before
        var lastChangeNanos = 0L

        while (System.nanoTime() < deadline) {
            sleepMillis(POLL_MILLIS)
            val now = onEdt { resourceSignatures() }
            if (now != latest) {
                latest = now
                lastChangeNanos = System.nanoTime()
            } else if (lastChangeNanos != 0L &&
                System.nanoTime() - lastChangeNanos >= QUIET_MILLIS * 1_000_000
            ) {
                break
            }
        }
        return latest
    }

    private fun diff(
        before: Map<String, String>,
        after: Map<String, String>,
    ): Triple<List<String>, List<String>, List<String>> = Triple(
        (after.keys - before.keys).sorted(),
        before.keys.intersect(after.keys).filter { before[it] != after[it] }.sorted(),
        (before.keys - after.keys).sorted(),
    )

    /** Sleeps without letting an interrupt escape into a tool result. Returns what it slept. */
    private fun sleepMillis(millis: Long): Long {
        val started = System.nanoTime()
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    companion object {
        private const val POLL_MILLIS = 250L
        private const val QUIET_MILLIS = 750L

        /**
         * `-Dmcp.designer.allowSave=true` registers [saveTool]. Named as a `const` so the WARN and
         * the tool's own description interpolate it and can never drift from the string read here.
         *
         * Off by default, because committing without review is exactly what the Designer scope
         * exists not to do. On, it is loud — see `DesignerHook`.
         */
        const val SAVE_PROPERTY = "mcp.designer.allowSave"

        /** Tolerant of the whitespace a shell-quoted `-D` can leave behind. */
        fun saveAllowed(): Boolean =
            System.getProperty(SAVE_PROPERTY)?.trim()?.equals("true", ignoreCase = true) == true
    }

    /**
     * HTTP handlers run on their own threads, but project state is Swing-adjacent and expects the
     * event dispatch thread. Marshal onto it and propagate any failure to the caller.
     */
    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val task = FutureTask(Callable { block() })
        SwingUtilities.invokeLater(task)
        return try {
            task.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }
}
