package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonElement
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.McpJson
import io.colens.mcp.common.optString
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A view shaped like one the Designer writes: a flex root with a label and a nested container
 * holding a second label, plus a tag binding and an onClick script.
 */
private const val SAMPLE = """
{
  "custom": { "viewLevel": 1 },
  "params": { "deviceId": "" },
  "props": { "defaultSize": { "width": 800, "height": 600 } },
  "root": {
    "type": "ia.container.flex",
    "meta": { "name": "root" },
    "props": { "direction": "column" },
    "children": [
      {
        "type": "ia.display.label",
        "meta": { "name": "Title" },
        "position": { "grow": 1 },
        "props": { "text": "Hello" },
        "propConfig": {
          "props.text": { "binding": { "type": "tag", "config": { "tagPath": "[default]A" } } }
        },
        "events": {
          "dom": { "onClick": { "type": "script", "config": { "script": "\tprint 1" } } }
        }
      },
      {
        "type": "ia.container.coord",
        "meta": { "name": "Inner" },
        "custom": { "gain": 2 },
        "children": [
          { "type": "ia.display.label", "meta": { "name": "Nested" } }
        ]
      }
    ]
  }
}
"""

/**
 * The same shape, but with members in an order no serializer would choose: `children` ahead of
 * `type`, `events` trailing after it, `meta` last on the nested label, and the view's own keys
 * reversed. Used by the member-ordering cases below.
 */
private const val AWKWARD_ORDER = """
{
  "root": {
    "children": [
      {
        "children": [
          { "meta": { "name": "Nested" }, "props": { "text": "Deep" }, "type": "ia.display.label" }
        ],
        "type": "ia.container.coord",
        "custom": { "gain": 2 },
        "meta": { "name": "Inner" }
      },
      {
        "props": { "text": "Hello" },
        "type": "ia.display.label",
        "meta": { "name": "Title" },
        "events": {
          "dom": { "onClick": { "config": { "script": "\tprint 1" }, "type": "script" } }
        },
        "position": { "grow": 1 }
      }
    ],
    "props": { "direction": "column" },
    "type": "ia.container.flex",
    "meta": { "name": "root" }
  },
  "props": { "defaultSize": { "height": 600, "width": 800 } },
  "params": { "deviceId": "" },
  "custom": { "viewLevel": 1 }
}
"""

/**
 * Every object in [element], addressed by its position in the tree, mapped to its member order.
 * Comparing two of these says "nothing moved" far more precisely than comparing serialized text,
 * which also changes when a value does.
 */
private fun keyOrders(element: JsonElement, path: String = "$"): Map<String, List<String>> {
    val out = LinkedHashMap<String, List<String>>()
    when {
        element.isJsonObject -> {
            val obj = element.asJsonObject
            out[path] = obj.keySet().toList()
            obj.entrySet().forEach { (key, value) -> out += keyOrders(value, "$path.$key") }
        }
        element.isJsonArray ->
            element.asJsonArray.forEachIndexed { i, value -> out += keyOrders(value, "$path[$i]") }
    }
    return out
}

class ViewDocumentTest : StringSpec({

    fun doc() = ViewDocument.parse(SAMPLE)

    // -- navigation ---------------------------------------------------------

    "resolves components by name" {
        val d = doc()
        d.typeOf(d.component("root")) shouldBe "ia.container.flex"
        d.typeOf(d.component("root/Title")) shouldBe "ia.display.label"
        d.typeOf(d.component("root/Inner/Nested")) shouldBe "ia.display.label"
    }

    "resolves components by numeric index when a name doesn't match" {
        val d = doc()
        d.nameOf(d.component("root/0")).shouldNotBeNull() shouldBe "Title"
        d.nameOf(d.component("root/1/0")).shouldNotBeNull() shouldBe "Nested"
    }

    "a bad path names the available children" {
        val thrown = shouldThrow<McpArgumentException> { doc().component("root/Nope") }
        thrown.message.shouldNotBeNull() shouldContain "Title"
    }

    "a path must start at root" {
        shouldThrow<McpArgumentException> { doc().component("Title") }
            .message.shouldNotBeNull() shouldContain "must start with 'root'"
    }

    "parentOf returns null for root and the parent otherwise" {
        val d = doc()
        d.parentOf("root") shouldBe null
        d.nameOf(d.parentOf("root/Inner/Nested")!!) shouldBe "Inner"
    }

    // -- isolation ----------------------------------------------------------

    "the document is deep-copied, so edits never touch the caller's json" {
        val original = ViewDocument.parse(SAMPLE).json()
        val edited = ViewDocument.of(original)
        edited.props("root/Title").addProperty("text", "Changed")

        original.getAsJsonObject("root").getAsJsonArray("children")[0]
            .asJsonObject.getAsJsonObject("props").optString("text") shouldBe "Hello"
        edited.props("root/Title").optString("text") shouldBe "Changed"
    }

    "a failed edit leaves the document usable" {
        val d = doc()
        shouldThrow<McpArgumentException> { d.addComponent("root/Missing", com.inductiveautomation.ignition.common.gson.JsonObject()) }
        d.componentCount() shouldBe 4
    }

    // -- member ordering ----------------------------------------------------
    //
    // These are a contract, not an incidental property. ViewDocument mutates the parsed tree in
    // place (a Gson JsonObject, whose backing map iterates in insertion order), so a view read,
    // edited and written back keeps every untouched member exactly where the Designer put it.
    // The lens project's view-fixture corpus (co-lens/lens, branch `mcp-corpus`) is pinned
    // byte-exact against our output and depends on that. A refactor of ViewDocument toward typed
    // fields would reorder every MCP-touched view, break that corpus, and — without these cases —
    // pass the rest of this suite. The cases above cover structure; none of them covers order.

    "a round trip preserves member order exactly" {
        val expected = McpJson.toPrettyString(McpJson.parse(AWKWARD_ORDER))
        ViewDocument.parse(AWKWARD_ORDER).toJsonString() shouldBe expected
    }

    "editing one component leaves every other member's position untouched" {
        val before = keyOrders(McpJson.parse(AWKWARD_ORDER))
        val baseline = McpJson.toPrettyString(McpJson.parse(AWKWARD_ORDER))

        val d = ViewDocument.parse(AWKWARD_ORDER)
        d.props("root/Inner/Nested").addProperty("text", "Changed")

        keyOrders(d.json()) shouldBe before

        // Belt and braces: the serialized form differs on exactly the one line that changed.
        val after = d.toJsonString()
        after.lines().size shouldBe baseline.lines().size
        val changed = baseline.lines().zip(after.lines()).filter { (a, b) -> a != b }
        changed.map { it.second.trim() } shouldContainExactly listOf("\"text\": \"Changed\"")
    }

    "adding a component appends without disturbing existing members" {
        val before = keyOrders(McpJson.parse(AWKWARD_ORDER))

        val d = ViewDocument.parse(AWKWARD_ORDER)
        val node = com.inductiveautomation.ignition.common.gson.JsonObject().apply {
            addProperty("type", "ia.display.label")
            metaObject().addProperty("name", "Added")
        }
        d.addComponent("root/Inner", node) shouldBe "root/Inner/Added"

        // Every path that existed before still holds the same members in the same order; the only
        // new entries are the appended node's own.
        val after = keyOrders(d.json())
        after.filterKeys { it in before.keys } shouldBe before
    }

    // -- structure ----------------------------------------------------------

    "adds a component and returns its path" {
        val d = doc()
        val node = ViewDocument.parse("""{"root":{"type":"x"}}""").json()  // scratch object
        node.addProperty("type", "ia.display.label")

        val path = d.addComponent("root/Inner", node)
        path shouldBe "root/Inner/label"
        d.typeOf(d.component(path)) shouldBe "ia.display.label"
    }

    "auto-names collisions rather than overwriting" {
        val d = doc()
        fun label() = com.inductiveautomation.ignition.common.gson.JsonObject().apply {
            addProperty("type", "ia.display.label")
        }

        d.addComponent("root", label().also { it.metaObject().addProperty("name", "Title") }) shouldBe "root/Title1"
        d.addComponent("root", label().also { it.metaObject().addProperty("name", "Title") }) shouldBe "root/Title2"
        d.component("root/Title").shouldNotBeNull()
    }

    "inserts at an index" {
        val d = doc()
        val node = com.inductiveautomation.ignition.common.gson.JsonObject().apply {
            addProperty("type", "ia.display.label")
            metaObject().addProperty("name", "First")
        }
        d.addComponent("root", node, index = 0)

        d.component("root").getAsJsonArray("children")
            .map { d.nameOf(it.asJsonObject) } shouldContainExactly listOf("First", "Title", "Inner")
    }

    "removes a component" {
        val d = doc()
        d.removeComponent("root/Title")
        d.componentOrNull("root/Title") shouldBe null
        d.componentCount() shouldBe 3
    }

    "refuses to remove the root" {
        shouldThrow<McpArgumentException> { doc().removeComponent("root") }
            .message.shouldNotBeNull() shouldContain "root component"
    }

    "moves a component to a new parent" {
        val d = doc()
        val moved = d.moveComponent("root/Title", "root/Inner")

        moved shouldBe "root/Inner/Title"
        d.componentOrNull("root/Title") shouldBe null
        d.typeOf(d.component("root/Inner/Title")) shouldBe "ia.display.label"
        d.componentCount() shouldBe 4
    }

    "refuses to move a container into its own subtree" {
        shouldThrow<McpArgumentException> { doc().moveComponent("root/Inner", "root/Inner/Nested") }
            .message.shouldNotBeNull() shouldContain "descendants"
    }

    "renames a component and reports the new path" {
        val d = doc()
        d.renameComponent("root/Title", "Header") shouldBe "root/Header"
        d.componentOrNull("root/Title") shouldBe null
        d.typeOf(d.component("root/Header")) shouldBe "ia.display.label"
    }

    "refuses a rename that collides with a sibling" {
        shouldThrow<McpArgumentException> { doc().renameComponent("root/Title", "Inner") }
            .message.shouldNotBeNull() shouldContain "already used"
    }

    // -- property containers ------------------------------------------------

    "creates propConfig and events on demand" {
        val d = doc()
        d.component("root/Inner").has("propConfig") shouldBe false

        d.propConfig("root/Inner").addProperty("marker", "x")
        d.events("root/Inner").addProperty("marker", "y")

        d.component("root/Inner").getAsJsonObject("propConfig").optString("marker") shouldBe "x"
        d.component("root/Inner").getAsJsonObject("events").optString("marker") shouldBe "y"
    }

    "view paths address the view document, not a component" {
        val d = doc()
        ViewDocument.isViewPath("").shouldBeTrue()
        ViewDocument.isViewPath("view").shouldBeTrue()
        ViewDocument.isViewPath("root") shouldBe false

        d.custom("view").keySet() shouldContainExactly setOf("viewLevel")
        d.custom("root/Inner").keySet() shouldContainExactly setOf("gain")
        d.params().keySet() shouldContainExactly setOf("deviceId")
    }

    "events are rejected at view level" {
        shouldThrow<McpArgumentException> { doc().events("view") }
            .message.shouldNotBeNull() shouldContain "components"
    }

    // -- summaries ----------------------------------------------------------

    "tree lists every component with its path and attachments" {
        val tree = doc().tree()
        tree.map { it.asJsonObject.optString("path") } shouldContainExactly
            listOf("root", "root/Title", "root/Inner", "root/Inner/Nested")

        val title = tree[1].asJsonObject
        title.optString("type") shouldBe "ia.display.label"
        title.getAsJsonArray("boundProperties").map { it.asString } shouldContainExactly listOf("props.text")
        title.get("eventCount").asInt shouldBe 1

        tree[2].asJsonObject.get("customPropertyCount").asInt shouldBe 1
    }

    "counts components" {
        doc().componentCount() shouldBe 4
    }
})
