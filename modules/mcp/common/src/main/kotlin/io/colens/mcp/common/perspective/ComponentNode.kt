package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonObject
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.put

/** Merges [patch] into [target], recursing into nested objects rather than replacing them. */
fun mergeJson(target: JsonObject, patch: JsonObject) {
    patch.entrySet().forEach { (key, value) ->
        val existing = target.get(key)
        if (value.isJsonObject && existing != null && existing.isJsonObject) {
            mergeJson(existing.asJsonObject, value.asJsonObject)
        } else {
            target.add(key, value)
        }
    }
}

/**
 * The JSON for a new component.
 *
 * **[props] is written as given — the type's schema defaults are deliberately not seeded in.**
 * Perspective persists only properties that were explicitly set, and applies schema defaults at
 * runtime for everything absent. That convention is load-bearing rather than cosmetic: an absent
 * property is the signal that it is *still at its default*, which is what lets a reader — a human,
 * a linter, or `perspective_analyze_performance`'s rules — tell "left alone" from "deliberately
 * set to the default value". Writing the full default set destroys that distinction for every
 * component the tool touches.
 *
 * Measured against 91 real committed views: 70 of 70 `ia.display.view` components carry no
 * `props.loading`, though it has a schema default of `{"order": "after-parent"}`. Containers carry
 * only what was changed — a flex root with `justify` alone, a label with `text`.
 *
 * Note this is not what `ComponentDescriptor.getInitialProps` returns, despite the name: that
 * resolves a *palette variant* and yields the same defaults (a superset, for a variant that
 * overrides props). The Designer's pruning happens in the workspace's browser-side model, so no
 * Java call reproduces it. Not seeding at all does.
 *
 * [position] is different and *is* seeded by the caller from the parent container's
 * `childPositionDefaults`: it is layout state the parent requires, not a schema default, and real
 * views do carry it on every child. Pass null for a root container, which has no parent and no
 * position.
 */
fun newComponentNode(
    type: String,
    name: String?,
    defaultMetaName: String?,
    props: JsonObject?,
    position: JsonObject?,
): JsonObject = jsonObject {
    put("type", type)
    put("meta", jsonObject { put("name", name ?: defaultMetaName ?: type.substringAfterLast('.')) })
    put("props", props?.deepCopy() ?: JsonObject())
    if (position != null) put("position", position)
}
