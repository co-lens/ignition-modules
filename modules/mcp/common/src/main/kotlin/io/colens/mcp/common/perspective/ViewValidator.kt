package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonObject
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.put

enum class Severity { ERROR, WARNING }

/**
 * One problem found in a view.
 *
 * [fix] matters as much as [message]: these findings are read by a model that is about to try
 * again, so saying what to do instead turns a failed edit into a corrected one.
 */
data class Finding(
    val path: String,
    val code: String,
    val severity: Severity,
    val message: String,
    val fix: String? = null,
) {
    fun toJson(): JsonObject = jsonObject {
        put("path", path)
        put("code", code)
        put("severity", severity.name.lowercase())
        put("message", message)
        put("fix", fix)
    }
}

/**
 * Static analysis of a Perspective view.
 *
 * Two families of check. The schema-driven ones need a [ComponentCatalog] and defer to
 * Perspective's own JSON Schemas — we don't reimplement what the platform already validates. The
 * structural ones need nothing and catch the mistakes that are specific to *writing* view.json by
 * hand or by model: bindings put in the wrong place, `bidirectional` at the wrong nesting level,
 * and event scripts without their leading tab.
 *
 * With [NoComponentCatalog] the structural checks still run, so validation degrades on a gateway
 * without Perspective rather than failing.
 */
class ViewValidator(private val catalog: ComponentCatalog = NoComponentCatalog) {

    fun validate(view: ViewDocument): List<Finding> {
        val findings = mutableListOf<Finding>()

        val root = view.json().getAsJsonObjectOrNull(ViewDocument.ROOT)
        if (root == null) {
            findings += Finding(
                path = "",
                code = "missing_root",
                severity = Severity.ERROR,
                message = "View has no 'root' component.",
                fix = "Every view needs a 'root' object holding a container component.",
            )
            return findings
        }

        val declaredCustom = collectDeclaredCustomPaths(view)

        view.walk { node, path ->
            checkStructure(node, path, findings)
            checkComponentType(view, node, path, findings)
            checkInlineBindings(node, path, findings)
            checkPropConfig(view, node, path, findings)
            checkEvents(node, path, findings)
            checkDuplicateChildNames(view, node, path, findings)
            checkCustomReferences(node, path, declaredCustom, findings)
        }

        return findings
    }

    // -----------------------------------------------------------------------
    // Structural
    // -----------------------------------------------------------------------

    private fun checkStructure(node: JsonObject, path: String, findings: MutableList<Finding>) {
        if (!node.has("type")) {
            findings += Finding(
                path, "missing_type", Severity.ERROR,
                "Component has no 'type'.",
                "Add a registered component type id, e.g. \"type\": \"ia.display.label\".",
            )
        }
        if (node.has("children") && !node.get("children").isJsonArray) {
            findings += Finding(
                path, "invalid_children", Severity.ERROR,
                "'children' must be an array.",
            )
        }
        node.get("meta")?.let {
            if (!it.isJsonObject) {
                findings += Finding(path, "invalid_meta", Severity.ERROR, "'meta' must be an object.")
            }
        }
        if (path != ViewDocument.ROOT && node.getAsJsonObjectOrNull("meta")?.has("name") != true) {
            findings += Finding(
                path, "missing_name", Severity.WARNING,
                "Component has no 'meta.name', so it can only be addressed by index.",
                "Give it a name: \"meta\": { \"name\": \"MyLabel\" }.",
            )
        }
    }

    private fun checkComponentType(
        view: ViewDocument,
        node: JsonObject,
        path: String,
        findings: MutableList<Finding>,
    ) {
        val type = view.typeOf(node) ?: return
        val known = catalog.componentTypes()
        if (known.isEmpty()) return // No catalog: can't tell, don't guess.

        if (type !in known) {
            findings += Finding(
                path, "unknown_component_type", Severity.ERROR,
                "'$type' is not a registered Perspective component type.",
                "Call perspective_list_component_types to see what's available on this gateway.",
            )
            return
        }

        val props = node.getAsJsonObjectOrNull("props") ?: return
        catalog.validateProps(type, props).forEach { violation ->
            val where = violation.path?.removePrefix("$")?.trimStart('.')?.takeIf { it.isNotEmpty() }
            findings += Finding(
                path, "invalid_prop", Severity.ERROR,
                if (where != null) "$where: ${violation.message}" else violation.message,
                "Call perspective_get_component_type for '$type' to see the accepted properties.",
            )
        }
    }

    private fun checkDuplicateChildNames(
        view: ViewDocument,
        node: JsonObject,
        path: String,
        findings: MutableList<Finding>,
    ) {
        val children = node.getAsJsonArrayOrNull("children") ?: return
        val seen = mutableSetOf<String>()
        children.forEach { child ->
            if (!child.isJsonObject) return@forEach
            val name = view.nameOf(child.asJsonObject) ?: return@forEach
            if (!seen.add(name)) {
                findings += Finding(
                    "$path/$name", "duplicate_name", Severity.ERROR,
                    "Two children of '$path' are both named '$name'.",
                    "Names must be unique among siblings; paths address components by name.",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // The mistakes that authoring tools actually make
    // -----------------------------------------------------------------------

    /**
     * A binding object sitting inside `props` instead of `propConfig`. Perspective silently
     * treats it as literal data, so the component renders an object instead of a value and
     * nothing reports an error — which is exactly why it's worth flagging.
     */
    private fun checkInlineBindings(node: JsonObject, path: String, findings: MutableList<Finding>) {
        val props = node.getAsJsonObjectOrNull("props") ?: return
        findInlineBindings(props, "props", path, findings)

        node.getAsJsonObjectOrNull("custom")?.let { findInlineBindings(it, "custom", path, findings) }
    }

    private fun findInlineBindings(
        container: JsonObject,
        prefix: String,
        path: String,
        findings: MutableList<Finding>,
    ) {
        container.entrySet().forEach { (key, value) ->
            if (!value.isJsonObject) return@forEach
            val obj = value.asJsonObject
            val binding = obj.getAsJsonObjectOrNull("binding")
            if (binding != null && binding.has("type")) {
                findings += Finding(
                    path, "inline_binding", Severity.ERROR,
                    "'$prefix.$key' contains a binding object. Bindings do not live in " +
                        "'$prefix' — Perspective will treat this as literal data.",
                    "Move it to propConfig: \"propConfig\": { \"$prefix.$key\": " +
                        "{ \"binding\": { ... } } }, and leave a plain default value in '$prefix'.",
                )
            } else {
                findInlineBindings(obj, "$prefix.$key", path, findings)
            }
        }
    }

    private fun checkPropConfig(
        view: ViewDocument,
        node: JsonObject,
        path: String,
        findings: MutableList<Finding>,
    ) {
        val propConfig = node.get("propConfig") ?: return
        if (!propConfig.isJsonObject) {
            findings += Finding(path, "invalid_propConfig", Severity.ERROR, "'propConfig' must be an object.")
            return
        }

        propConfig.asJsonObject.entrySet().forEach { (key, entry) ->
            if (!entry.isJsonObject) {
                findings += Finding(
                    path, "invalid_propConfig_entry", Severity.ERROR,
                    "propConfig entry '$key' must be an object.",
                )
                return@forEach
            }
            val config = entry.asJsonObject

            if (!key.contains('.')) {
                findings += Finding(
                    path, "invalid_property_key", Severity.WARNING,
                    "propConfig key '$key' has no scope prefix.",
                    "Use a scoped key such as 'props.text', 'custom.myValue' or 'params.id'.",
                )
            }

            val binding = config.getAsJsonObjectOrNull("binding") ?: return@forEach
            checkBinding(binding, key, path, findings)
        }
    }

    private fun checkBinding(
        binding: JsonObject,
        propertyKey: String,
        path: String,
        findings: MutableList<Finding>,
    ) {
        // `bidirectional` belongs to the tag binding's config, not to the binding envelope.
        // Placed one level too high it is silently ignored and writes never happen.
        if (binding.has("bidirectional")) {
            findings += Finding(
                path, "bidirectional_misplaced", Severity.ERROR,
                "Binding on '$propertyKey' sets 'bidirectional' on the binding itself.",
                "Move it inside config: { \"binding\": { \"type\": \"tag\", " +
                    "\"config\": { \"tagPath\": \"...\", \"bidirectional\": true } } }",
            )
        }

        val type = binding.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        if (type == null) {
            findings += Finding(
                path, "binding_missing_type", Severity.ERROR,
                "Binding on '$propertyKey' has no 'type'.",
                "Set a binding type, e.g. \"type\": \"tag\" or \"expression\".",
            )
            return
        }

        val config = binding.getAsJsonObjectOrNull("config")
        if (config == null) {
            findings += Finding(
                path, "binding_missing_config", Severity.ERROR,
                "Binding of type '$type' on '$propertyKey' has no 'config' object.",
            )
            return
        }

        when (val violations = catalog.validateBindingConfig(type, config)) {
            null -> {
                val known = catalog.bindingTypes()
                if (known.isNotEmpty() && type !in known) {
                    findings += Finding(
                        path, "unknown_binding_type", Severity.WARNING,
                        "'$type' is not a binding type this gateway can validate.",
                        "Known types: ${known.sorted().joinToString(", ")}.",
                    )
                }
            }
            else -> violations.forEach { violation ->
                findings += Finding(
                    path, "invalid_binding_config", Severity.ERROR,
                    "Binding on '$propertyKey' (type '$type'): ${violation.message}",
                )
            }
        }

        binding.getAsJsonArrayOrNull("transforms")?.forEachIndexed { i, transform ->
            if (!transform.isJsonObject || !transform.asJsonObject.has("type")) {
                findings += Finding(
                    path, "invalid_transform", Severity.ERROR,
                    "Transform $i on '$propertyKey' has no 'type'.",
                )
            }
        }
    }

    /**
     * Perspective event scripts are function *bodies*, so every line must be indented. An
     * unindented script is a syntax error at runtime and produces nothing useful in the logs.
     */
    private fun checkEvents(node: JsonObject, path: String, findings: MutableList<Finding>) {
        val events = node.get("events") ?: return
        if (!events.isJsonObject) {
            findings += Finding(path, "invalid_events", Severity.ERROR, "'events' must be an object.")
            return
        }

        events.asJsonObject.entrySet().forEach { (group, groupElement) ->
            if (!groupElement.isJsonObject) return@forEach
            groupElement.asJsonObject.entrySet().forEach { (eventName, eventElement) ->
                if (!eventElement.isJsonObject) return@forEach
                val event = eventElement.asJsonObject
                if (event.get("type")?.takeIf { it.isJsonPrimitive }?.asString != "script") return@forEach

                val script = event.getAsJsonObjectOrNull("config")
                    ?.get("script")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return@forEach

                unindentedLine(script)?.let { lineNumber ->
                    findings += Finding(
                        path, "script_indentation", Severity.ERROR,
                        "Script for event '$group.$eventName' is missing leading tab " +
                            "indentation on line $lineNumber.",
                        "Perspective event scripts are function bodies: every line must start " +
                            "with a tab character.",
                    )
                }
            }
        }
    }

    /** 1-based number of the first non-empty line lacking a leading tab, or null. */
    private fun unindentedLine(script: String): Int? {
        script.split('\n').forEachIndexed { i, line ->
            if (line.isNotEmpty() && !line.startsWith("\t")) return i + 1
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Custom property references
    // -----------------------------------------------------------------------

    private fun collectDeclaredCustomPaths(view: ViewDocument): Set<String> {
        val declared = mutableSetOf<String>()
        view.json().getAsJsonObjectOrNull("custom")?.keySet()?.forEach { declared += "view.custom.$it" }
        view.walk { node, path ->
            node.getAsJsonObjectOrNull("custom")?.keySet()?.forEach { declared += "$path.custom.$it" }
        }
        return declared
    }

    /**
     * A `property` binding pointing at `custom.foo` on the same component when no such custom
     * property exists. This is a real dead end — the binding silently yields null forever.
     */
    private fun checkCustomReferences(
        node: JsonObject,
        path: String,
        declared: Set<String>,
        findings: MutableList<Finding>,
    ) {
        val propConfig = node.getAsJsonObjectOrNull("propConfig") ?: return
        propConfig.entrySet().forEach { (key, entry) ->
            if (!entry.isJsonObject) return@forEach
            val binding = entry.asJsonObject.getAsJsonObjectOrNull("binding") ?: return@forEach
            if (binding.get("type")?.takeIf { it.isJsonPrimitive }?.asString != "property") return@forEach

            val reference = binding.getAsJsonObjectOrNull("config")
                ?.get("path")?.takeIf { it.isJsonPrimitive }?.asString
                ?: return@forEach

            // Only self-referencing `custom.x` is unambiguous enough to flag; anything with a
            // component or view qualifier could legitimately resolve elsewhere.
            if (!reference.startsWith("custom.")) return@forEach
            if ("$path.$reference" in declared) return@forEach

            findings += Finding(
                path, "undefined_custom_property", Severity.WARNING,
                "Binding on '$key' reads '$reference', but this component has no such custom property.",
                "Add it with perspective_set_custom_property, or correct the binding path.",
            )
        }
    }

    companion object {
        /** Findings as JSON, plus the counts a caller needs to decide whether to proceed. */
        fun toJson(findings: List<Finding>): JsonObject {
            val errors = findings.count { it.severity == Severity.ERROR }
            return jsonObject {
                put("valid", errors == 0)
                put("errorCount", errors)
                put("warningCount", findings.size - errors)
                put("findings", jsonArrayOf(findings.map { it.toJson() }))
            }
        }
    }
}