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
import io.colens.mcp.gateway.project.ResourceScanner
import io.colens.mcp.gateway.project.ScanResult
import java.nio.charset.StandardCharsets

/**
 * Read access to committed project resources: Perspective views, scripts, named queries, UDT
 * definitions, alarm pipelines — whatever the gateway is running.
 *
 * These read the *gateway's* copy, so they work headless with no Designer open. For editing,
 * use the Designer bridge, where changes land as reviewable pending edits.
 */
class ProjectTools(private val context: GatewayContext) {

    // Constructor only stores the context, so the doc generator's stub is never called during
    // construction — mirrors SystemTools' `private val trial = TrialResetter(context)`.
    private val scanner = ResourceScanner(context)

    fun tools(): List<Tool> = listOf(
        listProjects(),
        listProjectResources(),
        readProjectResource(),
        scanResourceFiles(),
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

    private fun scanResourceFiles() = Tool(
        name = "scan_resource_files",
        title = "Scan resource files from disk",
        description = "Makes the gateway re-read its resources from disk and load what is actually " +
            "there. Use it after files were edited directly, or after a git checkout, pull, branch " +
            "switch or stash, so the running gateway matches the working tree. On Ignition 8.3 " +
            "this is the ONLY thing that makes a disk edit take effect: 8.3 never scans on its " +
            "own. Two collections: 'projects' is data/projects (views, scripts, named queries) and " +
            "'config' is data/config (tags, device connections, themes) which exists only on 8.3. " +
            "This scans EVERY project, not one — a project directory that has appeared is " +
            "registered, and a project whose directory is GONE is deleted from the gateway, so " +
            "check with the user before running it against a working tree you don't recognise. " +
            "Follow up with read_project_resource to confirm the new contents actually loaded.",
        inputSchema = schema {
            enumString(
                "target",
                "Which collection to scan. 'config' is Ignition 8.3 only.",
                values = listOf("both", "projects", "config"),
                default = "both",
            )
            integer(
                "timeoutSeconds",
                "How long to wait for the scan to finish. The scan carries on regardless — only " +
                    "the wait is bounded.",
                default = 30,
            )
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val target = args.optString("target") ?: ResourceScanner.TARGET_BOTH
            val timeoutSeconds = args.optInt("timeoutSeconds", 30).coerceIn(1, 300)
            val results = scanner.scan(target, timeoutSeconds)

            val scanned = results.filter { it.available }
            val changedCount = scanned.sumOf { it.changedCount }
            val timedOut = scanned.any { it.timedOut }

            jsonObject {
                put("target", target)
                put("changedCount", changedCount)
                put("timedOut", timedOut)
                put("results", jsonArrayOf(results.map { it.toJson() }))
                put("projects", jsonArrayOfStrings(context.projectManager.projectNames))
                put("note", when {
                    timedOut ->
                        "The scan did not finish within ${timeoutSeconds}s. It is still running — " +
                            "anything listed is what had been reported by then. Re-read the " +
                            "resources you care about rather than scanning again."
                    changedCount == 0 ->
                        "The scan finished and reported no changes: the gateway already matched " +
                            "what is on disk. If you expected a change, check the file was written " +
                            "where the gateway reads it (data/projects/<name>/ or data/config/)."
                    else ->
                        "The gateway now matches the files on disk. Any Designer with one of these " +
                            "projects open will show an update notification; merge_gateway_changes " +
                            "on the Designer endpoint applies it."
                })
            }
        },
    )

    private fun ScanResult.toJson() = jsonObject {
        put("target", target)
        put("available", available)
        put("reason", unavailableReason)
        if (available) {
            put("collectionsAdded", jsonArrayOfStrings(collectionsAdded))
            put("collectionsDeleted", jsonArrayOfStrings(collectionsDeleted))
            // Capped: a branch switch can touch thousands of resources, and the count is the
            // useful part once a list stops being readable.
            put("resourcesAdded", jsonArrayOfStrings(resourcesAdded.take(MAX_LISTED)))
            put("resourcesModified", jsonArrayOfStrings(resourcesModified.take(MAX_LISTED)))
            put("resourcesDeleted", jsonArrayOfStrings(resourcesDeleted.take(MAX_LISTED)))
            put("resourcesAddedCount", resourcesAdded.size)
            put("resourcesModifiedCount", resourcesModified.size)
            put("resourcesDeletedCount", resourcesDeleted.size)
            put("changedCount", changedCount)
            put("timedOut", timedOut)
            put("waitedMs", waitedMs)
        }
    }

    private companion object {
        const val MAX_LISTED = 100
    }

    private fun runtimeProject(project: String) =
        context.projectManager.getProject(project).orElse(null)
            ?: throw McpArgumentException(
                "No such project '$project'. Available: ${context.projectManager.projectNames}"
            )
}
