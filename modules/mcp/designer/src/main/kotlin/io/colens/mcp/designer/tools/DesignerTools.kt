package io.colens.mcp.designer.tools

import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.project.ChangeOperation
import com.inductiveautomation.ignition.common.project.resource.ProjectResource
import com.inductiveautomation.ignition.common.project.resource.ResourcePath
import com.inductiveautomation.ignition.common.project.resource.ResourceType
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
