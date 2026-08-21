package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonObject

/**
 * What a Perspective binding transform looks like, and why it is not shaped like a binding.
 *
 * A binding is an envelope — `{"type": "tag", "config": {...}}` — and everything type-specific
 * lives under `config`. A *transform* is not. `BindingRegistryImpl.createTransform` reads `type`
 * off the transform object and hands that **same object** to the factory, which reads its keys
 * straight off it: `ExpressionTransformFactory` calls `getAsJsonPrimitive("expression")` on it,
 * `FormatTransformFactory` reads `formatType`/`formatValue`/`locale`/`timezone`,
 * `MapTransformConfig` reads `mappings`/`inputType`/`outputType`/`fallback`, and `ScriptTransform`
 * reads `code`. There is no `config` wrapper for a transform anywhere in Perspective.
 *
 * Written by analogy with a binding, `{"type": "expression", "config": {"expression": "..."}}`
 * parses, saves and validates clean, and then does nothing — the transform passes its input
 * straight through while the Designer shows an empty expression. That silent success is issue #6,
 * and the table below is what makes it loud.
 *
 * The keys are read off the factories' bytecode rather than guessed, the same evidence standard
 * [BindingSchemaPatches] holds itself to. This covers `script`, which is the one registered
 * transform type Perspective ships no JSON Schema for; the other three are additionally validated
 * against `schemas/transform-*.json` through [ComponentCatalog.validateTransform].
 */
object TransformShapes {

    /** Keys each transform type reads and cannot work without, by transform type id. */
    val REQUIRED_KEYS: Map<String, List<String>> = mapOf(
        "expression" to listOf("expression"),
        "format" to listOf("formatType", "formatValue"),
        "map" to listOf("mappings", "inputType", "outputType"),
        "script" to listOf("code"),
    )

    /** A correct, minimal transform of each type, for error messages. */
    private val EXAMPLES: Map<String, String> = mapOf(
        "expression" to """{"type": "expression", "expression": "{value} = 8"}""",
        "format" to """{"type": "format", "formatType": "numeric", "formatValue": "0.00"}""",
        "map" to """{"type": "map", "inputType": "primitive", "outputType": "primitive", """ +
            """"mappings": [{"input": 1, "output": "On"}]}""",
        "script" to """{"type": "script", "code": "def transform(self, value, quality, timestamp):"}""",
    )

    /** Transform types this object knows the shape of. */
    fun knownTypes(): Set<String> = REQUIRED_KEYS.keys

    fun typeOf(transform: JsonObject): String? =
        transform.get("type")?.takeIf { it.isJsonPrimitive }?.asString

    /**
     * Keys [transform]'s own type requires that it does not carry. Empty for a transform whose
     * type is absent or unknown to us — an unrecognised type is reported separately rather than
     * guessed at.
     */
    fun missingKeys(transform: JsonObject): List<String> {
        val required = REQUIRED_KEYS[typeOf(transform)] ?: return emptyList()
        return required.filter { !transform.has(it) }
    }

    /**
     * The correction to offer. Names the `config` wrapper explicitly when the caller used one,
     * because that is the mistake worth un-learning rather than a missing key in isolation.
     */
    fun fixFor(transform: JsonObject): String {
        val type = typeOf(transform)
        val lead = if (transform.get("config")?.isJsonObject == true) {
            "A transform has no 'config' wrapper — unlike a binding, its keys are inline " +
                "siblings of 'type'."
        } else {
            "Transform keys are inline siblings of 'type'."
        }
        return EXAMPLES[type]?.let { "$lead Write it as $it" } ?: lead
    }
}
