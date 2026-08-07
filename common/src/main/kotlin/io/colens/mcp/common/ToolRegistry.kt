package io.colens.mcp.common

import com.inductiveautomation.ignition.common.gson.JsonArray

/** An ordered, name-unique set of tools. */
class ToolRegistry(tools: List<Tool> = emptyList()) {

    private val byName = LinkedHashMap<String, Tool>()

    init {
        tools.forEach(::add)
    }

    fun add(tool: Tool): ToolRegistry = apply {
        require(byName.put(tool.name, tool) == null) { "Duplicate tool name: ${tool.name}" }
    }

    fun addAll(tools: Iterable<Tool>): ToolRegistry = apply { tools.forEach(::add) }

    operator fun get(name: String): Tool? = byName[name]

    fun all(): List<Tool> = byName.values.toList()

    val size: Int get() = byName.size

    /**
     * A registry containing only the read-only tools. The gateway mounts this behind the
     * READ-permission route, so write gating is structural: a tool that isn't in the registry
     * can't be listed or called, regardless of what the client asks for.
     */
    fun readOnlyView(): ToolRegistry = ToolRegistry(byName.values.filter { it.readOnly })

    fun toJsonArray(): JsonArray = jsonArrayOf(byName.values.map { it.toJson() })
}
