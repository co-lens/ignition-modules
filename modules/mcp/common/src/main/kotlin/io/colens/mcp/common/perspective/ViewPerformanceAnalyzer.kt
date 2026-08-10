package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonElement
import com.inductiveautomation.ignition.common.gson.JsonObject
import io.colens.mcp.common.Finding
import io.colens.mcp.common.Severity

/**
 * What a view costs to run, and the specific things making it cost that.
 *
 * Separate from [ViewValidator] on purpose. `PerspectiveEditTools.edit` refuses to write a view
 * when validation reports a single [Severity.ERROR]; folding performance rules in there would mean
 * any edit to an already-slow view gets rejected, which is not the tool's job. So: its own class,
 * its own tool, and every finding a [Severity.WARNING].
 *
 * It reuses [Finding] verbatim so the JSON a caller sees matches `perspective_validate_view`
 * exactly — including `fix`, which carries most of the value: a model reading this report is about
 * to change something.
 *
 * The rules are written against Perspective's own binding constants rather than guesses. Worth
 * knowing, because it is the opposite of what people assume: **tag bindings do not poll.** They
 * subscribe, and their config has no rate at all. Only `query`, `http` and `tag-history` bindings
 * poll, via a shared `config.polling` block whose `rate` is an *expression string* in **seconds**
 * (Perspective's own default is `"30"`).
 */
class ViewPerformanceAnalyzer(private val budgets: Budgets = Budgets()) {

    /**
     * Thresholds, all caller-overridable. A one-second poll is unremarkable on a five-component
     * status view and ruinous on a view rendered forty times by a repeater, so there is no
     * defensible fixed number — these are starting points, not verdicts.
     */
    data class Budgets(
        val minPollSeconds: Int = 5,
        val components: Int = 150,
        val bindings: Int = 200,
        val depth: Int = 12,
        val scriptTransforms: Int = 2,
        val embeddedViews: Int = 10,
    )

    data class Analysis(
        val componentCount: Int,
        val bindingCount: Int,
        val polledBindingCount: Int,
        val scriptTransformCount: Int,
        val eventCount: Int,
        val maxDepth: Int,
        /** Every view this one pulls in, in document order. */
        val embeddedViews: List<String>,
        /** The subset rendered many times over — by a repeater or a carousel. */
        val repeats: List<Repeat>,
        val findings: List<Finding>,
    )

    /** One place where this view renders another view more than once. */
    data class Repeat(val componentPath: String, val viewPath: String, val instances: Int?)

    fun analyze(view: ViewDocument): Analysis {
        val findings = mutableListOf<Finding>()
        var componentCount = 0
        var bindingCount = 0
        var polledCount = 0
        var scriptTransformCount = 0
        var eventCount = 0
        var maxDepth = 0
        val embedded = mutableListOf<String>()
        val repeats = mutableListOf<Repeat>()

        // The view's own propConfig never appears in walk(), which starts at `root`. View-level
        // bindings are ordinary bindings and cost the same, so they are collected separately.
        view.json().getAsJsonObjectOrNull("propConfig")?.let { propConfig ->
            bindings(propConfig).forEach { (key, binding) ->
                bindingCount++
                if (inspectBinding(binding, key, "view", findings)) polledCount++
                scriptTransformCount += scriptTransforms(binding)
            }
        }

        view.walk { node, path ->
            componentCount++
            maxDepth = maxOf(maxDepth, path.count { it == '/' })
            eventCount += countEvents(node)

            node.getAsJsonObjectOrNull("propConfig")?.let { propConfig ->
                bindings(propConfig).forEach { (key, binding) ->
                    bindingCount++
                    if (inspectBinding(binding, key, path, findings)) polledCount++
                    scriptTransformCount += scriptTransforms(binding)
                }
            }

            val type = view.typeOf(node)
            val referenced = referencedViews(type, node)
            embedded += referenced
            if (type in REPEATING_TYPES) {
                val instances = instanceCount(type, node)
                referenced.forEach { repeats += Repeat(path, it, instances) }
            }
        }

        if (componentCount > budgets.components) {
            findings += Finding(
                ViewDocument.ROOT, "heavy_view", Severity.WARNING,
                "View has $componentCount components (budget ${budgets.components}). Every one of " +
                    "them is instantiated on the gateway and serialized to the session on open.",
                "Split the view, or move rarely-seen parts behind an embedded view that only " +
                    "mounts when shown.",
            )
        }
        if (bindingCount > budgets.bindings) {
            findings += Finding(
                ViewDocument.ROOT, "heavy_view", Severity.WARNING,
                "View has $bindingCount bindings (budget ${budgets.bindings}). Each is an " +
                    "independent subscription or evaluation held for as long as the view is open.",
                "Consolidate related bindings onto one custom property and derive the rest from it " +
                    "with property bindings, which are far cheaper than tag or query bindings.",
            )
        }
        if (maxDepth > budgets.depth) {
            findings += Finding(
                ViewDocument.ROOT, "deep_nesting", Severity.WARNING,
                "Component tree is $maxDepth levels deep (budget ${budgets.depth}).",
                "Deep container nesting multiplies layout work in the browser. Flatten with a " +
                    "flex or column container where the extra levels are only there for spacing.",
            )
        }
        if (embedded.size > budgets.embeddedViews) {
            findings += Finding(
                ViewDocument.ROOT, "embedded_view_fanout", Severity.WARNING,
                "View embeds ${embedded.size} other views (budget ${budgets.embeddedViews}): " +
                    "${embedded.distinct().sorted().joinToString(", ")}. Each one is a separate " +
                    "resource load and its own component tree.",
                "Check whether the embedded views are needed on open, or can be deferred behind a " +
                    "tab, a dropdown or a conditional.",
            )
        }

        return Analysis(
            componentCount = componentCount,
            bindingCount = bindingCount,
            polledBindingCount = polledCount,
            scriptTransformCount = scriptTransformCount,
            eventCount = eventCount,
            maxDepth = maxDepth,
            embeddedViews = embedded,
            repeats = repeats,
            findings = findings,
        )
    }

    /**
     * A view rendered many times over by a repeater or carousel elsewhere.
     *
     * This is the one cost that cannot be seen from inside a single view, and it is usually the
     * largest: the analysed view's bindings are all created once *per instance*. Reported against
     * the view that does the repeating, since that is where the instance count is configured.
     */
    fun repeatedViewFinding(repeat: Repeat, target: Analysis): Finding? {
        if (target.bindingCount == 0) return null
        val multiplier = repeat.instances?.takeIf { it > 0 }
        val total = multiplier?.let { it * target.bindingCount }
        return Finding(
            repeat.componentPath, "repeated_view_cost", Severity.WARNING,
            buildString {
                append("Repeats '${repeat.viewPath}', which carries ")
                append("${target.bindingCount} bindings and ${target.componentCount} components. ")
                if (total != null) {
                    append("At $multiplier instances that is roughly $total bindings from this ")
                    append("component alone.")
                } else {
                    append("The instance count is set at runtime, so the real cost is that many ")
                    append("times over.")
                }
            },
            "Bindings inside a repeated view are the most expensive thing in Perspective. Move " +
                "what you can to the host view and pass results down as view parameters, which " +
                "cost nothing per instance.",
        )
    }

    // -----------------------------------------------------------------------
    // Bindings
    // -----------------------------------------------------------------------

    private fun bindings(propConfig: JsonObject): List<Pair<String, JsonObject>> =
        propConfig.entrySet().mapNotNull { (key, entry) ->
            entry.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.getAsJsonObjectOrNull("binding")
                ?.let { key to it }
        }

    /** Runs every binding-level rule. Returns true when this binding polls. */
    private fun inspectBinding(
        binding: JsonObject,
        propertyKey: String,
        path: String,
        findings: MutableList<Finding>,
    ): Boolean {
        val type = binding.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        val config = binding.getAsJsonObjectOrNull("config")
        val polling = config?.getAsJsonObjectOrNull("polling")
        val pollEnabled = polling?.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        val rateSeconds = polling?.let { rateSeconds(it) }

        if (pollEnabled && rateSeconds != null && rateSeconds < budgets.minPollSeconds) {
            findings += Finding(
                path, "fast_polling_binding", Severity.WARNING,
                "Binding on '$propertyKey' (type '$type') polls every ${trim(rateSeconds)}s, " +
                    "under the ${budgets.minPollSeconds}s budget. This runs per open session, " +
                    "forever, whether or not the value changed.",
                if (type == "query") {
                    "Slow the poll rate, or drive the query from a tag change instead: bind the " +
                        "query's parameter to a tag and let the subscription decide when to re-run."
                } else {
                    "Slow the poll rate, or switch to an event-driven source if one exists."
                },
            )
        }

        // Value caching is per query-and-parameters and shared across sessions. Turning it off
        // under a poll means every session hits the database on every tick.
        if (pollEnabled && type in CACHEABLE_TYPES && config != null) {
            val cacheOff = config.get("enableValueCache")?.takeIf { it.isJsonPrimitive }?.asBoolean == false
            val bypass = config.get("bypassCache")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            if (cacheOff || bypass) {
                findings += Finding(
                    path, "polled_query_uncached", Severity.WARNING,
                    "Polling binding on '$propertyKey' has the value cache disabled, so every " +
                        "open session issues its own request on every poll rather than sharing " +
                        "one result.",
                    "Leave 'enableValueCache' on unless the binding genuinely needs a per-session " +
                        "result; the cache is keyed by query and parameters, so identical calls " +
                        "already share correctly.",
                )
            }
        }

        if (type in EXPRESSION_TYPES && config != null) {
            expressionFindings(config, propertyKey, path, findings)
        }

        val scriptTransforms = scriptTransforms(binding)
        if (scriptTransforms >= budgets.scriptTransforms) {
            findings += Finding(
                path, "script_transform_chain", Severity.WARNING,
                "Binding on '$propertyKey' runs $scriptTransforms script transforms in sequence. " +
                    "Each one enters the scripting runtime on every evaluation.",
                "Collapse them into a single transform, or replace the ones doing simple shaping " +
                    "with expression or map transforms, which do not touch Jython.",
            )
        } else if (scriptTransforms > 0 && pollEnabled) {
            findings += Finding(
                path, "script_transform_chain", Severity.WARNING,
                "Polling binding on '$propertyKey' runs a script transform, so Jython executes on " +
                    "every poll for every session.",
                "Move the work into the query or expression itself, or slow the poll rate.",
            )
        }

        return pollEnabled
    }

    /**
     * The expression functions that quietly turn an event-driven binding into a polling one, or
     * into a read that bypasses subscriptions entirely.
     */
    private fun expressionFindings(
        config: JsonObject,
        propertyKey: String,
        path: String,
        findings: MutableList<Finding>,
    ) {
        // `expr-struct` nests expressions arbitrarily deep inside its config, so rather than
        // guessing the shape, every string leaf is treated as a candidate expression.
        val expressions = stringLeaves(config)

        expressions.forEach { expression ->
            NOW_CALL.find(expression)?.let { match ->
                val millis = match.groupValues[1].trim().toIntOrNull()
                // now(0) is the documented way to ask for a non-polling timestamp.
                if (millis != 0) {
                    findings += Finding(
                        path, "expensive_expression", Severity.WARNING,
                        "Expression on '$propertyKey' calls now(${match.groupValues[1].trim()}), " +
                            "which re-evaluates the whole binding on a timer " +
                            "${if (millis == null) "every second by default" else "every ${millis}ms"} " +
                            "for as long as the view is open.",
                        "If you only need the time the value was produced, use now(0) — it reads " +
                            "the clock once instead of installing a poll.",
                    )
                }
            }

            if (RUN_SCRIPT_CALL.containsMatchIn(expression)) {
                findings += Finding(
                    path, "expensive_expression", Severity.WARNING,
                    "Expression on '$propertyKey' calls runScript(), which enters the scripting " +
                        "runtime on every evaluation and polls on its own rate argument.",
                    "Prefer a property or tag binding with a script transform, so the script runs " +
                        "when the source actually changes rather than on a timer.",
                )
            }

            if (TAG_CALL.containsMatchIn(expression)) {
                findings += Finding(
                    path, "expensive_expression", Severity.WARNING,
                    "Expression on '$propertyKey' calls tag(), which performs an unsubscribed read " +
                        "each time the expression evaluates.",
                    "Use a tag binding instead — it subscribes once and is pushed updates, rather " +
                        "than reading on demand.",
                )
            }
        }
    }

    private fun scriptTransforms(binding: JsonObject): Int =
        binding.getAsJsonArrayOrNull("transforms")
            ?.count { it.isJsonObject && it.asJsonObject.get("type")?.asStringOrNull() == "script" }
            ?: 0

    /**
     * `polling.rate` is an expression, so it is a plain number only some of the time. When it is
     * `{view.params.rate}` or similar there is nothing to judge and the rule stays quiet.
     */
    private fun rateSeconds(polling: JsonObject): Double? {
        val rate = polling.get("rate") ?: return null
        if (!rate.isJsonPrimitive) return null
        return rate.asStringOrNull()?.trim()?.toDoubleOrNull()
    }

    // -----------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------

    /** View paths this component pulls in: embedded views, repeater targets, carousel slides. */
    private fun referencedViews(type: String?, node: JsonObject): List<String> {
        if (type !in VIEW_HOSTING_TYPES) return emptyList()
        val props = node.getAsJsonObjectOrNull("props") ?: return emptyList()

        val direct = props.get("path")?.asStringOrNull()?.takeIf { it.isNotBlank() }
        val carousel = props.getAsJsonArrayOrNull("views")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.get("viewPath")?.asStringOrNull() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return listOfNotNull(direct) + carousel
    }

    /**
     * How many copies a repeater renders, when that is configured statically. A bound instance
     * list is decided at runtime and returns null rather than a wrong number.
     */
    private fun instanceCount(type: String?, node: JsonObject): Int? {
        val props = node.getAsJsonObjectOrNull("props") ?: return null
        val key = if (type == "ia.display.carousel") "views" else "instances"
        return props.getAsJsonArrayOrNull(key)?.size()
    }

    private fun countEvents(node: JsonObject): Int =
        node.getAsJsonObjectOrNull("events")
            ?.entrySet()
            ?.sumOf { (_, group) -> if (group.isJsonObject) group.asJsonObject.size() else 0 }
            ?: 0

    private fun stringLeaves(element: JsonElement): List<String> = when {
        element.isJsonPrimitive -> element.asStringOrNull()?.let { listOf(it) }.orEmpty()
        element.isJsonObject -> element.asJsonObject.entrySet().flatMap { stringLeaves(it.value) }
        element.isJsonArray -> element.asJsonArray.flatMap { stringLeaves(it) }
        else -> emptyList()
    }

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    companion object {
        /** Verified against Perspective's own `*BindingConstants` type ids. */
        private val EXPRESSION_TYPES = setOf("expr", "expr-struct")
        private val CACHEABLE_TYPES = setOf("query", "http")

        val REPEATING_TYPES = setOf("ia.display.view-repeater", "ia.display.carousel")
        val VIEW_HOSTING_TYPES = REPEATING_TYPES + setOf("ia.display.view")

        // The argument is captured so now(0) — an explicit request for no polling — can be
        // distinguished from now(), which installs a one-second timer.
        private val NOW_CALL = Regex("""\bnow\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
        private val RUN_SCRIPT_CALL = Regex("""\brunScript\s*\(""", RegexOption.IGNORE_CASE)
        private val TAG_CALL = Regex("""\btag\s*\(""", RegexOption.IGNORE_CASE)
    }
}

private fun JsonElement.asStringOrNull(): String? =
    takeIf { it.isJsonPrimitive }?.asString
