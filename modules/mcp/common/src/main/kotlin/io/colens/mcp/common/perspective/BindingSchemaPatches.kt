package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonElement
import com.inductiveautomation.ignition.common.gson.JsonObject

/**
 * Config keys Perspective's own binding readers accept but its shipped schema forgets to declare.
 *
 * `schemas/binding-tag.json` sets `"additionalProperties": false` and declares four keys, while
 * `TagBindingConfig.fromJson` reads seven — it also takes `fallbackDelay`, `publishInitial` and
 * `coalesce`. The Designer writes `fallbackDelay` on indirect tag bindings, so validating a
 * Designer-authored view against the shipped schema reports the platform's own output as invalid.
 * The two files are byte-identical in perspective-common 2.1.54 and 3.3.8, so this is not version
 * skew that waiting fixes.
 *
 * We restore the missing *declarations* rather than relaxing `additionalProperties`, so a
 * misspelled key is still caught. This is the only place the module overrides a shipped schema,
 * and every entry below is read off `TagBindingConfig.fromJson`'s call sites rather than guessed.
 *
 * Kept free of Perspective imports so the unit tests can load it — `:common:test` has Ignition on
 * the classpath but not Perspective.
 */
internal object BindingSchemaPatches {

    /**
     * `fallbackDelay` is read with `JsonUtilities.readNumber`, which coerces a numeric *string* as
     * happily as a number, so declaring it `"number"` would reject a config Perspective honours —
     * the same false-positive class this whole patch exists to remove. The two booleans are read
     * with `readBoolean`, which accepts only a real boolean and silently falls back to its default
     * otherwise, so constraining them keeps a value that would be dropped on the floor visible.
     */
    private val MISSING_PROPERTIES: Map<String, Map<String, JsonObject>> = mapOf(
        "tag" to mapOf(
            "fallbackDelay" to declaration(
                listOf("number", "string"),
                "Seconds an unresolved indirect tag path waits before falling back. " +
                    "Read by TagBindingConfig.fromJson; defaults to 2.5.",
            ),
            "publishInitial" to declaration(
                listOf("boolean"),
                "Read by TagBindingConfig.fromJson; defaults to false.",
            ),
            "coalesce" to declaration(
                listOf("boolean"),
                "Read by TagBindingConfig.fromJson; defaults to false.",
            ),
        ),
    )

    /** Binding types this object knows a gap for, for logging. */
    fun patchedTypes(): Set<String> = MISSING_PROPERTIES.keys

    /**
     * [schema] with any declaration Perspective omitted for [bindingType] added.
     *
     * Returns the input unchanged — the same instance — unless the schema really is a closed
     * object missing one of them. An existing declaration is never overwritten whatever its shape,
     * so a future Perspective that fixes its own schema wins and this becomes a no-op. Idempotent.
     */
    fun patch(bindingType: String, schema: JsonElement): JsonElement {
        val missing = MISSING_PROPERTIES[bindingType] ?: return schema
        if (!schema.isJsonObject) return schema

        val root = schema.asJsonObject
        // Only a schema that actually closes the door needs help; if Perspective ever opens it,
        // there is nothing to fix.
        val closed = root.get("additionalProperties")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean == false
        if (!closed) return schema

        val properties = root.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return schema

        val absent = missing.filterKeys { !properties.has(it) }
        if (absent.isEmpty()) return schema

        val patched = root.deepCopy()
        val patchedProperties = patched.getAsJsonObject("properties")
        absent.forEach { (name, declaration) -> patchedProperties.add(name, declaration.deepCopy()) }
        return patched
    }

    private fun declaration(types: List<String>, description: String): JsonObject = JsonObject().apply {
        if (types.size == 1) {
            addProperty("type", types.single())
        } else {
            add("type", JsonArray().apply { types.forEach { add(it) } })
        }
        addProperty("description", description)
    }
}
