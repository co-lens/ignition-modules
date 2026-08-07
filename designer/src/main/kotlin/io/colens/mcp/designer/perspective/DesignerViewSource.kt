package io.colens.mcp.designer.perspective

import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType
import com.inductiveautomation.ignition.designer.model.DesignerContext
import com.inductiveautomation.perspective.common.config.ViewConfig
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.McpJson
import io.colens.mcp.common.perspective.ViewDocument
import io.colens.mcp.common.perspective.ViewRef
import io.colens.mcp.common.perspective.ViewSource
import io.colens.mcp.common.perspective.WriteOutcome
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities

/**
 * Views in the open Designer project.
 *
 * Writes go through `DesignableProject.createOrModify`, which stages them as **unsaved Designer
 * changes** — they appear exactly as if a person had typed them, and a human still has to review
 * and Save before anything reaches the gateway. Nothing here commits.
 */
class DesignerViewSource(private val context: DesignerContext) : ViewSource {

    override val canWrite = true
    override val requiresProject = false

    private val resourceType: ResourceType get() = ViewConfig.RESOURCE_TYPE
    private val dataKey: String get() = ViewConfig.RESOURCE_FILENAME

    override fun resolveProject(requested: String?): String =
        context.projectName ?: throw McpArgumentException("No project is open in this Designer.")

    override fun listViews(project: String): List<ViewRef> = onEdt {
        designableProject().allResources.entries
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

    override fun read(project: String, viewPath: String): ViewDocument = onEdt {
        val resource = designableProject().getResource(ResourcePath(resourceType, viewPath)).orElse(null)
            ?: throw McpArgumentException(
                "No Perspective view '$viewPath' in the open project. " +
                    "Call perspective_list_views to see what's there."
            )
        val bytes = resource.getData(dataKey).orElse(null)?.bytes
            ?: throw McpArgumentException("View '$viewPath' has no $dataKey")
        ViewDocument.parse(String(bytes, StandardCharsets.UTF_8))
    }

    override fun write(project: String, viewPath: String, view: ViewDocument): WriteOutcome = onEdt {
        val path = ResourcePath(resourceType, viewPath)
        val designable = designableProject()
        val existed = designable.getResource(path).isPresent

        val bytes = McpJson.toPrettyString(view.json()).toByteArray(StandardCharsets.UTF_8)
        designable.createOrModify(path) { builder -> builder.putData(dataKey, bytes) }

        WriteOutcome(
            created = !existed,
            committed = false,
            note = "Staged as an unsaved Designer change. Review it in the Designer and save to apply.",
        )
    }

    override fun delete(project: String, viewPath: String): WriteOutcome = onEdt {
        val path = ResourcePath(resourceType, viewPath)
        val designable = designableProject()
        designable.getResource(path).orElse(null)
            ?: throw McpArgumentException("No Perspective view '$viewPath' in the open project")
        designable.deleteResource(path)
        WriteOutcome(
            created = false,
            committed = false,
            note = "Deletion staged as an unsaved Designer change. Save in the Designer to apply it.",
        )
    }

    private fun designableProject() =
        context.project ?: throw McpArgumentException("No project is open in this Designer yet.")

    /** Project state is Swing-adjacent; HTTP handlers run on their own threads. */
    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val task = FutureTask(Callable { block() })
        SwingUtilities.invokeLater(task)
        return try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
