package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonObject
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.McpJson
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.put

/**
 * Editing surface for a Perspective `view.json`.
 *
 * A view file looks like this:
 *
 * ```
 * {
 *   "custom":     { ... },                     // view-level custom properties
 *   "params":     { ... },                     // view parameters
 *   "props":      { "defaultSize": { ... } },  // view properties
 *   "propConfig": { "params.foo": { "paramDirection": "input" } },
 *   "root":       { ...component... }          // always a container
 * }
 * ```
 *
 * and every component node looks like this:
 *
 * ```
 * {
 *   "type": "ia.display.label",
 *   "meta": { "name": "Label" },
 *   "position": { ... },                       // shape depends on the PARENT container
 *   "props": { "text": "Hello" },
 *   "custom": { ... },
 *   "propConfig": { "props.text": { "binding": { ... } } },   // bindings live HERE, not in props
 *   "events": { "dom": { "onClick": { "type": "script", "config": { "script": "\t..." } } } },
 *   "children": [ ... ]
 * }
 * ```
 *
 * Components are addressed by slash-separated path starting at `root`, e.g.
 * `root/FlexContainer/Label`. Segments resolve against `meta.name` first and fall back to a
 * numeric child index, which matches the addressing the Flint tooling uses — so paths produced
 * here are meaningful to a human reading them in the Designer.
 *
 * The document is deep-copied on construction, so a mutation that throws part-way leaves the
 * caller's original JSON untouched; the caller only persists [json] after everything succeeds.
 */
class ViewDocument private constructor(private val doc: JsonObject) {

    companion object {
        const val ROOT = "root"

        /** Paths that address the view itself rather than a component. */
        private val VIEW_ALIASES = setOf("", "view", ".")

        fun parse(text: String): ViewDocument {
            val element = try {
                McpJson.parse(text)
            } catch (e: Exception) {
                throw McpArgumentException("View is not valid JSON: ${e.message}")
            }
            if (!element.isJsonObject) throw McpArgumentException("View JSON must be an object")
            return ViewDocument(element.asJsonObject)
        }

        fun of(json: JsonObject): ViewDocument = ViewDocument(json)

        /** A new, empty view whose root is the given container node. */
        fun create(rootComponent: JsonObject): ViewDocument = ViewDocument(jsonObject {
            put("custom", JsonObject())
            put("params", JsonObject())
            put("props", JsonObject())
            put("root", rootComponent)
        })

        /** True when [path] addresses the view document rather than a component. */
        fun isViewPath(path: String?): Boolean = path.orEmpty().trim().lowercase() in VIEW_ALIASES
    }

    private val root: JsonObject = doc.deepCopy()

    /** The edited document. Persist this once all mutations have succeeded. */
    fun json(): JsonObject = root

    fun toJsonString(): String = McpJson.toPrettyString(root)

    // -----------------------------------------------------------------------
    // Navigation
    // -----------------------------------------------------------------------

    /** The root component node (`root`), which is always a container. */
    fun rootComponent(): JsonObject =
        root.getAsJsonObjectOrNull(ROOT)
            ?: throw McpArgumentException("View has no 'root' component")

    /**
     * Resolves a component path. Returns the node itself — mutating it mutates the document.
     */
    fun component(path: String): JsonObject {
        val segments = segments(path)
        var current = rootComponent()
        for (i in 1 until segments.size) {
            current = childOf(current, segments[i])
                ?: throw McpArgumentException(
                    "No component at '${segments.take(i + 1).joinToString("/")}'. " +
                        "Children of '${segments.take(i).joinToString("/")}': ${childNames(current)}"
                )
        }
        return current
    }

    fun componentOrNull(path: String): JsonObject? = try {
        component(path)
    } catch (e: McpArgumentException) {
        null
    }

    /** Parent node of the component at [path], or null when [path] is `root`. */
    fun parentOf(path: String): JsonObject? {
        val segments = segments(path)
        if (segments.size == 1) return null
        return component(segments.dropLast(1).joinToString("/"))
    }

    fun nameOf(component: JsonObject): String? =
        component.getAsJsonObjectOrNull("meta")?.get("name")?.takeIf { it.isJsonPrimitive }?.asString

    fun typeOf(component: JsonObject): String? =
        component.get("type")?.takeIf { it.isJsonPrimitive }?.asString

    private fun segments(path: String): List<String> {
        val trimmed = path.trim().trim('/')
        if (trimmed.isEmpty()) throw McpArgumentException("Component path must not be empty")
        val segments = trimmed.split('/')
        if (segments[0] != ROOT) {
            throw McpArgumentException("Component path must start with 'root', got '$path'")
        }
        return segments
    }

    private fun childOf(parent: JsonObject, segment: String): JsonObject? {
        val children = parent.getAsJsonArrayOrNull("children") ?: return null
        children.forEach { child ->
            if (child.isJsonObject && nameOf(child.asJsonObject) == segment) return child.asJsonObject
        }
        segment.toIntOrNull()?.let { index ->
            if (index in 0 until children.size() && children[index].isJsonObject) {
                return children[index].asJsonObject
            }
        }
        return null
    }

    private fun childNames(parent: JsonObject): List<String> =
        parent.getAsJsonArrayOrNull("children")
            ?.mapNotNull { if (it.isJsonObject) nameOf(it.asJsonObject) else null }
            .orEmpty()

    // -----------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------

    /**
     * Adds [node] under [parentPath]. Gives the node a unique `meta.name` if it has none or if
     * the name collides with a sibling. Returns the new component's path.
     */
    fun addComponent(parentPath: String, node: JsonObject, index: Int? = null): String {
        val parent = component(parentPath)
        val children = parent.getAsJsonArrayOrNull("children")
            ?: JsonArray().also { parent.add("children", it) }

        val requested = nameOf(node) ?: typeOf(node)?.substringAfterLast('.') ?: "Component"
        val name = uniqueChildName(parent, requested)
        node.metaObject().put("name", name)

        if (index == null || index >= children.size()) {
            children.add(node)
        } else {
            val rebuilt = JsonArray()
            children.forEachIndexed { i, existing ->
                if (i == maxOf(index, 0)) rebuilt.add(node)
                rebuilt.add(existing)
            }
            parent.add("children", rebuilt)
        }

        return "$parentPath/$name"
    }

    fun removeComponent(path: String) {
        val segments = segments(path)
        if (segments.size == 1) throw McpArgumentException("Cannot delete the view's root component")
        val parent = parentOf(path)!!
        val target = component(path)
        val children = parent.getAsJsonArrayOrNull("children")
            ?: throw McpArgumentException("Parent of '$path' has no children")

        val rebuilt = JsonArray()
        var removed = false
        children.forEach { child ->
            if (!removed && child === target) removed = true else rebuilt.add(child)
        }
        if (!removed) throw McpArgumentException("Could not remove '$path'")
        parent.add("children", rebuilt)
    }

    /** Reparents or reorders a component. Returns its new path. */
    fun moveComponent(path: String, newParentPath: String, index: Int? = null): String {
        val target = component(path)
        val newParent = component(newParentPath)

        // Moving a container into its own subtree would detach the whole branch.
        if (newParent === target || isDescendant(target, newParent)) {
            throw McpArgumentException("Cannot move '$path' into itself or one of its descendants")
        }

        val detached = target.deepCopy()
        removeComponent(path)
        return addComponent(newParentPath, detached, index)
    }

    fun renameComponent(path: String, newName: String): String {
        if (newName.isBlank()) throw McpArgumentException("Component name must not be blank")
        if (newName.contains('/')) throw McpArgumentException("Component name must not contain '/'")
        val segments = segments(path)
        val target = component(path)
        val parent = parentOf(path)

        if (parent != null && siblingNames(parent, target).contains(newName)) {
            throw McpArgumentException("'$newName' is already used by a sibling of '$path'")
        }
        target.metaObject().put("name", newName)
        return (segments.dropLast(1) + newName).joinToString("/")
    }

    private fun isDescendant(ancestor: JsonObject, candidate: JsonObject): Boolean {
        val children = ancestor.getAsJsonArrayOrNull("children") ?: return false
        children.forEach { child ->
            if (child.isJsonObject) {
                if (child.asJsonObject === candidate) return true
                if (isDescendant(child.asJsonObject, candidate)) return true
            }
        }
        return false
    }

    private fun siblingNames(parent: JsonObject, exclude: JsonObject?): Set<String> =
        parent.getAsJsonArrayOrNull("children")
            ?.mapNotNull {
                if (it.isJsonObject && it.asJsonObject !== exclude) nameOf(it.asJsonObject) else null
            }
            ?.toSet()
            .orEmpty()

    private fun uniqueChildName(parent: JsonObject, base: String): String {
        val taken = siblingNames(parent, null)
        if (base !in taken) return base
        var n = 1
        while ("$base$n" in taken) n++
        return "$base$n"
    }

    // -----------------------------------------------------------------------
    // Property containers — created on demand
    // -----------------------------------------------------------------------

    /** `props` of a component, or the view's own `props` for a view path. */
    fun props(path: String): JsonObject = target(path).objectAt("props")

    /** `custom` of a component, or the view's own `custom` for a view path. */
    fun custom(path: String): JsonObject = target(path).objectAt("custom")

    /** `propConfig` of a component, or the view's own for a view path. Bindings live here. */
    fun propConfig(path: String): JsonObject = target(path).objectAt("propConfig")

    /** `events` of a component. Not meaningful at view level. */
    fun events(path: String): JsonObject {
        if (isViewPath(path)) throw McpArgumentException("Events belong to components, not the view")
        return component(path).objectAt("events")
    }

    fun position(path: String): JsonObject {
        if (isViewPath(path)) throw McpArgumentException("Position belongs to components, not the view")
        return component(path).objectAt("position")
    }

    /** View parameters. */
    fun params(): JsonObject = root.objectAt("params")

    private fun target(path: String): JsonObject = if (isViewPath(path)) root else component(path)

    /**
     * The `propConfig` entry for a scoped property key such as `props.text`, created if absent.
     * This is where bindings and property-change scripts belong — never inside `props`.
     */
    fun propConfigEntry(path: String, propertyKey: String): JsonObject =
        propConfig(path).objectAt(propertyKey)

    /**
     * Goes through [target], not [component]: a view-level `propConfig` holds the entries for
     * `custom.*` and `params.*`, and resolving `'view'` as a component path instead made
     * `perspective_delete_binding` reject the very path its own schema documents (issue #7).
     */
    fun propConfigEntryOrNull(path: String, propertyKey: String): JsonObject? =
        target(path).getAsJsonObjectOrNull("propConfig")?.getAsJsonObjectOrNull(propertyKey)

    /** Removes a `propConfig` entry entirely. Returns true if one was there. */
    fun removePropConfigEntry(path: String, propertyKey: String): Boolean =
        target(path).getAsJsonObjectOrNull("propConfig")?.remove(propertyKey) != null

    /** Drops a `propConfig` entry once nothing is left in it, keeping views tidy. */
    fun prunePropConfigEntry(path: String, propertyKey: String) {
        val config = target(path).getAsJsonObjectOrNull("propConfig") ?: return
        if (config.getAsJsonObjectOrNull(propertyKey)?.size() == 0) config.remove(propertyKey)
    }

    /** An event group such as `dom` or `component`, created if absent. */
    fun eventGroup(path: String, group: String): JsonObject = events(path).objectAt(group)

    /** Guards the view path itself so the caller gets [events]' explanation, not a path error. */
    fun eventGroupOrNull(path: String, group: String): JsonObject? {
        if (isViewPath(path)) throw McpArgumentException("Events belong to components, not the view")
        return component(path).getAsJsonObjectOrNull("events")?.getAsJsonObjectOrNull(group)
    }

    // -----------------------------------------------------------------------
    // Summaries
    // -----------------------------------------------------------------------

    /** Flat list of every component: path, type, name, child count, and what's attached. */
    fun tree(): JsonArray {
        val out = JsonArray()
        val rootNode = root.getAsJsonObjectOrNull(ROOT) ?: return out
        walk(rootNode, ROOT) { node, path -> out.add(summarize(node, path)) }
        return out
    }

    fun componentCount(): Int {
        var count = 0
        val rootNode = root.getAsJsonObjectOrNull(ROOT) ?: return 0
        walk(rootNode, ROOT) { _, _ -> count++ }
        return count
    }

    /** Visits every component depth-first, passing its resolved path. */
    fun walk(action: (node: JsonObject, path: String) -> Unit) {
        val rootNode = root.getAsJsonObjectOrNull(ROOT) ?: return
        walk(rootNode, ROOT, action)
    }

    private fun walk(node: JsonObject, path: String, action: (JsonObject, String) -> Unit) {
        action(node, path)
        val children = node.getAsJsonArrayOrNull("children") ?: return
        children.forEachIndexed { i, child ->
            if (child.isJsonObject) {
                val childName = nameOf(child.asJsonObject) ?: i.toString()
                walk(child.asJsonObject, "$path/$childName", action)
            }
        }
    }

    private fun summarize(node: JsonObject, path: String): JsonObject = jsonObject {
        put("path", path)
        put("type", typeOf(node))
        put("name", nameOf(node))
        put("childCount", node.getAsJsonArrayOrNull("children")?.size() ?: 0)
        put("boundProperties", boundPropertyKeys(node).let { keys ->
            JsonArray().apply { keys.forEach { add(it) } }
        })
        put("eventCount", countEvents(node))
        put("customPropertyCount", node.getAsJsonObjectOrNull("custom")?.size() ?: 0)
    }

    /** Property keys on this node that carry a binding, e.g. `props.text`. */
    fun boundPropertyKeys(node: JsonObject): List<String> =
        node.getAsJsonObjectOrNull("propConfig")
            ?.entrySet()
            ?.filter { (_, v) -> v.isJsonObject && v.asJsonObject.has("binding") }
            ?.map { it.key }
            .orEmpty()

    private fun countEvents(node: JsonObject): Int =
        node.getAsJsonObjectOrNull("events")
            ?.entrySet()
            ?.sumOf { (_, group) -> if (group.isJsonObject) group.asJsonObject.size() else 0 }
            ?: 0
}

// ---------------------------------------------------------------------------
// Small Gson conveniences. Gson's own getAsJsonObject throws on a wrong type
// rather than returning null, which is never what we want while walking a
// document that a human (or a model) may have hand-edited.
// ---------------------------------------------------------------------------

fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.getAsJsonArrayOrNull(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

/** Returns the object at [key], creating (or replacing a non-object) as needed. */
internal fun JsonObject.objectAt(key: String): JsonObject {
    getAsJsonObjectOrNull(key)?.let { return it }
    return JsonObject().also { add(key, it) }
}

internal fun JsonObject.metaObject(): JsonObject = objectAt("meta")
