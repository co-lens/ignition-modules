package io.colens.mcp.common

import com.inductiveautomation.ignition.common.gson.JsonElement
import com.inductiveautomation.ignition.common.gson.JsonObject

/**
 * One MCP tool. Deliberately a plain class with a hand-written JSON Schema — no annotations,
 * no reflection, no codegen. Adding a tool is one constructor call.
 *
 * The handler receives the call's `arguments` object (never null; an empty object when the
 * client sent none) and returns the value to put in `structuredContent`. Throwing is fine:
 * [McpServer] converts it into an `isError` tool result rather than a protocol error, which is
 * what lets the model see and react to the failure.
 */
class Tool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    /** False for anything that changes gateway or project state. Drives `readOnlyHint`. */
    val readOnly: Boolean = true,
    /** True for tools whose effect is hard to undo. Drives `destructiveHint`. */
    val destructive: Boolean = false,
    val handler: (JsonObject) -> JsonElement,
) {
    fun toJson(): JsonObject = jsonObject {
        put("name", name)
        put("title", title)
        put("description", description)
        put("inputSchema", inputSchema)
        put("annotations", jsonObject {
            put("title", title)
            put("readOnlyHint", readOnly)
            put("destructiveHint", destructive)
        })
    }
}

/**
 * Builds a JSON Schema object for a tool's arguments.
 *
 * ```
 * schema {
 *     string("path", "Tag path to browse", required = true)
 *     boolean("recursive", "Recurse into folders", default = false)
 * }
 * ```
 */
fun schema(build: SchemaBuilder.() -> Unit = {}): JsonObject =
    SchemaBuilder().apply(build).build()

class SchemaBuilder {
    private val properties = JsonObject()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false, default: String? = null) =
        property(name, "string", description, required) { if (default != null) put("default", default) }

    fun integer(name: String, description: String, required: Boolean = false, default: Int? = null) =
        property(name, "integer", description, required) { if (default != null) put("default", default) }

    fun boolean(name: String, description: String, required: Boolean = false, default: Boolean? = null) =
        property(name, "boolean", description, required) { if (default != null) put("default", default) }

    fun stringArray(name: String, description: String, required: Boolean = false) =
        property(name, "array", description, required) {
            put("items", jsonObject { put("type", "string") })
        }

    fun array(name: String, description: String, items: JsonObject, required: Boolean = false) =
        property(name, "array", description, required) { put("items", items) }

    fun enumString(
        name: String,
        description: String,
        values: List<String>,
        required: Boolean = false,
        default: String? = null,
    ) = property(name, "string", description, required) {
        put("enum", jsonArrayOfStrings(values))
        if (default != null) put("default", default)
    }

    fun raw(name: String, definition: JsonObject, required: Boolean = false) {
        properties.add(name, definition)
        if (required) this.required += name
    }

    private fun property(
        name: String,
        type: String,
        description: String,
        isRequired: Boolean,
        extra: JsonObject.() -> Unit = {},
    ) {
        properties.add(name, jsonObject {
            put("type", type)
            put("description", description)
            extra()
        })
        if (isRequired) required += name
    }

    fun build(): JsonObject = jsonObject {
        put("type", "object")
        put("properties", properties)
        put("required", jsonArrayOfStrings(required))
        // Reject unknown keys so a hallucinated argument surfaces as a clear error rather than
        // being silently dropped.
        put("additionalProperties", false)
    }
}
