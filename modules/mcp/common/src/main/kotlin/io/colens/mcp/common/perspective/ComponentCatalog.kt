package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonObject

/**
 * What `:common` needs to know about Perspective's component registry, expressed without
 * referencing any Perspective type — so this module still compiles and its tests still run on a
 * machine that has never seen Perspective.
 *
 * Implemented by `GatewayComponentCatalog` (over `PerspectiveContext`) and
 * `DesignerComponentCatalog` (over Perspective's `DesignerHook`). Both registries are the same
 * class underneath: `DesignerComponentRegistry extends ComponentRegistry`.
 */
interface ComponentCatalog {

    /** Every registered component type id, e.g. `ia.display.label`. */
    fun componentTypes(): Set<String>

    /** Palette categories, for grouping in `perspective_list_component_types`. */
    fun categories(): Set<String>

    fun describe(typeId: String): ComponentTypeInfo?

    /**
     * Validates a component's `props` against that component's own JSON Schema.
     * Returns an empty list when the type is unknown or carries no schema.
     */
    fun validateProps(typeId: String, props: JsonObject): List<SchemaViolation>

    /**
     * Validates a binding's `config` against Perspective's shipped
     * `schemas/binding-<type>.json`. Returns null when the binding type is unrecognised, which
     * the caller reports differently from "recognised but invalid".
     */
    fun validateBindingConfig(bindingType: String, config: JsonObject): List<SchemaViolation>?

    /** Binding types we can validate, from the schema resources Perspective ships. */
    fun bindingTypes(): Set<String>
}

data class ComponentTypeInfo(
    val id: String,
    val name: String?,
    val category: String?,
    val deprecated: Boolean,
    val defaultMetaName: String?,
    /** Defaults to seed a new instance's `props`. */
    val defaultProperties: JsonObject?,
    /** Defaults for the child's `position`, which depends on the *parent* container type. */
    val childPositionDefaults: JsonObject?,
    val eventNames: List<String>,
    val extensionFunctionNames: List<String>,
)

data class SchemaViolation(val path: String?, val code: String?, val message: String)

/** Used when Perspective isn't installed; validation degrades to structural checks only. */
object NoComponentCatalog : ComponentCatalog {
    override fun componentTypes(): Set<String> = emptySet()
    override fun categories(): Set<String> = emptySet()
    override fun describe(typeId: String): ComponentTypeInfo? = null
    override fun validateProps(typeId: String, props: JsonObject): List<SchemaViolation> = emptyList()
    override fun validateBindingConfig(bindingType: String, config: JsonObject): List<SchemaViolation>? = null
    override fun bindingTypes(): Set<String> = emptySet()
}
