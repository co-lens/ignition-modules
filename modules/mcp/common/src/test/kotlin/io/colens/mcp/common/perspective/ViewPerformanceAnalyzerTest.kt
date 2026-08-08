package io.colens.mcp.common.perspective

import io.colens.mcp.common.Severity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ViewPerformanceAnalyzerTest : StringSpec({

    fun view(root: String) = ViewDocument.parse("""{ "root": $root }""")

    fun container(children: String) = """
        { "type": "ia.container.flex", "meta": { "name": "root" }, "children": [ $children ] }
    """

    /** A label whose `props.text` carries the given binding. */
    fun bound(binding: String) = """
        {
          "type": "ia.display.label", "meta": { "name": "L" },
          "propConfig": { "props.text": { "binding": $binding } }
        }
    """

    fun analyze(root: String, budgets: ViewPerformanceAnalyzer.Budgets = ViewPerformanceAnalyzer.Budgets()) =
        ViewPerformanceAnalyzer(budgets).analyze(view(root))

    fun codes(root: String, budgets: ViewPerformanceAnalyzer.Budgets = ViewPerformanceAnalyzer.Budgets()) =
        analyze(root, budgets).findings.map { it.code }

    // -- clean baseline -----------------------------------------------------

    "an ordinary view produces no findings" {
        val root = container(
            bound("""{ "type": "tag", "config": { "tagPath": "[default]A", "mode": "direct" } }""")
        )
        analyze(root).findings.shouldBeEmpty()
    }

    "every finding is a warning, so edits to a slow view are never blocked" {
        val root = container(
            bound("""{ "type": "query", "config": { "polling": { "enabled": true, "rate": "1" } } }""")
        )
        analyze(root).findings.map { it.severity }.toSet() shouldBe setOf(Severity.WARNING)
    }

    // -- polling ------------------------------------------------------------

    "a query binding polling faster than the budget is flagged" {
        val root = container(
            bound("""{ "type": "query", "config": { "polling": { "enabled": true, "rate": "1" } } }""")
        )
        codes(root) shouldContain "fast_polling_binding"
        analyze(root).polledBindingCount shouldBe 1
    }

    "a poll rate at or above the budget is left alone" {
        val root = container(
            bound("""{ "type": "query", "config": { "polling": { "enabled": true, "rate": "30" } } }""")
        )
        codes(root) shouldNotContain "fast_polling_binding"
    }

    "polling that is configured but disabled is not counted or flagged" {
        val root = container(
            bound("""{ "type": "query", "config": { "polling": { "enabled": false, "rate": "1" } } }""")
        )
        codes(root) shouldNotContain "fast_polling_binding"
        analyze(root).polledBindingCount shouldBe 0
    }

    "a poll rate given as an expression is not judged" {
        val root = container(
            bound(
                """{ "type": "query", "config": { "polling": { "enabled": true, "rate": "{view.params.rate}" } } }"""
            )
        )
        codes(root) shouldNotContain "fast_polling_binding"
    }

    "a tag binding is never treated as polling" {
        // Tag bindings subscribe; their config has no rate at all. Flagging one would be a lie.
        val root = container(
            bound("""{ "type": "tag", "config": { "tagPath": "[default]A", "mode": "direct" } }""")
        )
        analyze(root).polledBindingCount shouldBe 0
        analyze(root).findings.shouldBeEmpty()
    }

    "a polled query with the value cache disabled is flagged" {
        val root = container(
            bound(
                """
                { "type": "query", "config": {
                    "polling": { "enabled": true, "rate": "60" }, "enableValueCache": false } }
                """
            )
        )
        codes(root) shouldContain "polled_query_uncached"
    }

    "bypassCache on a polled query is flagged the same way" {
        val root = container(
            bound(
                """
                { "type": "query", "config": {
                    "polling": { "enabled": true, "rate": "60" }, "bypassCache": true } }
                """
            )
        )
        codes(root) shouldContain "polled_query_uncached"
    }

    "an uncached query that does not poll is left alone" {
        val root = container(
            bound("""{ "type": "query", "config": { "enableValueCache": false } }""")
        )
        codes(root) shouldNotContain "polled_query_uncached"
    }

    // -- expressions --------------------------------------------------------

    "a bare now() in an expression is flagged" {
        val root = container(
            bound("""{ "type": "expr", "config": { "expression": "dateFormat(now(), 'HH:mm')" } }""")
        )
        codes(root) shouldContain "expensive_expression"
        analyze(root).findings.first().message shouldContain "every second by default"
    }

    "now(0) is the documented non-polling form and is not flagged" {
        val root = container(
            bound("""{ "type": "expr", "config": { "expression": "dateFormat(now(0), 'HH:mm')" } }""")
        )
        codes(root).shouldBeEmpty()
    }

    "runScript in an expression is flagged" {
        val root = container(
            bound("""{ "type": "expr", "config": { "expression": "runScript('shared.f', 1000)" } }""")
        )
        codes(root) shouldContain "expensive_expression"
    }

    "tag() in an expression is flagged as an unsubscribed read" {
        val root = container(
            bound("""{ "type": "expr", "config": { "expression": "tag('[default]A')" } }""")
        )
        analyze(root).findings.first().message shouldContain "unsubscribed read"
    }

    "expressions nested inside an expr-struct config are still inspected" {
        val root = container(
            bound(
                """
                { "type": "expr-struct", "config": { "struct": {
                    "a": { "b": [ { "expression": "now()" } ] } } } }
                """
            )
        )
        codes(root) shouldContain "expensive_expression"
    }

    "expression functions are not hunted for in non-expression bindings" {
        // A tag path or a query path may legitimately contain these substrings.
        val root = container(
            bound("""{ "type": "tag", "config": { "tagPath": "[default]Plant/tag(1)" } }""")
        )
        codes(root).shouldBeEmpty()
    }

    // -- transforms ---------------------------------------------------------

    "a chain of script transforms is flagged" {
        val root = container(
            bound(
                """
                { "type": "tag", "config": { "tagPath": "[default]A" }, "transforms": [
                    { "type": "script", "code": "\treturn value" },
                    { "type": "script", "code": "\treturn value" } ] }
                """
            )
        )
        codes(root) shouldContain "script_transform_chain"
        analyze(root).scriptTransformCount shouldBe 2
    }

    "a single script transform on a polling binding is flagged" {
        val root = container(
            bound(
                """
                { "type": "query", "config": { "polling": { "enabled": true, "rate": "60" } },
                  "transforms": [ { "type": "script", "code": "\treturn value" } ] }
                """
            )
        )
        analyze(root).findings.map { it.code } shouldContain "script_transform_chain"
    }

    "a single script transform on an event-driven binding is not flagged" {
        val root = container(
            bound(
                """
                { "type": "tag", "config": { "tagPath": "[default]A" },
                  "transforms": [ { "type": "script", "code": "\treturn value" } ] }
                """
            )
        )
        codes(root).shouldBeEmpty()
    }

    "non-script transforms are not counted" {
        val root = container(
            bound(
                """
                { "type": "tag", "config": { "tagPath": "[default]A" }, "transforms": [
                    { "type": "expression" }, { "type": "map" }, { "type": "format" } ] }
                """
            )
        )
        analyze(root).scriptTransformCount shouldBe 0
        codes(root).shouldBeEmpty()
    }

    // -- weight -------------------------------------------------------------

    "component and binding counts include the whole tree" {
        val root = container("${bound("""{ "type": "tag", "config": {} }""")}, ${bound("""{ "type": "expr", "config": {} }""")}")
        val analysis = analyze(root)
        analysis.componentCount shouldBe 3 // root plus two labels
        analysis.bindingCount shouldBe 2
    }

    "a view-level binding is counted even though walk starts at root" {
        val doc = ViewDocument.parse(
            """
            {
              "propConfig": { "custom.total": { "binding": { "type": "expr", "config": { "expression": "now()" } } } },
              "root": { "type": "ia.container.flex", "meta": { "name": "root" } }
            }
            """
        )
        val analysis = ViewPerformanceAnalyzer().analyze(doc)
        analysis.bindingCount shouldBe 1
        analysis.findings.map { it.code } shouldContain "expensive_expression"
        analysis.findings.first().path shouldBe "view"
    }

    "too many components is flagged against the budget" {
        val root = container(List(5) { """{ "type": "ia.display.label", "meta": { "name": "L$it" } }""" }
            .joinToString(","))
        codes(root, ViewPerformanceAnalyzer.Budgets(components = 3)) shouldContain "heavy_view"
    }

    "nesting depth is measured from the component path" {
        val root = """
            { "type": "ia.container.flex", "meta": { "name": "root" }, "children": [
                { "type": "ia.container.flex", "meta": { "name": "A" }, "children": [
                    { "type": "ia.display.label", "meta": { "name": "B" } } ] } ] }
        """
        analyze(root).maxDepth shouldBe 2
        codes(root, ViewPerformanceAnalyzer.Budgets(depth = 1)) shouldContain "deep_nesting"
    }

    "embedded views are collected and counted against the fanout budget" {
        val root = container(
            (1..3).joinToString(",") {
                """{ "type": "ia.display.view", "meta": { "name": "V$it" }, "props": { "path": "Shared/W$it" } }"""
            }
        )
        analyze(root).embeddedViews shouldBe listOf("Shared/W1", "Shared/W2", "Shared/W3")
        codes(root, ViewPerformanceAnalyzer.Budgets(embeddedViews = 2)) shouldContain "embedded_view_fanout"
    }

    // -- repeated views (cross-view) ----------------------------------------

    "a repeater records its target and static instance count" {
        val root = container(
            """
            { "type": "ia.display.view-repeater", "meta": { "name": "R" },
              "props": { "path": "Shared/Row", "instances": [ {}, {}, {} ] } }
            """
        )
        val repeats = analyze(root).repeats
        repeats.size shouldBe 1
        repeats.first().viewPath shouldBe "Shared/Row"
        repeats.first().instances shouldBe 3
        repeats.first().componentPath shouldBe "root/R"
    }

    "a carousel repeats each of its configured views" {
        val root = container(
            """
            { "type": "ia.display.carousel", "meta": { "name": "C" },
              "props": { "views": [ { "viewPath": "A" }, { "viewPath": "B" } ] } }
            """
        )
        val analysis = analyze(root)
        analysis.repeats.map { it.viewPath } shouldBe listOf("A", "B")
        analysis.repeats.first().instances shouldBe 2
    }

    "a repeater with a bound instance list reports an unknown count rather than a wrong one" {
        val root = container(
            """
            { "type": "ia.display.view-repeater", "meta": { "name": "R" }, "props": { "path": "Shared/Row" },
              "propConfig": { "props.instances": { "binding": { "type": "query", "config": {} } } } }
            """
        )
        analyze(root).repeats.first().instances.shouldBeNull()
    }

    "the repeated-view finding multiplies the target's bindings by the instance count" {
        val host = analyze(
            container(
                """
                { "type": "ia.display.view-repeater", "meta": { "name": "R" },
                  "props": { "path": "Shared/Row", "instances": [ {}, {}, {}, {} ] } }
                """
            )
        )
        val target = analyze(
            container(
                "${bound("""{ "type": "tag", "config": {} }""")}, ${bound("""{ "type": "tag", "config": {} }""")}"
            )
        )

        val finding = ViewPerformanceAnalyzer().repeatedViewFinding(host.repeats.first(), target)
        finding.shouldNotBeNull()
        finding.code shouldBe "repeated_view_cost"
        finding.path shouldBe "root/R"
        finding.message shouldContain "roughly 8 bindings"
    }

    "repeating a view with no bindings costs nothing worth reporting" {
        val host = analyze(
            container(
                """
                { "type": "ia.display.view-repeater", "meta": { "name": "R" },
                  "props": { "path": "Shared/Row", "instances": [ {}, {} ] } }
                """
            )
        )
        val target = analyze(container(""))
        ViewPerformanceAnalyzer().repeatedViewFinding(host.repeats.first(), target).shouldBeNull()
    }

    "an unknown instance count still reports the per-instance cost" {
        val host = analyze(
            container(
                """
                { "type": "ia.display.view-repeater", "meta": { "name": "R" }, "props": { "path": "Shared/Row" } }
                """
            )
        )
        val target = analyze(container(bound("""{ "type": "tag", "config": {} }""")))
        val finding = ViewPerformanceAnalyzer().repeatedViewFinding(host.repeats.first(), target)
        finding.shouldNotBeNull()
        finding.message shouldContain "set at runtime"
    }

    // -- events -------------------------------------------------------------

    "events are counted across groups" {
        val root = container(
            """
            { "type": "ia.display.label", "meta": { "name": "L" }, "events": {
                "dom": { "onClick": {}, "onDoubleClick": {} },
                "component": { "onStartup": {} } } }
            """
        )
        analyze(root).eventCount shouldBe 3
    }
})
