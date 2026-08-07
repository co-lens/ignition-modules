package io.colens.mcp.common.perspective

/**
 * Where views come from, so the read tools can be written once and used in both scopes.
 *
 * The gateway implementation reads committed resources through `ProjectManager` and cannot
 * write. The Designer implementation reads and writes the open project, where writes land as
 * unsaved changes.
 */
interface ViewSource {

    /** True when [write] is supported. False on the gateway. */
    val canWrite: Boolean

    /** True when tools must ask the caller which project. False in the Designer. */
    val requiresProject: Boolean

    /** Resolves the project to operate on, validating it exists. */
    fun resolveProject(requested: String?): String

    fun listViews(project: String): List<ViewRef>

    fun read(project: String, viewPath: String): ViewDocument

    /** Persists a view. Implementations that stage rather than commit say so in [WriteOutcome]. */
    fun write(project: String, viewPath: String, view: ViewDocument): WriteOutcome

    fun delete(project: String, viewPath: String): WriteOutcome
}

data class ViewRef(val path: String, val sizeBytes: Int?)

data class WriteOutcome(
    val created: Boolean,
    val committed: Boolean,
    val note: String,
)
