package io.colens.mcp.gateway.perspective

import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import com.inductiveautomation.perspective.common.config.ViewConfig
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.perspective.ViewDocument
import io.colens.mcp.common.perspective.ViewRef
import io.colens.mcp.common.perspective.ViewSource
import io.colens.mcp.common.perspective.WriteOutcome
import java.nio.charset.StandardCharsets

/**
 * Committed views, read through `ProjectManager`. Read-only by design: the gateway deliberately
 * gains no project-mutation surface, so an AI edit always passes through a Designer where a human
 * reviews and saves it.
 *
 * Resource coordinates come from `ViewConfig.RESOURCE_TYPE` and `RESOURCE_FILENAME` rather than
 * hardcoded strings, so they follow whatever Perspective is installed.
 */
class GatewayViewSource(private val context: GatewayContext) : ViewSource {

    override val canWrite = false
    override val requiresProject = true

    private val resourceType: ResourceType get() = ViewConfig.RESOURCE_TYPE
    private val dataKey: String get() = ViewConfig.RESOURCE_FILENAME

    override fun resolveProject(requested: String?): String {
        val project = requested?.takeIf { it.isNotBlank() }
            ?: throw McpArgumentException("A 'project' is required on the gateway.")
        if (!context.projectManager.names.contains(project)) {
            throw McpArgumentException(
                "No such project '$project'. Available: ${context.projectManager.names}"
            )
        }
        return project
    }

    override fun listViews(project: String): List<ViewRef> {
        val collection = context.projectManager.find(project).orElse(null)
            ?: throw McpArgumentException("No such project '$project'")

        return collection.allResources.entries
            .filter { (id, _) -> id.resourcePath.resourceType == resourceType }
            // Resource-type folders share the view resource type but carry no view.json; without
            // this they'd show up as empty views.
            .filter { (_, resource) -> resource?.dataKeys?.contains(dataKey) == true }
            .map { (id, resource) ->
                ViewRef(
                    path = id.resourcePath.path.toString(),
                    sizeBytes = resource?.getData(dataKey)?.orElse(null)?.bytes?.size,
                )
            }
    }

    override fun read(project: String, viewPath: String): ViewDocument {
        val path = ResourcePath(resourceType, viewPath)
        val resource = context.projectManager.getResource(project, path).orElse(null)
            ?: throw McpArgumentException(
                "No Perspective view '$viewPath' in project '$project'. " +
                    "Call perspective_list_views to see what's there."
            )
        val bytes = resource.getData(dataKey).orElse(null)?.bytes
            ?: throw McpArgumentException("View '$viewPath' has no $dataKey")
        return ViewDocument.parse(String(bytes, StandardCharsets.UTF_8))
    }

    override fun write(project: String, viewPath: String, view: ViewDocument): WriteOutcome =
        throw McpArgumentException(
            "The gateway endpoint is read-only for Perspective views. Connect to a running " +
                "Designer to edit views — changes stage there for a human to review and save."
        )

    override fun delete(project: String, viewPath: String): WriteOutcome = write(project, viewPath, ViewDocument.parse("{}"))
}
