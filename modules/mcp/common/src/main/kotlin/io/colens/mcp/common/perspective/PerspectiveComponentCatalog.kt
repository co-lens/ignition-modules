package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonElement
import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.ignition.common.jsonschema.JsonSchema
import com.inductiveautomation.perspective.common.api.ComponentDescriptor
import com.inductiveautomation.perspective.common.api.ComponentRegistry
import io.colens.mcp.common.McpJson
import org.slf4j.LoggerFactory

/**
 * [ComponentCatalog] over Perspective's real component registry.
 *
 * Lives in `:common` because it needs nothing scope-specific: `DesignerComponentRegistry` simply
 * extends `ComponentRegistry`, so the gateway and the Designer hand in different registries and
 * share this one implementation.
 *
 * Two things make this thin. Perspective's `ComponentDescriptor` already returns Ignition's
 * *shaded* Gson (`com.inductiveautomation.ignition.common.gson.JsonObject`) — the same type our
 * tools hand back — so nothing is converted. And prop validation is `JsonSchema.validate(...)`,
 * Ignition's own validator, so we assert nothing about Perspective's schemas ourselves.
 *
 * Binding validation reads the schemas Perspective *ships* on its own classpath
 * (`schemas/binding-tag.json` and friends), which is why binding types are discovered rather than
 * hardcoded: `BindingRegistry` is register-only and cannot be enumerated.
 */
class PerspectiveComponentCatalog(private val registry: () -> ComponentRegistry?) : ComponentCatalog {

    private val logger = LoggerFactory.getLogger("mcp.Perspective.Catalog")

    /** Cached because it never changes for a given Perspective build. */
    private val bindingSchemas: Map<String, JsonSchema> by lazy { loadBindingSchemas() }

    override fun componentTypes(): Set<String> = registry()?.get()?.keys.orEmpty()

    override fun categories(): Set<String> = registry()?.categories.orEmpty()

    override fun describe(typeId: String): ComponentTypeInfo? {
        val descriptor = registry()?.find(typeId)?.orElse(null) ?: return null
        return ComponentTypeInfo(
            id = descriptor.id(),
            name = descriptor.name(),
            category = descriptor.paletteCategory(),
            deprecated = descriptor.deprecated(),
            defaultMetaName = safe { descriptor.defaultMetaName() },
            defaultProperties = safe { descriptor.defaultProperties() },
            childPositionDefaults = safe { descriptor.childPositionDefaults()?.orElse(null) },
            eventNames = safe { descriptor.events().map { it.name } }.orEmpty(),
            extensionFunctionNames = safe { descriptor.extensionFunctions().map { it.name } }.orEmpty(),
        )
    }

    /** Defaults for a child of the given container type, used when adding a component. */
    fun childPositionDefaultsOf(parentTypeId: String): JsonObject? =
        safe { registry()?.find(parentTypeId)?.orElse(null)?.childPositionDefaults()?.orElse(null) }

    /*
     * There is deliberately no `initialPropsOf` here. One existed, was never called, and did not
     * mean what its name suggested: `ComponentDescriptor.getInitialProps(String)` resolves a
     * *palette variant id*, not a parent type, and returns `schema.getDefaultValue(true)` — the
     * same defaults, plus any props the variant overrides. It is a superset of
     * `defaultProperties()`, never a smaller "what the palette actually writes" set.
     *
     * The Designer writes few properties because its workspace prunes to the delta browser-side,
     * which no Java call reproduces. `newComponentNode` therefore seeds no props at all, which
     * produces the same file. Restoring a method here would just invite the same wrong fix again.
     */

    override fun validateProps(typeId: String, props: JsonObject): List<SchemaViolation> {
        val descriptor = safe { registry()?.find(typeId)?.orElse(null) } ?: return emptyList()
        val schema = safe { descriptor.schema() } ?: return emptyList()

        // Validate what Perspective will actually see. A stored view omits every property left at
        // its default — the Designer writes only what changed — so validating the stored object
        // alone reports each unwritten default as "missing but it is required" and drowns the real
        // findings. Layering the stored values over the descriptor's defaults reproduces runtime.
        val effective = safe { descriptor.defaultProperties()?.deepCopy() } ?: JsonObject()
        deepMerge(effective, props)

        return validate(schema, effective, "props")
    }

    private fun deepMerge(target: JsonObject, patch: JsonObject) {
        patch.entrySet().forEach { (key, value) ->
            val existing = target.get(key)
            if (value.isJsonObject && existing != null && existing.isJsonObject) {
                deepMerge(existing.asJsonObject, value.asJsonObject)
            } else {
                target.add(key, value)
            }
        }
    }

    override fun validateBindingConfig(bindingType: String, config: JsonObject): List<SchemaViolation>? {
        val schema = bindingSchemas[bindingType] ?: return null
        return validate(schema, config, "config")
    }

    override fun bindingTypes(): Set<String> = bindingSchemas.keys

    private fun validate(schema: JsonSchema, value: JsonElement, at: String): List<SchemaViolation> =
        try {
            schema.validate(value, value, at).map {
                SchemaViolation(path = it.path, code = it.code, message = it.message ?: it.toString())
            }
        } catch (t: Throwable) {
            // A schema we can't run is not a reason to fail the caller's whole request.
            logger.debug("Schema validation failed at '{}': {}", at, t.toString())
            emptyList()
        }

    /**
     * Discovers binding types from the schema resources on Perspective's classpath. Loaded via
     * [ComponentDescriptor]'s classloader so we read Perspective's own copy, whatever version is
     * installed.
     */
    private fun loadBindingSchemas(): Map<String, JsonSchema> {
        val loader = ComponentDescriptor::class.java.classLoader ?: return emptyMap()
        val found = LinkedHashMap<String, JsonSchema>()

        // Perspective ships one schema per binding type; there's no index, so probe the known
        // resource names. An unknown-to-us type simply isn't validated (reported as a warning).
        for ((type, resource) in BINDING_SCHEMA_RESOURCES) {
            try {
                loader.getResourceAsStream(resource)?.use { stream ->
                    val shipped = McpJson.parse(stream.reader(Charsets.UTF_8).readText())
                    val patched = BindingSchemaPatches.patch(type, shipped)
                    if (patched !== shipped) {
                        logger.debug("Restored declarations Perspective omits from {}", resource)
                    }
                    // Back through JsonSchema.parse rather than the public JsonSchema(JsonElement)
                    // constructor: parse goes via JsonSchemaBuilder, which turns the $ref cache on,
                    // and the element constructor does not. binding-tag-history.json has $refs.
                    found[type] = JsonSchema.parse(
                        McpJson.toString(patched).byteInputStream(Charsets.UTF_8)
                    )
                }
            } catch (t: Throwable) {
                logger.debug("Could not load {}: {}", resource, t.toString())
            }
        }

        logger.debug("Loaded {} Perspective binding schemas", found.size)
        return found
    }

    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        null
    }

    private companion object {
        val BINDING_SCHEMA_RESOURCES = mapOf(
            "tag" to "schemas/binding-tag.json",
            "expr" to "schemas/binding-expr.json",
            "expression" to "schemas/binding-expr.json",
            "expr-struct" to "schemas/binding-expr-struct.json",
            "property" to "schemas/binding-property.json",
            "query" to "schemas/binding-query.json",
            "tag-history" to "schemas/binding-tag-history.json",
            "http" to "schemas/binding-http.json",
        )
    }
}
