package io.colens.mcp.designer.tools

import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation
import com.inductiveautomation.ignition.common.resourcecollection.Resource
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType
import com.inductiveautomation.ignition.designer.IgnitionDesigner
import com.inductiveautomation.ignition.designer.model.DesignerContext
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

                val bytes = resource.getData(key).orElse(null)?.bytes
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
                    ?: Resource.DEFAULT_JSON_KEY

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
            ?: keys.firstOrNull { it == Resource.DEFAULT_DATA_KEY || it == Resource.DEFAULT_JSON_KEY }
            ?: keys.firstOrNull()

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
     * Resource paths where an unsaved Designer edit collides with a change the gateway has pushed.
     * Merging one of these is what would destroy the local edit.
     *
     * Two predicates, deliberately unioned: `isConflict(path)` and `getConflicts(changes)` are both
     * public API on both platform lines but take different internal code paths, and I could not
     * prove they agree. A false refusal costs one Designer action; a false clear costs the user
     * their work.
     */
    private fun conflictingPaths(): List<String> {
        val project = project()
        val changes = project.changes.orEmpty()

        val byPath = changes
            .mapNotNull { ChangeOperation.getResourceIdFromChange(it)?.resourcePath }
            .filter { project.isConflict(it) }

        val byOperation = project.getConflicts(changes)
            .mapNotNull { ChangeOperation.getResourceIdFromChange(it)?.resourcePath }

        return (byPath + byOperation).map { it.toString() }.distinct().sorted()
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

    private companion object {
        const val POLL_MILLIS = 250L
        const val QUIET_MILLIS = 750L
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
