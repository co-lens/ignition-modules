package io.colens.mcp.designer.tools

import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonElement
import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.ignition.designer.model.DesignerContext
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.Tool
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.optInt
import io.colens.mcp.common.optString
import io.colens.mcp.common.perspective.ComponentCatalog
import io.colens.mcp.common.perspective.PerspectiveComponentCatalog
import io.colens.mcp.common.perspective.PerspectiveReadTools
import io.colens.mcp.common.perspective.Severity
import io.colens.mcp.common.perspective.ViewDocument
import io.colens.mcp.common.perspective.ViewSource
import io.colens.mcp.common.perspective.ViewValidator
import io.colens.mcp.common.perspective.getAsJsonObjectOrNull
import io.colens.mcp.common.put
import io.colens.mcp.common.requireString
import io.colens.mcp.common.schema
import io.colens.mcp.designer.perspective.DesignerViewSource

/**
 * Surgical Perspective editing, staged in the open Designer.
 *
 * Every tool follows the same three steps: read the view, apply one change through
 * [ViewDocument], then validate and refuse to stage anything that would leave the view broken.
 * That last step is what earns these tools their surface area — the model addresses components by
 * path and never handles raw `view.json`, so the failure mode is a rejected call rather than a
 * corrupted view.
 */
class PerspectiveEditTools(private val context: DesignerContext) {

    private val catalog: ComponentCatalog = PerspectiveComponentCatalog { designerRegistry() }
    private val source: ViewSource = DesignerViewSource(context)
    private val validator = ViewValidator(catalog)

    /**
     * Perspective's Designer-side registry. `DesignerComponentRegistry` extends the common
     * `ComponentRegistry`, so everything downstream is scope-agnostic.
     */
    private fun designerRegistry(): com.inductiveautomation.perspective.common.api.ComponentRegistry? = try {
        (context.getModule("com.inductiveautomation.perspective")
            as? com.inductiveautomation.perspective.designer.DesignerHook)
            ?.designerComponentRegistry
    } catch (t: Throwable) {
        null
    }

    fun tools(): List<Tool> =
        PerspectiveReadTools(source, catalog).tools() +
            listOf(
                createView(),
                addComponent(),
                updateComponent(),
                moveComponent(),
                deleteComponent(),
                setCustomProperty(),
                deleteCustomProperty(),
                setBinding(),
                deleteBinding(),
                setEvent(),
                deleteEvent(),
                setChangeScript(),
                setViewParam(),
            )

    // -----------------------------------------------------------------------
    // Shared edit plumbing
    // -----------------------------------------------------------------------

    /**
     * Reads the view, applies [edit], validates, and stages it. Refuses to write when the edit
     * would introduce errors, returning the findings so the caller can correct the call.
     */
    private fun edit(
        args: JsonObject,
        extra: JsonObject.() -> Unit = {},
        edit: (ViewDocument) -> Unit,
    ): JsonObject {
        val project = source.resolveProject(args.optString("project"))
        val viewPath = args.requireString("view")

        val doc = source.read(project, viewPath)
        edit(doc)

        val findings = validator.validate(doc)
        val errors = findings.filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) {
            throw McpArgumentException(
                "Refusing to write: the result would be invalid. " +
                    errors.joinToString("; ") { "${it.path}: ${it.message}${it.fix?.let { f -> " ($f)" } ?: ""}" }
            )
        }

        val outcome = source.write(project, viewPath, doc)
        return jsonObject {
            put("project", project)
            put("view", viewPath)
            put("committed", outcome.committed)
            put("note", outcome.note)
            if (findings.isNotEmpty()) {
                put("warnings", jsonArrayOf(findings.map { it.toJson() }))
            }
            extra()
        }
    }

    private fun viewArgs(builder: io.colens.mcp.common.SchemaBuilder) {
        builder.string("project", "Ignored — the Designer operates on the open project.")
        builder.string("view", "View path, e.g. 'Page/Main'.", required = true)
    }

    private fun objectArg(args: JsonObject, key: String, required: Boolean = false): JsonObject? {
        val element = args.get(key)
        if (element == null || element.isJsonNull) {
            if (required) throw McpArgumentException("Missing required argument '$key'")
            return null
        }
        if (!element.isJsonObject) throw McpArgumentException("Argument '$key' must be an object")
        return element.asJsonObject
    }

    /** Merges [patch] into [target], recursing into nested objects rather than replacing them. */
    private fun merge(target: JsonObject, patch: JsonObject) {
        patch.entrySet().forEach { (key, value) ->
            val existing = target.get(key)
            if (value.isJsonObject && existing != null && existing.isJsonObject) {
                merge(existing.asJsonObject, value.asJsonObject)
            } else {
                target.add(key, value)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------

    private fun createView() = Tool(
        name = "perspective_create_view",
        title = "Create a Perspective view",
        description = "Creates a new view with an empty root container. Defaults for the container " +
            "come from the component registry, so the view opens correctly in the Designer.",
        inputSchema = schema {
            string("project", "Ignored — the Designer operates on the open project.")
            string("view", "View path to create, e.g. 'Page/Main'.", required = true)
            string("rootType", "Root container type.", default = "ia.container.flex")
            integer("width", "Default width.", default = 800)
            integer("height", "Default height.", default = 600)
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val project = source.resolveProject(args.optString("project"))
            val viewPath = args.requireString("view")
            val rootType = args.optString("rootType") ?: "ia.container.flex"

            if (catalog.componentTypes().isNotEmpty() && rootType !in catalog.componentTypes()) {
                throw McpArgumentException("'$rootType' is not a registered component type.")
            }
            runCatching { source.read(project, viewPath) }.getOrNull()?.let {
                throw McpArgumentException("View '$viewPath' already exists.")
            }

            val info = catalog.describe(rootType)
            val root = jsonObject {
                put("type", rootType)
                put("meta", jsonObject { put("name", "root") })
                put("props", info?.defaultProperties?.deepCopy() ?: JsonObject())
                put("children", JsonArray())
            }

            val doc = ViewDocument.create(root)
            doc.props("view").add("defaultSize", jsonObject {
                put("width", args.optInt("width", 800))
                put("height", args.optInt("height", 600))
            })

            val outcome = source.write(project, viewPath, doc)
            jsonObject {
                put("project", project)
                put("view", viewPath)
                put("rootType", rootType)
                put("created", outcome.created)
                put("committed", outcome.committed)
                put("note", outcome.note)
            }
        },
    )

    // -----------------------------------------------------------------------
    // Components
    // -----------------------------------------------------------------------

    private fun addComponent() = Tool(
        name = "perspective_add_component",
        title = "Add a Perspective component",
        description = "Adds a component to a container. Default properties and the child position " +
            "shape both come from the registry — position depends on the PARENT container type, so " +
            "letting this tool supply it is what makes the component lay out correctly. The name is " +
            "made unique among its siblings automatically.",
        inputSchema = schema {
            viewArgs(this)
            string("parentPath", "Container to add to, e.g. 'root' or 'root/FlexContainer'.", required = true)
            string("type", "Component type id, e.g. 'ia.display.label'.", required = true)
            string("name", "Component name. Defaults to the type's default, made unique.")
            raw("props", jsonObject {
                put("type", "object")
                put("description", "Property values to set, merged over the type's defaults.")
            })
            raw("position", jsonObject {
                put("type", "object")
                put("description", "Position overrides, merged over the parent's child defaults.")
            })
            integer("index", "Insert at this child index. Appends when omitted.")
        },
        readOnly = false,
        destructive = false,
        handler = { args ->
            val parentPath = args.requireString("parentPath")
            val type = args.requireString("type")
            var addedAt = ""

            edit(args, extra = { put("path", addedAt); put("type", type) }) { doc ->
                if (catalog.componentTypes().isNotEmpty() && type !in catalog.componentTypes()) {
                    throw McpArgumentException(
                        "'$type' is not a registered component type. " +
                            "Call perspective_list_component_types to see what's available."
                    )
                }

                val parent = doc.component(parentPath)
                val parentType = doc.typeOf(parent)
                val info = catalog.describe(type)
                val parentInfo = parentType?.let { catalog.describe(it) }

                val node = jsonObject {
                    put("type", type)
                    put("meta", jsonObject {
                        put("name", args.optString("name") ?: info?.defaultMetaName ?: type.substringAfterLast('.'))
                    })
                    put("props", info?.defaultProperties?.deepCopy() ?: JsonObject())
                    put("position", parentInfo?.childPositionDefaults?.deepCopy() ?: JsonObject())
                }

                objectArg(args, "props")?.let { merge(node.getAsJsonObjectOrNull("props")!!, it) }
                objectArg(args, "position")?.let { merge(node.getAsJsonObjectOrNull("position")!!, it) }

                addedAt = doc.addComponent(parentPath, node, args.get("index")?.takeIf { !it.isJsonNull }?.asInt)
            }
        },
    )

    private fun updateComponent() = Tool(
        name = "perspective_update_component",
        title = "Update a Perspective component",
        description = "Merges values into a component's props and/or position, and optionally " +
            "renames it. Merging is recursive, so nested objects like 'style' keep the keys you " +
            "don't mention.",
        inputSchema = schema {
            viewArgs(this)
            string("path", "Component path, e.g. 'root/Label'.", required = true)
            raw("props", jsonObject { put("type", "object"); put("description", "Property values to merge in.") })
            raw("position", jsonObject { put("type", "object"); put("description", "Position values to merge in.") })
            string("name", "Rename the component to this.")
        },
        readOnly = false,
        destructive = false,
        handler = { args ->
            val path = args.requireString("path")
            var finalPath = path

            edit(args, extra = { put("path", finalPath) }) { doc ->
                objectArg(args, "props")?.let { merge(doc.props(path), it) }
                objectArg(args, "position")?.let { merge(doc.position(path), it) }
                args.optString("name")?.let { finalPath = doc.renameComponent(path, it) }
            }
        },
    )

    private fun moveComponent() = Tool(
        name = "perspective_move_component",
        title = "Move a Perspective component",
        description = "Reparents or reorders a component. Its props, bindings and events move with it.",
        inputSchema = schema {
            viewArgs(this)
            string("path", "Component to move.", required = true)
            string("newParentPath", "Container to move it into.", required = true)
            integer("index", "Insert at this child index. Appends when omitted.")
        },
        readOnly = false,
        destructive = false,
        handler = { args ->
            val path = args.requireString("path")
            val newParent = args.requireString("newParentPath")
            var finalPath = path

            edit(args, extra = { put("path", finalPath) }) { doc ->
                finalPath = doc.moveComponent(path, newParent, args.get("index")?.takeIf { !it.isJsonNull }?.asInt)
            }
        },
    )

    private fun deleteComponent() = Tool(
        name = "perspective_delete_component",
        title = "Delete a Perspective component",
        description = "Deletes a component and everything under it.",
        inputSchema = schema {
            viewArgs(this)
            string("path", "Component to delete.", required = true)
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val path = args.requireString("path")
            edit(args, extra = { put("deleted", path) }) { doc -> doc.removeComponent(path) }
        },
    )

    // -----------------------------------------------------------------------
    // Custom properties
    // -----------------------------------------------------------------------

    private fun setCustomProperty() = Tool(
        name = "perspective_set_custom_property",
        title = "Set a custom property",
        description = "Adds or updates a custom property on a component, or on the view itself when " +
            "path is 'view'. Custom properties are where you put values that bindings and scripts " +
            "share.",
        inputSchema = schema {
            viewArgs(this)
            string("path", "Component path, or 'view' for a view-level property.", required = true)
            string("name", "Property name.", required = true)
            raw("value", jsonObject { put("description", "Property value: any JSON.") }, required = true)
            boolean("persistent", "Whether the value is saved with the view.", default = true)
        },
        readOnly = false,
        destructive = false,
        handler = { args ->
            val path = args.requireString("path")
            val name = args.requireString("name")

            edit(args, extra = { put("property", "custom.$name") }) { doc ->
                val value: JsonElement = args.get("value") ?: throw McpArgumentException("Missing 'value'")
                doc.custom(path).add(name, value)

                // Persistent is Perspective's default, so only record the non-default case.
                val persistent = args.get("persistent")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                if (!persistent) {
                    doc.propConfigEntry(path, "custom.$name").addProperty("persistent", false)
                }
            }
        },
    )

    private fun deleteCustomProperty() = Tool(
        name = "perspective_delete_custom_property",
        title = "Delete a custom property",
        description = "Removes a custom property and any binding attached to it.",
        inputSchema = schema {
            viewArgs(this)
            string("path", "Component path, or 'view' for a view-level property.", required = true)
            string("name", "Property name.", required = true)
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val path = args.requireString("path")
            val name = args.requireString("name")

            edit(args, extra = { put("deleted", "custom.$name") }) { doc ->
                if (doc.custom(path).remove(name) == null) {
                    throw McpArgumentException("'$path' has no custom property '$name'")
                }
                doc.removePropConfigEntry(path, "custom.$name")
            }
        },
    )

    // -----------------------------------------------------------------------
    // Bindings
    // -----------------------------------------------------------------------

    private fun setBinding() = Tool(
        name = "perspective_set_binding",
        title = "Set a binding",
        description = "Binds a property. The binding is written to propConfig (never into props, " +
            "which is where hand-written views usually go wrong) and its config is validated " +
            "against Perspective's schema for that binding type before anything is staged. " +
            "For a bidirectional tag binding put 'bidirectional': true inside config, not beside it.",
        inputSchema = schema {
            viewArgs(this)
            string("path", "Component path, or 'view' for a view-level property.", required = true)
            string("property", "Scoped property key, e.g. 'props.text' or 'custom.value'.", required = true)
            string("type", "Binding type, e.g. 'tag', 'expr', 'property', 'query'.", required = true)
            raw("config", jsonObject {
                put("type", "object")
                put("description", "Binding config, e.g. {\"tagPath\": \"[default]A\"} for a tag binding.")
            }, required = true)
            array("transforms", "Optional transforms applied in order.", items = jsonObject {
                put("type", "object")
                put("description", "A transform, e.g. {\"type\": \"format\", \"config\": {...}}.")
            })
            boolean("enabled", "Whether the binding is active.", default = true)
        },
        readOnly = false,
        destructive = false,
        handler = { args ->
            val path = args.requireString("path")
            val property = args.requireString("property")
            val type = args.requireString("type")

            edit(args, extra = { put("property", property); put("bindingType", type) }) { doc ->
                if (!property.contains('.')) {
                    throw McpArgumentException(
                        "'$property' needs a scope prefix, e.g. 'props.$property' or 'custom.$property'."
                    )
                }
                val config = objectArg(args, "config", required = true)!!

                catalog.validateBindingConfig(type, config)?.let { violations ->
                    if (violations.isNotEmpty()) {
                        throw McpArgumentException(
                            "Invalid config for a '$type' binding: " +
                                violations.joinToString("; ") { it.message }
                        )
                    }
                }

                val binding = jsonObject {
                    put("type", type)
                    put("config", config)
                    args.get("transforms")?.takeIf { it.isJsonArray }?.let { put("transforms", it) }
                    args.get("enabled")?.takeIf { !it.isJsonNull && !it.asBoolean }?.let {
                        put("enabled", false)
                    }
                }

                doc.propConfigEntry(path, property).add("binding", binding)
            }
        },
    )

    private fun deleteBinding() = Tool(
        name = "perspective_delete_binding",
        title = "Delete a binding",
        description = "Removes a property's binding, leaving the property and its current value.",
        inputSchema = schema {
            viewArgs(this)
            string("path", "Component path, or 'view'.", required = true)
            string("property", "Scoped property key, e.g. 'props.text'.", required = true)
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val path = args.requireString("path")
            val property = args.requireString("property")

            edit(args, extra = { put("property", property) }) { doc ->
                val entry = doc.propConfigEntryOrNull(path, property)
                    ?: throw McpArgumentException("'$path' has no configuration for '$property'")
                if (entry.remove("binding") == null) {
                    throw McpArgumentException("'$property' on '$path' has no binding")
                }
                doc.prunePropConfigEntry(path, property)
            }
        },
    )

    // -----------------------------------------------------------------------
    // Events and scripts
    // -----------------------------------------------------------------------

    private fun setEvent() = Tool(
        name = "perspective_set_event",
        title = "Set an event script",
        description = "Attaches a script to a component event. Write the script body without " +
            "indentation and it is indented for you — Perspective event scripts are function " +
            "bodies, so an unindented line is a syntax error at runtime, and this tool makes that " +
            "mistake impossible.",
        inputSchema = schema {
            viewArgs(this)
            string("path", "Component path.", required = true)
            string("eventGroup", "Event group, e.g. 'dom' or 'component'.", default = "dom")
            string("eventName", "Event name, e.g. 'onClick'.", required = true)
            string("script", "Python body. Leading tabs are added if missing.", required = true)
        },
        readOnly = false,
        destructive = false,
        handler = { args ->
            val path = args.requireString("path")
            val group = args.optString("eventGroup") ?: "dom"
            val name = args.requireString("eventName")
            val script = indentScript(args.requireString("script"))

            edit(args, extra = { put("event", "$group.$name") }) { doc ->
                doc.eventGroup(path, group).add(name, jsonObject {
                    put("type", "script")
                    put("scope", "G")
                    put("config", jsonObject { put("script", script) })
                })
            }
        },
    )

    private fun deleteEvent() = Tool(
        name = "perspective_delete_event",
        title = "Delete an event script",
        description = "Removes a script from a component event.",
        inputSchema = schema {
            viewArgs(this)
            string("path", "Component path.", required = true)
            string("eventGroup", "Event group.", default = "dom")
            string("eventName", "Event name.", required = true)
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val path = args.requireString("path")
            val group = args.optString("eventGroup") ?: "dom"
            val name = args.requireString("eventName")

            edit(args, extra = { put("deleted", "$group.$name") }) { doc ->
                val groupObject = doc.eventGroupOrNull(path, group)
                    ?: throw McpArgumentException("'$path' has no '$group' events")
                if (groupObject.remove(name) == null) {
                    throw McpArgumentException("'$path' has no '$group.$name' event")
                }
            }
        },
    )

    private fun setChangeScript() = Tool(
        name = "perspective_set_change_script",
        title = "Set a property-change script",
        description = "Runs a script whenever a property changes. Like event scripts the body is " +
            "indented for you. The script receives 'currentValue', 'previousValue' and 'origin'.",
        inputSchema = schema {
            viewArgs(this)
            string("path", "Component path, or 'view'.", required = true)
            string("property", "Scoped property key, e.g. 'custom.value'.", required = true)
            string("script", "Python body. Leading tabs are added if missing.", required = true)
        },
        readOnly = false,
        destructive = false,
        handler = { args ->
            val path = args.requireString("path")
            val property = args.requireString("property")
            val script = indentScript(args.requireString("script"))

            edit(args, extra = { put("property", property) }) { doc ->
                doc.propConfigEntry(path, property).add("onChange", jsonObject {
                    put("script", script)
                    put("enabled", true)
                })
            }
        },
    )

    private fun setViewParam() = Tool(
        name = "perspective_set_view_param",
        title = "Set a view parameter",
        description = "Declares a view parameter and its direction. Input parameters are passed in " +
            "when the view is embedded; output parameters are read back out.",
        inputSchema = schema {
            viewArgs(this)
            string("name", "Parameter name.", required = true)
            raw("value", jsonObject { put("description", "Default value: any JSON.") }, required = true)
            enumString(
                name = "direction",
                description = "Parameter direction.",
                values = listOf("input", "output", "inout"),
                default = "input",
            )
        },
        readOnly = false,
        destructive = false,
        handler = { args ->
            val name = args.requireString("name")
            val direction = args.optString("direction") ?: "input"

            edit(args, extra = { put("param", name); put("direction", direction) }) { doc ->
                val value: JsonElement = args.get("value") ?: throw McpArgumentException("Missing 'value'")
                doc.params().add(name, value)

                doc.propConfigEntry("view", "params.$name").apply {
                    addProperty("paramDirection", direction)
                    addProperty("persistent", true)
                }
            }
        },
    )

    /**
     * Perspective scripts are function bodies: every line must be indented or the script fails to
     * compile. Rather than reject unindented input we fix it, which removes the single most common
     * authoring mistake at the source.
     */
    private fun indentScript(script: String): String {
        val lines = script.replace("\r\n", "\n").split('\n')
        if (lines.all { it.isEmpty() || it.startsWith("\t") }) return script
        return lines.joinToString("\n") { if (it.isEmpty()) it else "\t$it" }
    }
}
