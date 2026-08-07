package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonObject
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.Tool
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonArrayOfStrings
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.optString
import io.colens.mcp.common.put
import io.colens.mcp.common.requireString
import io.colens.mcp.common.schema

/**
 * The Perspective tools that only read. Built once here and registered by both scopes, so a model
 * that edits a view in the Designer reads it back through exactly the same shapes it would see
 * from the gateway.
 */
class PerspectiveReadTools(
    private val source: ViewSource,
    private val catalog: ComponentCatalog,
) {

    fun tools(): List<Tool> = listOf(
        listViews(),
        getView(),
        getComponent(),
        listComponentTypes(),
        getComponentType(),
        validateView(),
    )

    private fun projectArg(builder: io.colens.mcp.common.SchemaBuilder) {
        if (source.requiresProject) {
            builder.string("project", "Project name.", required = true)
        } else {
            builder.string("project", "Ignored — the Designer operates on the open project.")
        }
    }

    private fun listViews() = Tool(
        name = "perspective_list_views",
        title = "List Perspective views",
        description = "Lists the Perspective views in a project, with the number of components in each.",
        inputSchema = schema {
            projectArg(this)
            string("pathContains", "Only return views whose path contains this substring.")
        },
        handler = { args ->
            val project = source.resolveProject(args.optString("project"))
            val filter = args.optString("pathContains")?.lowercase()

            val views = source.listViews(project)
                .filter { filter == null || it.path.lowercase().contains(filter) }
                .sortedBy { it.path }

            jsonObject {
                put("project", project)
                put("count", views.size)
                put("views", jsonArrayOf(views.map { ref ->
                    jsonObject {
                        put("view", ref.path)
                        put("sizeBytes", ref.sizeBytes)
                        put("componentCount", runCatching {
                            source.read(project, ref.path).componentCount()
                        }.getOrNull())
                    }
                }))
            }
        },
    )

    private fun getView() = Tool(
        name = "perspective_get_view",
        title = "Get a Perspective view",
        description = "Returns a view's parameters, view-level custom properties and its component " +
            "tree. Each tree entry carries the path you pass to the other perspective_ tools, plus " +
            "which properties are bound and how many events are attached. Start here before editing.",
        inputSchema = schema {
            projectArg(this)
            string("view", "View path, e.g. 'Page/Main'.", required = true)
        },
        handler = { args ->
            val project = source.resolveProject(args.optString("project"))
            val viewPath = args.requireString("view")
            val doc = source.read(project, viewPath)

            jsonObject {
                put("project", project)
                put("view", viewPath)
                put("params", doc.json().getAsJsonObjectOrNull("params") ?: JsonObject())
                put("custom", doc.json().getAsJsonObjectOrNull("custom") ?: JsonObject())
                put("props", doc.json().getAsJsonObjectOrNull("props") ?: JsonObject())
                put("propConfig", doc.json().getAsJsonObjectOrNull("propConfig") ?: JsonObject())
                put("componentCount", doc.componentCount())
                put("components", doc.tree())
            }
        },
    )

    private fun getComponent() = Tool(
        name = "perspective_get_component",
        title = "Get a Perspective component",
        description = "Returns one component in full: type, props, custom properties, bindings " +
            "(from propConfig), events, position and the names of its children.",
        inputSchema = schema {
            projectArg(this)
            string("view", "View path, e.g. 'Page/Main'.", required = true)
            string("path", "Component path, e.g. 'root/FlexContainer/Label'.", required = true)
        },
        handler = { args ->
            val project = source.resolveProject(args.optString("project"))
            val viewPath = args.requireString("view")
            val componentPath = args.requireString("path")
            val doc = source.read(project, viewPath)
            val node = doc.component(componentPath)

            jsonObject {
                put("project", project)
                put("view", viewPath)
                put("path", componentPath)
                put("type", doc.typeOf(node))
                put("name", doc.nameOf(node))
                put("props", node.getAsJsonObjectOrNull("props") ?: JsonObject())
                put("custom", node.getAsJsonObjectOrNull("custom") ?: JsonObject())
                put("position", node.getAsJsonObjectOrNull("position") ?: JsonObject())
                put("propConfig", node.getAsJsonObjectOrNull("propConfig") ?: JsonObject())
                put("events", node.getAsJsonObjectOrNull("events") ?: JsonObject())
                put("boundProperties", jsonArrayOfStrings(doc.boundPropertyKeys(node)))
                put("children", jsonArrayOfStrings(
                    node.getAsJsonArrayOrNull("children")
                        ?.mapNotNull { if (it.isJsonObject) doc.nameOf(it.asJsonObject) else null }
                        .orEmpty()
                ))
            }
        },
    )

    private fun listComponentTypes() = Tool(
        name = "perspective_list_component_types",
        title = "List Perspective component types",
        description = "Lists the component types registered on this system, which is what " +
            "perspective_add_component will accept. Filter by category or a substring of the id.",
        inputSchema = schema {
            string("category", "Only return components in this palette category.")
            string("idContains", "Only return components whose id contains this substring.")
        },
        handler = { args ->
            val category = args.optString("category")
            val idContains = args.optString("idContains")?.lowercase()

            val types = catalog.componentTypes()
            if (types.isEmpty()) {
                throw McpArgumentException(
                    "Perspective's component registry is not available on this system."
                )
            }

            val entries = JsonArray()
            types.sorted().forEach { id ->
                if (idContains != null && !id.lowercase().contains(idContains)) return@forEach
                val info = catalog.describe(id)
                if (category != null && info?.category != category) return@forEach
                entries.add(jsonObject {
                    put("id", id)
                    put("name", info?.name)
                    put("category", info?.category)
                    put("deprecated", info?.deprecated)
                })
            }

            jsonObject {
                put("count", entries.size())
                put("totalRegistered", types.size)
                put("categories", jsonArrayOfStrings(catalog.categories().sorted()))
                put("components", entries)
            }
        },
    )

    private fun getComponentType() = Tool(
        name = "perspective_get_component_type",
        title = "Get a Perspective component type",
        description = "Returns a component type's default properties, events and extension " +
            "functions. 'defaultProperties' is the shape a new instance starts with and is the " +
            "best reference for what the component accepts; perspective_validate_view then " +
            "enforces the component's real JSON schema.",
        inputSchema = schema {
            string("typeId", "Component type id, e.g. 'ia.display.label'.", required = true)
        },
        handler = { args ->
            val typeId = args.requireString("typeId")
            val info = catalog.describe(typeId)
                ?: throw McpArgumentException(
                    "'$typeId' is not a registered component type. " +
                        "Call perspective_list_component_types to see what's available."
                )

            jsonObject {
                put("id", info.id)
                put("name", info.name)
                put("category", info.category)
                put("deprecated", info.deprecated)
                put("defaultMetaName", info.defaultMetaName)
                put("defaultProperties", info.defaultProperties)
                put("childPositionDefaults", info.childPositionDefaults)
                put("events", jsonArrayOfStrings(info.eventNames))
                put("extensionFunctions", jsonArrayOfStrings(info.extensionFunctionNames))
            }
        },
    )

    private fun validateView() = Tool(
        name = "perspective_validate_view",
        title = "Validate a Perspective view",
        description = "Checks a view against the component registry and Perspective's own JSON " +
            "schemas, and reports the authoring mistakes that break views silently — bindings left " +
            "in props instead of propConfig, 'bidirectional' at the wrong nesting level, and event " +
            "scripts missing their leading tab indentation. Each finding says how to fix it.",
        inputSchema = schema {
            projectArg(this)
            string("view", "View path, e.g. 'Page/Main'.", required = true)
        },
        handler = { args ->
            val project = source.resolveProject(args.optString("project"))
            val viewPath = args.requireString("view")
            val findings = ViewValidator(catalog).validate(source.read(project, viewPath))

            jsonObject {
                put("project", project)
                put("view", viewPath)
                put("catalogAvailable", catalog.componentTypes().isNotEmpty())
                ViewValidator.toJson(findings).entrySet().forEach { (k, v) -> add(k, v) }
            }
        },
    )
}
