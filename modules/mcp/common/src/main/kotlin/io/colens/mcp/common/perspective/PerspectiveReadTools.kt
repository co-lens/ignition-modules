package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonObject
import io.colens.mcp.common.Finding
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.Tool
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonArrayOfStrings
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.optInt
import io.colens.mcp.common.optString
import io.colens.mcp.common.put
import io.colens.mcp.common.requireString
import io.colens.mcp.common.schema

/**
 * The Perspective tools that only read. Built once here and registered by both scopes, so a model
 * that edits a view in the Designer reads it back through exactly the same shapes it would see
 * from the gateway.
 */
class PerspectiveReadTools(
    private val source: ViewSource,
    private val catalog: ComponentCatalog,
) {

    fun tools(): List<Tool> = listOf(
        listViews(),
        getView(),
        getComponent(),
        listComponentTypes(),
        getComponentType(),
        validateView(),
        analyzePerformance(),
    )

    private fun projectArg(builder: io.colens.mcp.common.SchemaBuilder) {
        if (source.requiresProject) {
            builder.string("project", "Project name.", required = true)
        } else {
            builder.string("project", "Ignored — the Designer operates on the open project.")
        }
    }

    private fun listViews() = Tool(
        name = "perspective_list_views",
        title = "List Perspective views",
        description = "Lists the Perspective views in a project, with the number of components in each.",
        inputSchema = schema {
            projectArg(this)
            string("pathContains", "Only return views whose path contains this substring.")
        },
        handler = { args ->
            val project = source.resolveProject(args.optString("project"))
            val filter = args.optString("pathContains")?.lowercase()

            val views = source.listViews(project)
                .filter { filter == null || it.path.lowercase().contains(filter) }
                .sortedBy { it.path }

            jsonObject {
                put("project", project)
                put("count", views.size)
                put("views", jsonArrayOf(views.map { ref ->
                    jsonObject {
                        put("view", ref.path)
                        put("sizeBytes", ref.sizeBytes)
                        put("componentCount", runCatching {
                            source.read(project, ref.path).componentCount()
                        }.getOrNull())
                    }
                }))
            }
        },
    )

    private fun getView() = Tool(
        name = "perspective_get_view",
        title = "Get a Perspective view",
        description = "Returns a view's parameters, view-level custom properties and its component " +
            "tree. Each tree entry carries the path you pass to the other perspective_ tools, plus " +
            "which properties are bound and how many events are attached. Start here before editing.",
        inputSchema = schema {
            projectArg(this)
            string("view", "View path, e.g. 'Page/Main'.", required = true)
        },
        handler = { args ->
            val project = source.resolveProject(args.optString("project"))
            val viewPath = args.requireString("view")
            val doc = source.read(project, viewPath)

            jsonObject {
                put("project", project)
                put("view", viewPath)
                put("params", doc.json().getAsJsonObjectOrNull("params") ?: JsonObject())
                put("custom", doc.json().getAsJsonObjectOrNull("custom") ?: JsonObject())
                put("props", doc.json().getAsJsonObjectOrNull("props") ?: JsonObject())
                put("propConfig", doc.json().getAsJsonObjectOrNull("propConfig") ?: JsonObject())
                put("componentCount", doc.componentCount())
                put("components", doc.tree())
            }
        },
    )

    private fun getComponent() = Tool(
        name = "perspective_get_component",
        title = "Get a Perspective component",
        description = "Returns one component in full: type, props, custom properties, bindings " +
            "(from propConfig), events, position and the names of its children.",
        inputSchema = schema {
            projectArg(this)
            string("view", "View path, e.g. 'Page/Main'.", required = true)
            string("path", "Component path, e.g. 'root/FlexContainer/Label'.", required = true)
        },
        handler = { args ->
            val project = source.resolveProject(args.optString("project"))
            val viewPath = args.requireString("view")
            val componentPath = args.requireString("path")
            val doc = source.read(project, viewPath)
            val node = doc.component(componentPath)

            jsonObject {
                put("project", project)
                put("view", viewPath)
                put("path", componentPath)
                put("type", doc.typeOf(node))
                put("name", doc.nameOf(node))
                put("props", node.getAsJsonObjectOrNull("props") ?: JsonObject())
                put("custom", node.getAsJsonObjectOrNull("custom") ?: JsonObject())
                put("position", node.getAsJsonObjectOrNull("position") ?: JsonObject())
                put("propConfig", node.getAsJsonObjectOrNull("propConfig") ?: JsonObject())
                put("events", node.getAsJsonObjectOrNull("events") ?: JsonObject())
                put("boundProperties", jsonArrayOfStrings(doc.boundPropertyKeys(node)))
                put("children", jsonArrayOfStrings(
                    node.getAsJsonArrayOrNull("children")
                        ?.mapNotNull { if (it.isJsonObject) doc.nameOf(it.asJsonObject) else null }
                        .orEmpty()
                ))
            }
        },
    )

    private fun listComponentTypes() = Tool(
        name = "perspective_list_component_types",
        title = "List Perspective component types",
        description = "Lists the component types registered on this system, which is what " +
            "perspective_add_component will accept. Filter by category or a substring of the id.",
        inputSchema = schema {
            string("category", "Only return components in this palette category.")
            string("idContains", "Only return components whose id contains this substring.")
        },
        handler = { args ->
            val category = args.optString("category")
            val idContains = args.optString("idContains")?.lowercase()

            val types = catalog.componentTypes()
            if (types.isEmpty()) {
                throw McpArgumentException(
                    "Perspective's component registry is not available on this system."
                )
            }

            val entries = JsonArray()
            types.sorted().forEach { id ->
                if (idContains != null && !id.lowercase().contains(idContains)) return@forEach
                val info = catalog.describe(id)
                if (category != null && info?.category != category) return@forEach
                entries.add(jsonObject {
                    put("id", id)
                    put("name", info?.name)
                    put("category", info?.category)
                    put("deprecated", info?.deprecated)
                })
            }

            jsonObject {
                put("count", entries.size())
                put("totalRegistered", types.size)
                put("categories", jsonArrayOfStrings(catalog.categories().sorted()))
                put("components", entries)
            }
        },
    )

    private fun getComponentType() = Tool(
        name = "perspective_get_component_type",
        title = "Get a Perspective component type",
        description = "Returns a component type's default properties, events and extension " +
            "functions. 'defaultProperties' is the shape a new instance starts with and is the " +
            "best reference for what the component accepts; perspective_validate_view then " +
            "enforces the component's real JSON schema.",
        inputSchema = schema {
            string("typeId", "Component type id, e.g. 'ia.display.label'.", required = true)
        },
        handler = { args ->
            val typeId = args.requireString("typeId")
            val info = catalog.describe(typeId)
                ?: throw McpArgumentException(
                    "'$typeId' is not a registered component type. " +
                        "Call perspective_list_component_types to see what's available."
                )

            jsonObject {
                put("id", info.id)
                put("name", info.name)
                put("category", info.category)
                put("deprecated", info.deprecated)
                put("defaultMetaName", info.defaultMetaName)
                put("defaultProperties", info.defaultProperties)
                put("childPositionDefaults", info.childPositionDefaults)
                put("events", jsonArrayOfStrings(info.eventNames))
                put("extensionFunctions", jsonArrayOfStrings(info.extensionFunctionNames))
            }
        },
    )

    private fun validateView() = Tool(
        name = "perspective_validate_view",
        title = "Validate a Perspective view",
        description = "Checks a view against the component registry and Perspective's own JSON " +
            "schemas, and reports the authoring mistakes that break views silently — bindings left " +
            "in props instead of propConfig, 'bidirectional' at the wrong nesting level, and event " +
            "scripts missing their leading tab indentation. Each finding says how to fix it.",
        inputSchema = schema {
            projectArg(this)
            string("view", "View path, e.g. 'Page/Main'.", required = true)
        },
        handler = { args ->
            val project = source.resolveProject(args.optString("project"))
            val viewPath = args.requireString("view")
            val findings = ViewValidator(catalog).validate(source.read(project, viewPath))

            jsonObject {
                put("project", project)
                put("view", viewPath)
                put("catalogAvailable", catalog.componentTypes().isNotEmpty())
                ViewValidator.toJson(findings).entrySet().forEach { (k, v) -> add(k, v) }
            }
        },
    )

    /**
     * The project-wide sweep. Deliberately one tool rather than a per-view one: the most expensive
     * thing in Perspective is a view with many bindings rendered many times by a repeater somewhere
     * else, and that is invisible from inside either view on its own.
     */
    private fun analyzePerformance() = Tool(
        name = "perspective_analyze_performance",
        title = "Analyze Perspective view performance",
        description = "Sweeps a project's views and reports what each costs to open and to keep " +
            "open — components, bindings, how many of those poll, script transforms, nesting " +
            "depth and embedded views — alongside findings for the specific things that make a " +
            "view slow: fast polling, expressions that install their own timers, script transform " +
            "chains, uncached polled queries, and views repeated many times over by a repeater or " +
            "carousel. Heaviest views come first. Pass 'view' to analyze a single view instead.",
        inputSchema = schema {
            projectArg(this)
            string("view", "Analyze only this view, e.g. 'Page/Main'.")
            string("pathContains", "Only analyze views whose path contains this substring.")
            integer("limit", "Maximum number of views to analyze.", default = 100)
            integer(
                "minPollSeconds",
                "Flag polling bindings faster than this many seconds.",
                default = 5,
            )
            integer("componentBudget", "Flag views with more components than this.", default = 150)
            integer("bindingBudget", "Flag views with more bindings than this.", default = 200)
            integer("depthBudget", "Flag views nested deeper than this many levels.", default = 12)
        },
        handler = { args ->
            val project = source.resolveProject(args.optString("project"))
            val single = args.optString("view")
            val filter = args.optString("pathContains")?.lowercase()
            val limit = args.optInt("limit", 100).coerceAtLeast(1)

            val analyzer = ViewPerformanceAnalyzer(
                ViewPerformanceAnalyzer.Budgets(
                    minPollSeconds = args.optInt("minPollSeconds", 5),
                    components = args.optInt("componentBudget", 150),
                    bindings = args.optInt("bindingBudget", 200),
                    depth = args.optInt("depthBudget", 12),
                ),
            )

            val candidates = if (single != null) {
                listOf(ViewRef(single, null))
            } else {
                source.listViews(project)
                    .filter { filter == null || it.path.lowercase().contains(filter) }
                    .sortedBy { it.path }
            }
            val selected = candidates.take(limit)

            // One unparseable view must not sink the sweep — same tolerance as list_views.
            val skipped = mutableListOf<JsonObject>()
            val analyzed = LinkedHashMap<String, ViewPerformanceAnalyzer.Analysis>()
            val sizes = selected.associate { it.path to it.sizeBytes }

            selected.forEach { ref ->
                runCatching { analyzer.analyze(source.read(project, ref.path)) }
                    .onSuccess { analyzed[ref.path] = it }
                    .onFailure { e ->
                        skipped += jsonObject {
                            put("view", ref.path)
                            put("reason", e.message ?: e.javaClass.simpleName)
                        }
                    }
            }

            // Cross-view pass. A repeater's target is only judgeable once both views are in hand,
            // and a target outside the analyzed set simply yields no finding rather than a guess.
            val crossView = analyzed.mapValues { (_, analysis) ->
                analysis.repeats.mapNotNull { repeat ->
                    analyzed[repeat.viewPath]?.let { analyzer.repeatedViewFinding(repeat, it) }
                }
            }

            val rows = analyzed.entries.map { (path, analysis) ->
                path to (analysis.findings + crossView.getValue(path))
            }.sortedWith(
                compareByDescending<Pair<String, List<Finding>>> { it.second.size }
                    .thenByDescending { analyzed.getValue(it.first).bindingCount }
                    .thenBy { it.first },
            )

            val findingCounts = rows.flatMap { it.second }.groupingBy { it.code }.eachCount()

            jsonObject {
                put("project", project)
                put("viewsAnalyzed", analyzed.size)
                put("viewsSkipped", skipped.size)
                put("truncated", candidates.size > selected.size)
                put("totals", jsonObject {
                    put("components", analyzed.values.sumOf { it.componentCount })
                    put("bindings", analyzed.values.sumOf { it.bindingCount })
                    put("polledBindings", analyzed.values.sumOf { it.polledBindingCount })
                    put("scriptTransforms", analyzed.values.sumOf { it.scriptTransformCount })
                    put("findings", findingCounts.values.sum())
                })
                put("findingCounts", jsonObject {
                    findingCounts.toSortedMap().forEach { (code, n) -> put(code, n) }
                })
                put("views", jsonArrayOf(rows.map { (path, findings) ->
                    val analysis = analyzed.getValue(path)
                    jsonObject {
                        put("view", path)
                        put("sizeBytes", sizes[path])
                        put("componentCount", analysis.componentCount)
                        put("bindingCount", analysis.bindingCount)
                        put("polledBindingCount", analysis.polledBindingCount)
                        put("scriptTransformCount", analysis.scriptTransformCount)
                        put("eventCount", analysis.eventCount)
                        put("maxDepth", analysis.maxDepth)
                        put("embeddedViews", jsonArrayOfStrings(analysis.embeddedViews.distinct().sorted()))
                        put("findings", jsonArrayOf(findings.map { it.toJson() }))
                    }
                }))
                put("skipped", jsonArrayOf(skipped))
            }
        },
    )
}
