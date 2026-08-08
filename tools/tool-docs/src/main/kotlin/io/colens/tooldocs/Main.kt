package io.colens.tooldocs

import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.ignition.designer.model.DesignerContext
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.mcp.common.McpJson
import io.colens.mcp.common.Tool
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.put
import io.colens.mcp.designer.tools.DesignerTools
import io.colens.mcp.designer.tools.PerspectiveEditTools
import io.colens.mcp.gateway.tools.DataTools
import io.colens.mcp.gateway.tools.PerfTools
import io.colens.mcp.gateway.tools.PerspectiveTools
import io.colens.mcp.gateway.tools.ProjectTools
import io.colens.mcp.gateway.tools.SystemTools
import io.colens.mcp.gateway.tools.TagTools
import java.io.File
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Proxy
import java.util.Optional

/**
 * Emits the tool reference the docs site renders, as JSON.
 *
 * The trick that makes this cheap: every tool class takes an Ignition context but only *stores* it
 * — every actual `context.` call lives inside a `handler = { }` lambda that this program never
 * invokes. `GatewayContext` and `DesignerContext` are both interfaces, so a [Proxy] stub is enough
 * to construct the whole tool surface and read its metadata. No production code has to change.
 *
 * Per-tool JSON comes from [Tool.toJson], the same method `McpServer` uses on the wire, so what is
 * documented here is byte-identical to what a client receives from `tools/list`. That equality is
 * checkable — see the verification notes in the docs.
 */
fun main(args: Array<String>) {
    val out = File(args.singleOrNull() ?: error("usage: tool-docs <output.json>"))

    val calls = mutableListOf<String>()
    val gateway: GatewayContext = stubContext(calls)
    val designer: DesignerContext = stubContext(calls)

    // Mirrors GatewayHook.startup and DesignerHook.startup. Adding a tool CLASS to either hook
    // means adding it here too — the diff gate in CI compares this file's output against itself,
    // so it cannot notice a group that was never listed.
    val document = jsonObject {
        put("schemaVersion", 1)
        put("ignitionVersion", System.getProperty("ignition.version"))
        put("scopes", jsonArrayOf(listOf(
            scope(
                id = "gateway",
                label = "Gateway",
                endpoint = "POST /data/mcp/mcp",
                groups = listOf(
                    group("tags", "Tags", TagTools(gateway).tools()),
                    group("projects", "Projects", ProjectTools(gateway).tools()),
                    group("data", "Data", DataTools(gateway).tools()),
                    group("system", "System", SystemTools(gateway).tools()),
                    group("performance", "Performance", PerfTools(gateway).tools()),
                    group("perspective", "Perspective", PerspectiveTools(gateway).tools()),
                ),
            ),
            scope(
                id = "designer",
                label = "Designer",
                endpoint = "POST http://127.0.0.1:<port>/mcp",
                groups = listOf(
                    // saveTool() is listed explicitly: it is registered only when
                    // -Dmcp.designer.allowSave=true, so it is absent from tools(), but it still
                    // needs documenting or nobody can find out the flag exists.
                    DesignerTools(designer).let { group("designer", "Designer", it.tools() + it.saveTool()) },
                    group("perspective", "Perspective", PerspectiveEditTools(designer).tools()),
                ),
            ),
        )))
    }

    // The tripwire. A stub that returns nulls fails SILENTLY: a context call during construction
    // would yield a subtly wrong schema — an empty enum, a missing default — rather than a crash,
    // and that would ship as documentation. Recording every invocation turns this design's one
    // assumption into a build failure.
    check(calls.isEmpty()) {
        buildString {
            append("Tool construction called ")
            append(calls.distinct().joinToString(", "))
            append(" on a stub context.\n")
            append("The doc generator builds tools against a stub and assumes construction is ")
            append("context-free, so the metadata it just produced may be wrong. Move that call ")
            append("into the tool's handler, or make the field lazy.")
        }
    }

    out.parentFile?.mkdirs()
    // Trailing newline and stable ordering so `git diff` on this file is meaningful.
    out.writeText(McpJson.toPrettyString(document) + "\n")

    val total = document["scopes"].asJsonArray.sumOf { s ->
        s.asJsonObject["groups"].asJsonArray.sumOf { g -> g.asJsonObject["tools"].asJsonArray.size() }
    }
    println("Wrote $total tools to $out")
}

private fun scope(id: String, label: String, endpoint: String, groups: List<JsonObject>) = jsonObject {
    put("id", id)
    put("label", label)
    put("endpoint", endpoint)
    put("groups", jsonArrayOf(groups))
}

private fun group(id: String, label: String, tools: List<Tool>) = jsonObject {
    put("id", id)
    put("label", label)
    put("tools", jsonArrayOf(tools.map { it.toJson() }))
}

/**
 * An interface stub that records what was asked of it and answers with type-appropriate defaults.
 *
 * Defaults rather than exceptions: [Proxy] routes `hashCode`, `equals` and `toString` through the
 * handler, and Kotlin can invoke those incidentally — throwing there would fail for reasons that
 * have nothing to do with the assumption being tested.
 */
private inline fun <reified T : Any> stubContext(calls: MutableList<String>): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "<stub ${T::class.java.simpleName}>"
            else -> {
                calls += "${T::class.java.simpleName}.${method.name}"
                defaultValue(method.returnType)
            }
        }
    } as T

private fun defaultValue(type: Class<*>): Any? = when {
    type == Void.TYPE -> null
    type == Optional::class.java -> Optional.empty<Any>()
    // Boxing a zeroed one-element array is the least error-prone way to get the right primitive
    // default without a branch per type.
    type.isPrimitive -> ReflectArray.get(ReflectArray.newInstance(type, 1), 0)
    else -> null
}
