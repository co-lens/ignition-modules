package io.colens.mcp.gateway.tools

import com.inductiveautomation.ignition.common.browsing.BrowseFilter
import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonElement
import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.ignition.common.model.values.BasicQualifiedValue
import com.inductiveautomation.ignition.common.model.values.QualifiedValue
import com.inductiveautomation.ignition.common.tags.TagUtilities
import com.inductiveautomation.ignition.common.tags.browsing.NodeDescription
import com.inductiveautomation.ignition.common.tags.model.TagPath
import com.inductiveautomation.ignition.common.tags.model.SecurityContext
import com.inductiveautomation.ignition.common.tags.model.TagProvider
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.Tool
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonArrayOfStrings
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.optBoolean
import io.colens.mcp.common.optInt
import io.colens.mcp.common.optString
import io.colens.mcp.common.put
import io.colens.mcp.common.requireString
import io.colens.mcp.common.requireStringList
import io.colens.mcp.common.schema
import io.colens.mcp.common.toJsonValue
import java.util.concurrent.TimeUnit

class TagTools(private val context: GatewayContext) {

    fun tools(): List<Tool> = listOf(
        listTagProviders(),
        browseTags(),
        readTags(),
        getTagConfig(),
        writeTags(),
    )

    private fun listTagProviders() = Tool(
        name = "list_tag_providers",
        title = "List tag providers",
        description = "Lists the tag providers configured on this gateway.",
        inputSchema = schema(),
        handler = {
            jsonObject {
                put("providers", jsonArrayOfStrings(context.tagManager.tagProviderNames))
            }
        },
    )

    private fun browseTags() = Tool(
        name = "browse_tags",
        title = "Browse tags",
        description = "Browses the tag tree. Start at the root with an empty path, then drill in. " +
            "Set recursive=true to walk folders, bounded by maxDepth and limit.",
        inputSchema = schema {
            string("path", "Tag path to browse, e.g. '[default]Area1/Line2'. Empty browses the provider root.")
            string("provider", "Tag provider name. Ignored when 'path' includes [provider]. Defaults to 'default'.")
            boolean("recursive", "Recurse into folders and UDT instances.", default = false)
            integer("maxDepth", "Maximum recursion depth when recursive.", default = 3)
            integer("limit", "Maximum number of nodes to return.", default = 500)
        },
        handler = { args ->
            val path = tagPath(args.optString("path"), args.optString("provider"))
            val provider = tagProvider(path)
            val recursive = args.optBoolean("recursive", false)
            val maxDepth = args.optInt("maxDepth", 3)
            val limit = args.optInt("limit", 500)

            val nodes = JsonArray()
            val truncated = browse(provider, path, if (recursive) maxDepth else 1, limit, nodes)

            jsonObject {
                put("path", path.toString())
                put("provider", path.source)
                put("count", nodes.size())
                put("truncated", truncated)
                put("nodes", nodes)
            }
        },
    )

    private fun readTags() = Tool(
        name = "read_tags",
        title = "Read tag values",
        description = "Reads current values for one or more tag paths, with quality and timestamp.",
        inputSchema = schema {
            stringArray("paths", "Tag paths to read, e.g. ['[default]Area1/Temp'].", required = true)
        },
        handler = { args ->
            val paths = args.requireStringList("paths").map { tagPath(it, null) }
            val bySource = paths.groupBy { it.source }

            val results = LinkedHashMap<TagPath, QualifiedValue>()
            bySource.forEach { (source, sourcePaths) ->
                val provider = tagProvider(source)
                val values = provider.readAsync(sourcePaths, SecurityContext.systemContext()).get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                sourcePaths.forEachIndexed { i, p -> results[p] = values[i] }
            }

            jsonObject {
                put("tags", jsonArrayOf(paths.map { p ->
                    val qv = results[p]
                    jsonObject {
                        put("path", p.toString())
                        put("value", toJsonValue(qv?.value))
                        put("quality", qv?.quality?.toString())
                        put("good", qv?.quality?.isGood)
                        put("timestamp", qv?.timestamp?.toInstant()?.toString())
                    }
                }))
            }
        },
    )

    private fun getTagConfig() = Tool(
        name = "get_tag_config",
        title = "Get tag configuration",
        description = "Returns the full JSON configuration of tags or folders — data type, bindings, " +
            "alarms, history settings and UDT parameters. Use this to understand how a tag is built, " +
            "not just its current value.",
        inputSchema = schema {
            stringArray("paths", "Tag or folder paths to fetch configuration for.", required = true)
            boolean("recursive", "Include the configuration of child tags.", default = false)
            boolean("localOnly", "Exclude configuration inherited from a UDT definition.", default = false)
        },
        handler = { args ->
            val paths = args.requireStringList("paths").map { tagPath(it, null) }
            val recursive = args.optBoolean("recursive", false)
            val localOnly = args.optBoolean("localOnly", false)

            val configs = JsonArray()
            paths.groupBy { it.source }.forEach { (source, sourcePaths) ->
                val models = tagProvider(source)
                    .getTagConfigsAsync(sourcePaths, recursive, localOnly)
                    .get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                models.forEach { configs.add(TagUtilities.toJsonObject(it)) }
            }

            jsonObject { put("tags", configs) }
        },
    )

    private fun writeTags() = Tool(
        name = "write_tags",
        title = "Write tag values",
        description = "Writes values to tags. This changes live process values — confirm the paths " +
            "with read_tags first.",
        inputSchema = schema {
            array(
                name = "writes",
                description = "Writes to perform.",
                items = jsonObject {
                    put("type", "object")
                    put("properties", jsonObject {
                        put("path", jsonObject {
                            put("type", "string")
                            put("description", "Tag path to write, e.g. '[default]Area1/Setpoint'.")
                        })
                        put("value", jsonObject {
                            put("description", "Value to write (string, number, boolean or null).")
                        })
                    })
                    put("required", jsonArrayOfStrings(listOf("path", "value")))
                },
                required = true,
            )
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val writes = args.get("writes")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw McpArgumentException("Missing required argument 'writes'")
            if (writes.size() == 0) throw McpArgumentException("'writes' must not be empty")

            val requests = writes.map { element ->
                val o = element.asJsonObject
                tagPath(o.requireString("path"), null) to BasicQualifiedValue(unwrap(o.get("value")))
            }

            val outcomes = LinkedHashMap<Int, String>()
            requests.withIndex()
                .groupBy { it.value.first.source }
                .forEach { (source, indexed) ->
                    val qualities = tagProvider(source)
                        .writeAsync(
                            indexed.map { it.value.first },
                            indexed.map { it.value.second },
                            SecurityContext.systemContext(),
                        )
                        .get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    indexed.forEachIndexed { i, iv -> outcomes[iv.index] = qualities[i].toString() }
                }

            jsonObject {
                put("results", jsonArrayOf(requests.mapIndexed { i, (path, value) ->
                    jsonObject {
                        put("path", path.toString())
                        put("value", toJsonValue(value.value))
                        put("quality", outcomes[i])
                        put("ok", outcomes[i]?.contains("Good", ignoreCase = true) ?: false)
                    }
                }))
            }
        },
    )

    // -----------------------------------------------------------------------

    private fun browse(
        provider: TagProvider,
        path: TagPath,
        remainingDepth: Int,
        limit: Int,
        into: JsonArray,
    ): Boolean {
        if (remainingDepth <= 0) return false
        if (into.size() >= limit) return true

        val results = provider.browseAsync(path, BrowseFilter.NONE)
            .get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (results.resultQuality.isNotGood) {
            throw IllegalStateException("Browse of '$path' returned quality ${results.resultQuality}")
        }

        var truncated = false
        for (node in results.results.orEmpty()) {
            if (into.size() >= limit) return true
            val childPath = path.getChildPath(node.name)
            into.add(describe(node, childPath))
            if (node.hasChildren() && remainingDepth > 1) {
                truncated = browse(provider, childPath, remainingDepth - 1, limit, into) || truncated
            }
        }
        return truncated
    }

    private fun describe(node: NodeDescription, path: TagPath): JsonObject = jsonObject {
        put("name", node.name)
        put("path", path.toString())
        put("objectType", node.objectType?.toString())
        put("dataType", node.dataType?.toString())
        put("hasChildren", node.hasChildren())
        node.currentValue?.let { qv ->
            put("value", toJsonValue(qv.value))
            put("quality", qv.quality?.toString())
        }
    }

    private fun tagPath(raw: String?, provider: String?): TagPath {
        val text = raw.orEmpty()
        return try {
            if (text.startsWith("[")) {
                TagPathParser.parse(text)
            } else {
                TagPathParser.parse(provider ?: DEFAULT_PROVIDER, text)
            }
        } catch (e: Exception) {
            throw McpArgumentException("Invalid tag path '$text': ${e.message}")
        }
    }

    private fun tagProvider(path: TagPath): TagProvider = tagProvider(path.source)

    private fun tagProvider(source: String?): TagProvider {
        val name = source?.takeIf { it.isNotBlank() } ?: DEFAULT_PROVIDER
        return context.tagManager.getTagProvider(name)
            ?: throw McpArgumentException(
                "No such tag provider '$name'. Available: ${context.tagManager.tagProviderNames}"
            )
    }

    /** JSON -> a JVM value suitable for a tag write. */
    private fun unwrap(element: JsonElement?): Any? = when {
        element == null || element.isJsonNull -> null
        element.isJsonPrimitive -> element.asJsonPrimitive.let { p ->
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asString.toIntOrNull() ?: p.asString.toLongOrNull() ?: p.asDouble
                else -> p.asString
            }
        }
        else -> element.toString()
    }

    /*
     * Tag calls run under SecurityContext.systemContext(). Authorisation already happened at the
     * route: the caller presented an API token whose permissions Ignition validated before this
     * code ran. Passing an empty context instead would re-check tag-level security against a
     * principal that doesn't exist here and deny legitimate reads; passing null risks an NPE
     * inside the provider. Tag-level security zones are therefore NOT a second line of defence
     * here — scope the API token instead.
     */

    private companion object {
        const val DEFAULT_PROVIDER = "default"
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L
    }
}
