package io.colens.mcp.gateway.perspective

import com.inductiveautomation.ignition.common.project.resource.ResourcePath
import com.inductiveautomation.ignition.common.project.resource.ResourceType
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
        if (!context.projectManager.projectNames.contains(project)) {
            throw McpArgumentException(
                "No such project '$project'. Available: ${context.projectManager.projectNames}"
            )
        }
        return project
    }

    override fun listViews(project: String): List<ViewRef> {
        val runtimeProject = context.projectManager.getProject(project).orElse(null)
            ?: throw McpArgumentException("No such project '$project'")

        return runtimeProject.allResources.entries
            .filter { it.key.resourcePath.resourceType == resourceType }
            // Resource-type folders share the view resource type but carry no view.json; without
            // this they'd show up as empty views.
            .filter { it.value?.dataKeys?.contains(dataKey) == true }
            .map { entry ->
                ViewRef(
                    path = entry.key.resourcePath.path.toString(),
                    // 8.1 returns byte[] directly, not Optional<ImmutableBytes>.
                    sizeBytes = runCatching { entry.value?.getData(dataKey) }.getOrNull()?.size,
                )
            }
    }

    override fun read(project: String, viewPath: String): ViewDocument {
        val path = ResourcePath(resourceType, viewPath)
        val resource = context.projectManager.getProject(project).orElse(null)
            ?.getResource(path)?.orElse(null)
            ?: throw McpArgumentException(
                "No Perspective view '$viewPath' in project '$project'. " +
                    "Call perspective_list_views to see what's there."
            )
        val bytes = runCatching { resource.getData(dataKey) }.getOrNull()
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
