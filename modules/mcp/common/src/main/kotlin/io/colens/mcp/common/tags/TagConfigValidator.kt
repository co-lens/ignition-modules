package io.colens.mcp.common.tags

import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.ignition.common.tags.TagUtilities
import io.colens.mcp.common.Finding
import io.colens.mcp.common.Severity

/**
 * Static checks on tag configuration JSON, run *before* it reaches Ignition.
 *
 * This exists because `TagUtilities.toTagConfiguration` fails silently on exactly the mistakes a
 * model makes most often. Verified against `common-8.3.8.jar`:
 *
 * - `parameters: { X: { value: "PLC7" } }` with no `dataType` returns successfully having emitted
 *   `{ X: { dataType: "Integer" } }` — the value dropped, the type wrong. It logs to the `tags.json`
 *   logger and carries on, so the caller is told the save worked.
 * - An unknown key such as `notARealProperty` is retained verbatim, which means `datatype` for
 *   `dataType` becomes a meaningless custom property rather than an error.
 * - A config with no `name` throws `IndexOutOfBoundsException: Index 0 out of bounds for length 0`,
 *   which tells the caller nothing about what is wrong.
 * - `name: "bad/name"` is accepted even though `TagUtilities.isValidName` returns false for it.
 *
 * Each of those is a rule below, and each has a regression test. A tool that reported success in
 * those cases would be worse than no tool, because the caller would stop looking.
 *
 * **Property *values* are deliberately not validated — only names and shapes.** There is no
 * allowlist of data types here, so `"dataType": "Int4"` passes untouched even though Ignition
 * stores it as `Integer`. That is a decision rather than an omission: every silent failure above
 * is a name or a structure, never a value, and a type list would be a second source of truth for
 * something the platform already owns, maintained by guessing at it. Refusing input that
 * `system.tag.configure` accepts would narrow what these tools can express relative to the API
 * they exist to replace. If a value-level rule is ever added, it wants that same test — does
 * Ignition accept this, and does accepting it cause silent harm?
 *
 * Pure `ignition-common` — no `GatewayContext` — which is what lets it live here and be tested
 * without a gateway, exactly as `ViewValidator` is.
 */
class TagConfigValidator(private val catalog: TagPropertyCatalog = NoTagPropertyCatalog) {

    /**
     * Validates one tag config, recursing into nested `tags`.
     *
     * [path] names the config in its findings and roots the paths of its children. It defaults to
     * the config's own name, so a finding two levels down reads `Motor/Run` rather than `/Run`.
     */
    fun validate(config: JsonObject, path: String = config.stringOrNull("name").orEmpty()): List<Finding> {
        val findings = mutableListOf<Finding>()
        walk(config, path, findings)
        return findings
    }

    /** Validates the array a tool was handed, naming each entry by its tag name or its index. */
    fun validateAll(configs: List<JsonObject>): List<Finding> =
        configs.flatMapIndexed { index, config ->
            validate(config, nameOf(config) ?: "[$index]")
        }

    private fun walk(config: JsonObject, path: String, findings: MutableList<Finding>) {
        checkName(config, path, findings)
        val type = checkTagType(config, path, findings)
        checkProperties(config, path, findings)
        checkParameters(config, path, findings)
        checkChildren(config, type, path, findings)
    }

    // -----------------------------------------------------------------------

    private fun checkName(config: JsonObject, path: String, findings: MutableList<Finding>) {
        val name = nameOf(config)
        if (name == null) {
            findings += Finding(
                path, "missing_name", Severity.ERROR,
                "Tag config has no 'name'. Ignition's parser fails with an index error rather " +
                    "than a useful message, so this is caught here instead.",
                "Add the tag's name: \"name\": \"MyTag\". The path you pass to configure_tags is " +
                    "the parent folder; the name comes from the config itself.",
            )
            return
        }
        if (!TagUtilities.isValidName(name)) {
            findings += Finding(
                path, "invalid_name", Severity.ERROR,
                "'$name' is not a valid tag name. Ignition accepts it here and rejects or mangles " +
                    "it later.",
                "Tag names cannot contain path or expression characters such as / \\ . [ ] { } or " +
                    "control characters. Rename it, or create a folder if you meant a path.",
            )
        }
    }

    private fun checkTagType(config: JsonObject, path: String, findings: MutableList<Finding>): String? {
        val type = config.stringOrNull("tagType") ?: return null
        if (type !in TAG_TYPES) {
            findings += Finding(
                path, "unknown_tag_type", Severity.ERROR,
                "'$type' is not a tag type.",
                "Use one of: ${TAG_TYPES.sorted().joinToString(", ")}. Omit 'tagType' entirely to " +
                    "let Ignition infer it.",
            )
            return null
        }
        if (type == "UdtInstance" && config.stringOrNull("typeId").isNullOrBlank()) {
            findings += Finding(
                path, "udt_instance_missing_type", Severity.ERROR,
                "UDT instance has no 'typeId', so there is no definition to instantiate.",
                "Set \"typeId\" to the definition's path under _types_, e.g. \"typeId\": \"Motor\".",
            )
        }
        return type
    }

    /**
     * Unknown keys. A near-miss of a real property is almost certainly a typo and is fatal,
     * because Ignition keeps it as a custom property and the tag silently does the wrong thing.
     * A genuinely unfamiliar key is only a warning — tags do legitimately carry custom properties.
     */
    private fun checkProperties(config: JsonObject, path: String, findings: MutableList<Finding>) {
        val known = catalog.propertyNames()
        if (known.isEmpty()) return // No catalog: can't tell, don't guess.

        val byLowercase = known.associateBy { it.lowercase() }

        config.keySet().forEach { key ->
            if (key in STRUCTURAL_KEYS || key in known) return@forEach

            val match = byLowercase[key.lowercase()]
            if (match != null) {
                findings += Finding(
                    path, "misspelled_property", Severity.ERROR,
                    "'$key' differs only in case from the real property '$match'. Ignition would " +
                        "keep '$key' as a custom property and leave '$match' unset.",
                    "Rename it to '$match'.",
                )
            } else {
                findings += Finding(
                    path, "unknown_property", Severity.WARNING,
                    "'$key' is not a tag property this provider knows. It will be stored as a " +
                        "custom property.",
                    "If that was intended, ignore this. Otherwise check the spelling against " +
                        "get_tag_config output for a similar tag.",
                )
            }
        }
    }

    /**
     * The expensive one. A parameter override written the natural way — value only, since the
     * definition already declares the type — parses as an Integer and loses the value, with no
     * error returned to the caller.
     */
    private fun checkParameters(config: JsonObject, path: String, findings: MutableList<Finding>) {
        val parameters = config.get("parameters") ?: return
        if (!parameters.isJsonObject) {
            findings += Finding(
                path, "invalid_parameters", Severity.ERROR,
                "'parameters' must be an object mapping parameter names to their configuration.",
            )
            return
        }

        parameters.asJsonObject.entrySet().forEach { (name, entry) ->
            if (!entry.isJsonObject) {
                findings += Finding(
                    path, "invalid_parameters", Severity.ERROR,
                    "Parameter '$name' must be an object such as " +
                        "{ \"dataType\": \"String\", \"value\": \"PLC1\" }.",
                )
                return@forEach
            }
            val parameter = entry.asJsonObject
            if (parameter.has("value") && parameter.stringOrNull("dataType").isNullOrBlank()) {
                findings += Finding(
                    path, "parameter_missing_datatype", Severity.ERROR,
                    "Parameter '$name' has a value but no 'dataType'. Ignition defaults the type " +
                        "to Integer, fails to parse the value into it, and DISCARDS the value — " +
                        "reporting success. The tag would be created with the parameter unset.",
                    "Always state the type alongside the value, even when overriding a parameter " +
                        "the UDT definition already types: " +
                        "\"$name\": { \"dataType\": \"String\", \"value\": ... }.",
                )
            }
        }
    }

    private fun checkChildren(
        config: JsonObject,
        type: String?,
        path: String,
        findings: MutableList<Finding>,
    ) {
        val children = config.get("tags") ?: return
        if (!children.isJsonArray) {
            findings += Finding(path, "invalid_children", Severity.ERROR, "'tags' must be an array.")
            return
        }

        if (type == "AtomicTag") {
            findings += Finding(
                path, "children_on_atomic_tag", Severity.ERROR,
                "An AtomicTag cannot contain other tags, but this one has a 'tags' array.",
                "Use \"tagType\": \"Folder\" to group tags, or \"UdtType\" to define a UDT.",
            )
        }

        val seen = mutableSetOf<String>()
        children.asJsonArray.forEach { element ->
            if (!element.isJsonObject) {
                findings += Finding(
                    path, "invalid_children", Severity.ERROR, "Every entry in 'tags' must be an object.",
                )
                return@forEach
            }
            val child = element.asJsonObject
            val name = nameOf(child)
            if (name != null && !seen.add(name)) {
                findings += Finding(
                    childPath(path, name), "duplicate_child_name", Severity.ERROR,
                    "Two children of '${path.ifEmpty { "the root" }}' are both named '$name'. " +
                        "The second would overwrite the first.",
                    "Names must be unique among siblings.",
                )
            }
            walk(child, childPath(path, name ?: "?"), findings)
        }
    }

    // -----------------------------------------------------------------------

    private fun nameOf(config: JsonObject): String? = config.stringOrNull("name")

    private fun childPath(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private companion object {
        /** The `TagObjectType` values that make sense in authored configuration. */
        val TAG_TYPES = setOf("AtomicTag", "Folder", "UdtType", "UdtInstance")

        /** Keys that shape the document rather than naming a tag property. */
        val STRUCTURAL_KEYS = setOf("tags", "parameters")
    }
}
