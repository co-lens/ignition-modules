package io.colens.mcp.common.tags

/**
 * The set of tag property names this gateway understands.
 *
 * Same shape and same reason as `ComponentCatalog`: the knowledge lives on the gateway, the rules
 * that use it live here, and `:common` stays compilable and testable without one.
 *
 * The gateway implementation reads `TagProvider.getTagConfigModelAsync()`, whose
 * `ConfigurationPropertyModel.getModelProperties()` yields every `Property` a tag can carry.
 * Property sets differ per provider, so a catalog is scoped to the provider being written to.
 */
fun interface TagPropertyCatalog {

    /** Every known tag property name, e.g. `dataType`, `opcItemPath`, `historyEnabled`. */
    fun propertyNames(): Set<String>
}

/**
 * Used when the property model can't be reached — an unavailable provider, an older platform, or a
 * unit test. The structural rules still run; only the two rules that need to know what a real
 * property looks like go quiet.
 */
object NoTagPropertyCatalog : TagPropertyCatalog {
    override fun propertyNames(): Set<String> = emptySet()
}
