package io.colens.mcp.gateway.tools

import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.project.resource.ProjectResource
import com.inductiveautomation.ignition.common.project.resource.ResourcePath
import com.inductiveautomation.ignition.common.project.resource.ResourceType
import com.inductiveautomation.ignition.gateway.model.GatewayContext
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

/**
 * Read access to committed project resources: Perspective views, scripts, named queries, UDT
 * definitions, alarm pipelines — whatever the gateway is running.
 *
 * These read the *gateway's* copy, so they work headless with no Designer open. For editing,
 * use the Designer bridge, where changes land as reviewable pending edits.
 */
class ProjectTools(private val context: GatewayContext) {

    fun tools(): List<Tool> = listOf(
        listProjects(),
        listProjectResources(),
        readProjectResource(),
    )

    private fun listProjects() = Tool(
        name = "list_projects",
        title = "List projects",
        description = "Lists the projects on this gateway with their inheritance and enabled state.",
        inputSchema = schema(),
        handler = {
            val manager = context.projectManager
            val manifests = manager.projectManifests
            jsonObject {
                put("projects", jsonArrayOf(manager.projectNames.map { name ->
                    // 8.1's ProjectManifest is a bean, not a record: isEnabled/isInheritable, not
                    // enabled()/inheritable(). A wrong accessor here would compile and return nulls.
                    val manifest = manifests[name]
                    jsonObject {
                        put("name", name)
                        put("title", manifest?.title)
                        put("description", manifest?.description)
                        put("parent", manifest?.parent)
                        put("enabled", manifest?.isEnabled)
                        put("inheritable", manifest?.isInheritable)
                    }
                }))
            }
        },
    )

    private fun listProjectResources() = Tool(
        name = "list_project_resources",
        title = "List project resources",
        description = "Lists resources in a project. Filter by module id and/or resource type to " +
            "narrow it down — for example moduleId 'com.inductiveautomation.perspective' with type " +
            "'views' lists Perspective views, and moduleId 'ignition' type 'script-python' lists " +
            "project script modules. Call with no filter first to discover what types exist.",
        inputSchema = schema {
            string("project", "Project name.", required = true)
            string("moduleId", "Only return resources owned by this module id.")
            string("type", "Only return resources of this resource type.")
            string("pathContains", "Only return resources whose path contains this substring (case-insensitive).")
            integer("limit", "Maximum number of resources to return.", default = 500)
        },
        handler = { args ->
            val project = args.requireString("project")
            val moduleId = args.optString("moduleId")
            val type = args.optString("type")
            val pathContains = args.optString("pathContains")?.lowercase()
            val limit = args.optInt("limit", 500)

            val all = runtimeProject(project).allResources

            val matching = all.keys
                .asSequence()
                .filter { moduleId == null || it.resourcePath.moduleId == moduleId }
                .filter { type == null || it.resourcePath.type == type }
                .filter { pathContains == null || it.resourcePath.path.toString().lowercase().contains(pathContains) }
                .sortedBy { it.resourcePath.toString() }
                .toList()

            val listed = JsonArray()
            matching.take(limit).forEach { id ->
                val resource = all[id]
                listed.add(jsonObject {
                    put("moduleId", id.resourcePath.moduleId)
                    put("type", id.resourcePath.type)
                    put("path", id.resourcePath.path.toString())
                    put("definedIn", resource?.projectName)
                    put("dataKeys", jsonArrayOfStrings(resource?.dataKeys ?: emptySet()))
                })
            }

            jsonObject {
                put("project", project)
                put("total", matching.size)
                put("count", listed.size())
                put("truncated", matching.size > listed.size())
                // Handy for a follow-up call when the caller doesn't yet know what's in here.
                put("availableTypes", jsonArrayOfStrings(
                    all.keys.map { "${it.resourcePath.moduleId}/${it.resourcePath.type}" }.distinct().sorted()
                ))
                put("resources", listed)
            }
        },
    )

    private fun readProjectResource() = Tool(
        name = "read_project_resource",
        title = "Read a project resource",
        description = "Returns the contents of one project resource. Perspective views come back as " +
            "view.json, Python scripts as code.py. Use list_project_resources to find the exact " +
            "moduleId, type and path.",
        inputSchema = schema {
            string("project", "Project name.", required = true)
            string("moduleId", "Module id that owns the resource.", required = true)
            string("type", "Resource type.", required = true)
            string("path", "Resource path within the type, e.g. 'Page/Main'.", required = true)
            string("dataKey", "Which data file to read. Defaults to the resource's single/primary file.")
            integer("maxBytes", "Truncate the returned content beyond this many bytes.", default = 262144)
        },
        handler = { args ->
            val project = args.requireString("project")
            val resourcePath = ResourcePath(
                ResourceType(args.requireString("moduleId"), args.requireString("type")),
                args.requireString("path"),
            )

            // 8.1's ProjectManager has no getResource(project, path); go via the project. Side
            // effect: a bad project name now fails with "No such project" rather than "No resource".
            val resource = runtimeProject(project).getResource(resourcePath).orElse(null)
                ?: throw McpArgumentException("No resource '$resourcePath' in project '$project'")

            val keys = resource.dataKeys.toList()
            val key = args.optString("dataKey")
                ?: keys.singleOrNull()
                ?: keys.firstOrNull { it == ProjectResource.DEFAULT_DATA_KEY || it == Constants.DEFAULT_JSON_KEY }
                ?: keys.firstOrNull()
                ?: throw McpArgumentException("Resource '$resourcePath' has no data")

            // 8.1 returns the byte[] directly rather than an Optional<ImmutableBytes>, and may
            // throw rather than return null for an absent key.
            val bytes = runCatching { resource.getData(key) }.getOrNull()
                ?: throw McpArgumentException("Resource '$resourcePath' has no data under key '$key'")

            val maxBytes = args.optInt("maxBytes", 262_144)
            val truncated = bytes.size > maxBytes
            val text = String(bytes, 0, minOf(bytes.size, maxBytes), StandardCharsets.UTF_8)

            jsonObject {
                put("project", project)
                put("moduleId", resourcePath.moduleId)
                put("type", resourcePath.type)
                put("path", resourcePath.path.toString())
                put("dataKey", key)
                put("dataKeys", jsonArrayOfStrings(keys))
                put("definedIn", resource.projectName)
                put("documentation", resource.documentation)
                put("sizeBytes", bytes.size)
                put("truncated", truncated)
                put("content", text)
            }
        },
    )

    private fun runtimeProject(project: String) =
        context.projectManager.getProject(project).orElse(null)
            ?: throw McpArgumentException(
                "No such project '$project'. Available: ${context.projectManager.projectNames}"
            )
}
