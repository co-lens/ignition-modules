package io.colens.mcp.gateway.tools

import com.inductiveautomation.ignition.common.browsing.BrowseFilter
import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonElement
import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.ignition.common.model.values.BasicQualifiedValue
import com.inductiveautomation.ignition.common.model.values.QualifiedValue
import com.inductiveautomation.ignition.common.model.values.QualityCode
import com.inductiveautomation.ignition.common.tags.TagUtilities
import com.inductiveautomation.ignition.common.tags.browsing.NodeDescription
import com.inductiveautomation.ignition.common.tags.config.BasicTagConfiguration
import com.inductiveautomation.ignition.common.tags.config.CollisionPolicy
import com.inductiveautomation.ignition.common.tags.config.properties.WellKnownTagProps
import com.inductiveautomation.ignition.common.tags.model.TagPath
import com.inductiveautomation.ignition.common.tags.model.SecurityContext
import com.inductiveautomation.ignition.common.tags.model.TagProvider
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.McpJson
import io.colens.mcp.common.SnapshotStore
import io.colens.mcp.common.Severity
import io.colens.mcp.common.Tool
import io.colens.mcp.common.findingsJson
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonArrayOfStrings
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.optArray
import io.colens.mcp.common.optBoolean
import io.colens.mcp.common.optInt
import io.colens.mcp.common.optString
import io.colens.mcp.common.put
import io.colens.mcp.common.requireString
import io.colens.mcp.common.requireStringList
import io.colens.mcp.common.schema
import io.colens.mcp.common.tags.NoTagPropertyCatalog
import io.colens.mcp.common.tags.TagConfigValidator
import io.colens.mcp.common.tags.TagPropertyCatalog
import io.colens.mcp.common.toJsonValue
import java.util.concurrent.TimeUnit

class TagTools(private val context: GatewayContext) {

    /**
     * Pre-edit backups for the four tools that change tag *configuration*.
     *
     * The lambda is not evaluated here: the documentation generator constructs this class against a
     * stub context and fails the build if construction touches it.
     */
    private val snapshots = SnapshotStore(rootProvider = {
        SnapshotStore.resolveRoot { context.systemManager.dataDir.toPath().resolve(BACKUP_DIR) }
    })

    fun tools(): List<Tool> = listOf(
        listTagProviders(),
        browseTags(),
        readTags(),
        getTagConfig(),
        writeTags(),
        configureTags(),
        deleteTags(),
        renameTag(),
        importTags(),
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

            val outcomes = LinkedHashMap<Int, QualityCode>()
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
                    indexed.forEachIndexed { i, iv -> qualities.getOrNull(i)?.let { outcomes[iv.index] = it } }
                }

            jsonObject {
                put("results", jsonArrayOf(requests.mapIndexed { i, (path, value) ->
                    jsonObject {
                        put("path", path.toString())
                        put("value", toJsonValue(value.value))
                        put("quality", outcomes[i]?.toString())
                        // isGood, not a substring match on the name: a quality merely *containing*
                        // "Good" is not necessarily good.
                        put("ok", outcomes[i]?.isGood ?: false)
                    }
                }))
            }
        },
    )

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    private fun configureTags() = Tool(
        name = "configure_tags",
        title = "Create or edit tag configuration",
        description = "Creates or edits tags, UDT definitions and UDT instances. Each entry in " +
            "'tags' is a configuration object in the same shape get_tag_config returns, carrying " +
            "its own 'name'; 'parentPath' is the folder they go into. Write UDT definitions to " +
            "the provider's _types_ folder, e.g. parentPath '[default]_types_'.\n\n" +
            "Collisions default to MergeOverwrite: properties you send are applied and properties " +
            "you leave out keep their current values, so editing one setting does not require " +
            "sending the whole tag. Use Overwrite only when you mean to REPLACE the configuration " +
            "— it drops every property you did not send, including alarms and history settings. " +
            "Use Abort to refuse to touch anything that already exists.\n\n" +
            "The configuration is validated first and the whole call is refused if anything is " +
            "wrong, because Ignition's own parser accepts several broken inputs silently — most " +
            "notably a UDT parameter given a value but no dataType, which it discards while " +
            "reporting success.\n\n" +
            "Validation is not the only way a tag can fail. The provider can still refuse an " +
            "individual one — an Abort collision being the ordinary case — so compare 'written' " +
            "against 'attempted', or read 'ok' per entry in 'results'. A refusal leaves that " +
            "tag exactly as it was.",
        inputSchema = schema {
            string(
                "parentPath",
                "Folder the tags are created in, e.g. '[default]Area1', '[default]' for the " +
                    "provider root, or '[default]_types_' for UDT definitions.",
                required = true,
            )
            array(
                name = "tags",
                description = "Tag configuration objects, each with its own 'name'.",
                items = jsonObject { put("type", "object") },
                required = true,
            )
            enumString(
                "collisionPolicy",
                "What to do when a tag already exists at that path.",
                values = COLLISION_POLICIES,
                default = "MergeOverwrite",
            )
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val parentPath = tagPath(args.requireString("parentPath"), null)
            val provider = tagProvider(parentPath)
            val policy = collisionPolicy(args.optString("collisionPolicy"))

            val configs = (args.optArray("tags") ?: throw McpArgumentException("Missing required argument 'tags'"))
                .map {
                    it.takeIf { e -> e.isJsonObject }?.asJsonObject
                        ?: throw McpArgumentException("Every entry in 'tags' must be an object")
                }
            if (configs.isEmpty()) throw McpArgumentException("'tags' must not be empty")

            val findings = TagConfigValidator(propertyCatalog(provider)).validateAll(configs)

            if (findings.any { it.severity == Severity.ERROR }) {
                jsonObject {
                    put("parentPath", parentPath.toString())
                    put("written", 0)
                    put("note", "Nothing was written. Fix the errors below and call again.")
                    findingsJson(findings).entrySet().forEach { (k, v) -> add(k, v) }
                }
            } else {
                val tagConfigs = configs.flatMap { config ->
                    try {
                        TagUtilities.toTagConfiguration(McpJson.toString(config), parentPath)
                    } catch (e: Exception) {
                        throw McpArgumentException(
                            "Could not read the configuration for " +
                                "'${config.get("name")?.asString ?: "an unnamed tag"}': ${e.message}"
                        )
                    }
                }

                // After validation, before the write: a payload that was going to be rejected
                // anyway does not deserve a backup, and the write must not happen if one fails.
                backup(
                    configs.mapNotNull { it.get("name")?.takeIf { n -> n.isJsonPrimitive }?.asString }
                        .map { tagPath("$parentPath/$it", null) }
                )

                val qualities = provider
                    .saveTagConfigsAsync(tagConfigs, policy, SecurityContext.systemContext())
                    .get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                jsonObject {
                    put("parentPath", parentPath.toString())
                    put("provider", parentPath.source)
                    put("collisionPolicy", policy.name)
                    // Actually written, not attempted. These differ whenever the provider refuses
                    // an individual tag — an Abort collision being the ordinary case — and a
                    // caller asserting written == tags.size would otherwise read a total refusal
                    // as a complete success.
                    put("written", qualities.count { it.isGood })
                    put("attempted", tagConfigs.size)
                    put("results", jsonArrayOf(tagConfigs.mapIndexed { i, config ->
                        jsonObject {
                            put("path", config.path?.toString())
                            put("quality", qualities.getOrNull(i)?.toString())
                            put("ok", qualities.getOrNull(i)?.isGood)
                        }
                    }))
                    findingsJson(findings).entrySet().forEach { (k, v) -> add(k, v) }
                }
            }
        },
    )

    private fun deleteTags() = Tool(
        name = "delete_tags",
        title = "Delete tags",
        description = "Removes tags, folders or UDT definitions and everything under them. " +
            "Deleting a UDT definition breaks every instance of it, so check with browse_tags " +
            "first. This cannot be undone from here.",
        inputSchema = schema {
            stringArray("paths", "Tag paths to delete, e.g. '[default]Area1/Old'.", required = true)
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val paths = args.requireStringList("paths").map { tagPath(it, null) }

            // The description says this cannot be undone from here. With the backup it can: the
            // file this writes is an import_tags payload for the whole subtree.
            backup(paths)

            // Same index-preserving grouping as write_tags: one call per provider, results
            // written back into the caller's original order.
            val outcomes = LinkedHashMap<Int, QualityCode>()
            paths.withIndex().groupBy { it.value.source }.forEach { (source, indexed) ->
                val qualities = tagProvider(source)
                    .removeTagConfigsAsync(indexed.map { it.value }, SecurityContext.systemContext())
                    .get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                indexed.forEachIndexed { i, iv -> qualities.getOrNull(i)?.let { outcomes[iv.index] = it } }
            }

            jsonObject {
                put("deleted", outcomes.count { it.value.isGood })
                put("results", jsonArrayOf(paths.mapIndexed { i, path ->
                    jsonObject {
                        put("path", path.toString())
                        put("quality", outcomes[i]?.toString())
                        put("ok", outcomes[i]?.isGood ?: false)
                    }
                }))
            }
        },
    )

    private fun renameTag() = Tool(
        name = "rename_tag",
        title = "Rename a tag",
        description = "Renames a tag in place. This is a real rename rather than a delete and " +
            "recreate, so tag history and UDT instance membership survive it — but every " +
            "reference to the old path, in bindings, scripts and other tags, breaks.",
        inputSchema = schema {
            string("path", "Tag path to rename, e.g. '[default]Area1/Pmp1'.", required = true)
            string("newName", "The new name. Not a path — the tag stays where it is.", required = true)
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val path = tagPath(args.requireString("path"), null)
            val newName = args.requireString("newName")

            if (!TagUtilities.isValidName(newName)) {
                throw McpArgumentException(
                    "'$newName' is not a valid tag name. Names cannot contain path characters " +
                        "such as / \\ . [ ] — pass a name, not a path."
                )
            }

            backup(listOf(path))

            // Abort rather than the usual default: renaming onto a name that is already taken
            // should fail loudly, not overwrite whatever was there.
            val quality = tagProvider(path)
                .saveTagConfigsAsync(
                    listOf(renameConfig(path, newName)),
                    CollisionPolicy.Abort,
                    SecurityContext.systemContext(),
                )
                .get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .firstOrNull()

            jsonObject {
                put("path", path.toString())
                put("newName", newName)
                put("quality", quality?.toString())
                put("ok", quality?.isGood ?: false)
            }
        },
    )

    private fun importTags() = Tool(
        name = "import_tags",
        title = "Import a tag export",
        description = "Applies a whole Designer-style tag export in one call. Use it to move a " +
            "folder or a type library; for individual tags configure_tags is easier to get " +
            "right, since it validates each one and reports per-tag results.\n\n" +
            "Pass exactly what system.tag.exportTags produced, unchanged, and do NOT reshape it into " +
            "configure_tags' 'tags' array — the two tools take deliberately different shapes. " +
            "Note the export shape depends on how many paths were exported: one path yields a " +
            "bare object, several yield an object wrapping a 'tags' array. This tool accepts " +
            "both, which is exactly why passing it through untouched is the rule.\n\n" +
            "Check 'imported' against 'total' rather than 'findings'. A malformed payload fails " +
            "in Ignition's parser rather than in validation, so it surfaces in 'qualities' and " +
            "leaves 'findings' empty — a response that looks clean apart from imported being 0. " +
            "Nothing is partially written when that happens.",
        inputSchema = schema {
            string(
                "parentPath",
                "Folder to import into, e.g. '[default]Area1' or '[default]' for the root.",
                required = true,
            )
            string(
                "json",
                "The tag export JSON, as a string, exactly as system.tag.exportTags emitted it.",
                required = true,
            )
            enumString(
                "collisionPolicy",
                "What to do where the export overlaps existing tags.",
                values = COLLISION_POLICIES,
                default = "MergeOverwrite",
            )
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val parentPath = tagPath(args.requireString("parentPath"), null)
            val json = args.requireString("json")
            val policy = collisionPolicy(args.optString("collisionPolicy"))

            // The whole destination folder, because an import can create, overwrite or merge
            // anything beneath it and the payload alone does not say which.
            backup(listOf(parentPath))

            val qualities = tagProvider(parentPath)
                .importTagsAsync(parentPath, json, IMPORT_FORMAT, policy, SecurityContext.systemContext())
                .get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            jsonObject {
                put("parentPath", parentPath.toString())
                put("collisionPolicy", policy.name)
                put("imported", qualities.count { it.isGood })
                put("total", qualities.size)
                put("qualities", jsonArrayOfStrings(qualities.map { it.toString() }))
            }
        },
    )

    private fun collisionPolicy(name: String?): CollisionPolicy {
        if (name == null) return CollisionPolicy.MergeOverwrite
        return CollisionPolicy.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw McpArgumentException(
                "Unknown collisionPolicy '$name'. Use one of: ${COLLISION_POLICIES.joinToString(", ")}."
            )
    }

    /**
     * The provider's own list of valid tag property names, which the validator uses to tell a
     * typo from a deliberate custom property. Degrades to no catalog rather than failing the
     * call — a missing property model should cost you two rules, not the whole tool.
     */
    private fun propertyCatalog(provider: TagProvider): TagPropertyCatalog =
        runCatching {
            val names = provider.tagConfigModelAsync
                .get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .modelProperties
                .mapNotNull { it.name }
                .toSet()
            if (names.isEmpty()) NoTagPropertyCatalog else TagPropertyCatalog { names }
        }.getOrDefault(NoTagPropertyCatalog)

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

    /**
     * Backs up every path a config-changing tool is about to touch, and throws if it cannot.
     *
     * Deliberately **not** applied to `write_tags`. That writes tag *values*, and a configuration
     * export restores none of them — offering a backup there would be a promise this cannot keep.
     * Live process values are not recoverable from this module at all, which is worth knowing
     * before calling it rather than after.
     */
    private fun backup(paths: List<TagPath>) {
        paths.distinctBy { it.toString() }.forEach { path ->
            snapshots.snapshotOnce(
                key = "tag:$path",
                category = SnapshotStore.TAGS,
                label = path.toString(),
            ) { exportOf(path) }
        }
    }

    /**
     * The rename edit for [path], carrying the tag's own configuration with it.
     *
     * `BasicTagConfiguration.createRename` is `createEdit` plus a `Name` property and nothing else,
     * which reads like a partial edit the provider will merge. It isn't: saving it moves the tag and
     * leaves it with only `name` and `tagType`, dropping `dataType`, the value, history, alarms and
     * every other property — reported as `Good`, on both 8.1.43 and 8.3.7. That silent loss is the
     * whole reason this builds the config itself.
     *
     * The fix is to send the properties the tag already has, so the save has nothing to drop
     * whether it merges or replaces. [TagConfigurationModel.getLocalConfiguration] is the right
     * source: it excludes properties inherited from a UDT definition, which belong to the
     * definition and would be wrongly baked into the instance if copied here.
     *
     * Falls back to the bare rename when the tag cannot be read. That is the pre-existing
     * behaviour, and a rename that loses properties still beats refusing to rename at all — the
     * pre-edit backup taken just before this covers the loss either way.
     */
    private fun renameConfig(path: TagPath, newName: String) =
        runCatching {
            tagProvider(path)
                .getTagConfigsAsync(listOf(path), false, true)
                .get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .firstOrNull()
                ?.localConfiguration
                ?.let { local ->
                    BasicTagConfiguration.createEdit(path, local).apply {
                        set(WellKnownTagProps.Name, newName)
                    }
                }
        }.getOrNull() ?: BasicTagConfiguration.createRename(path, newName)

    /**
     * The subtree at [path] in the shape `import_tags` accepts, so restoring is a call this module
     * already offers rather than a Designer round trip.
     *
     * Always the wrapped `{"tags": [...]}` form: `import_tags` takes either, and one shape means a
     * restore never depends on how many tags happened to be under the path.
     *
     * Null when the provider returns nothing — a tag being created for the first time has no prior
     * state, which is not a failure and must not block the write.
     */
    private fun exportOf(path: TagPath): String? {
        val models = tagProvider(path)
            .getTagConfigsAsync(listOf(path), true, false)
            .get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (models.isEmpty()) return null

        val configs = JsonArray()
        models.forEach { configs.add(TagUtilities.toJsonObject(it)) }
        return McpJson.toPrettyString(jsonObject { put("tags", configs) })
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
        /** Under the gateway data directory, so backups travel with a gateway backup. */
        const val BACKUP_DIR = "mcp-backups"

        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L

        val COLLISION_POLICIES = CollisionPolicy.entries.map { it.name }

        /**
         * `importTagsAsync`'s format argument. Matches `system.tag.exportTags`' own — the other
         * value there is "xml" — and confirmed end to end on 8.3.8: export a UDT, delete it,
         * import the bytes back, and the persisted file is byte-identical to the original.
         */
        const val IMPORT_FORMAT = "json"
    }
}
