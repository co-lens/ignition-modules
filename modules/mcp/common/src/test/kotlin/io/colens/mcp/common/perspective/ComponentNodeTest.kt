package io.colens.mcp.common.perspective

import io.colens.mcp.common.McpJson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ComponentNodeTest : StringSpec({

    fun obj(json: String) = McpJson.parse(json).asJsonObject

    // -- the regression -----------------------------------------------------

    "a component with no caller props gets an empty props object, not the type's defaults" {
        // The bug this guards: seeding schema defaults writes properties the Designer never
        // writes, and destroys "absent means still at its default" for every reader downstream.
        val node = newComponentNode("ia.display.view", null, null, props = null, position = null)
        node.getAsJsonObject("props").keySet().shouldContainExactly()
    }

    "caller props are written exactly, with nothing added" {
        val node = newComponentNode(
            "ia.display.view", null, null,
            props = obj("""{"path":"Shared/Row","params":{"id":1}}"""),
            position = null,
        )
        node.getAsJsonObject("props").keySet() shouldBe setOf("path", "params")
    }

    "caller props are copied, so later edits don't reach back into the argument" {
        val props = obj("""{"text":"hi"}""")
        val node = newComponentNode("ia.display.label", null, null, props, position = null)
        node.getAsJsonObject("props").addProperty("text", "changed")
        props.get("text").asString shouldBe "hi"
    }

    // -- naming -------------------------------------------------------------

    "an explicit name wins" {
        newComponentNode("ia.display.label", "Title", "Label", null, null)
            .getAsJsonObject("meta").get("name").asString shouldBe "Title"
    }

    "the type's default meta name is next" {
        newComponentNode("ia.display.label", null, "Label", null, null)
            .getAsJsonObject("meta").get("name").asString shouldBe "Label"
    }

    "with neither, the last segment of the type id is used" {
        newComponentNode("ia.display.label", null, null, null, null)
            .getAsJsonObject("meta").get("name").asString shouldBe "label"
    }

    // -- position -----------------------------------------------------------

    "position is written when given" {
        val node = newComponentNode("ia.display.label", null, null, null, obj("""{"basis":"100px"}"""))
        node.getAsJsonObject("position").get("basis").asString shouldBe "100px"
    }

    "a null position omits the key entirely, which is what a root container looks like" {
        newComponentNode("ia.container.flex", "root", null, null, null).has("position") shouldBe false
    }

    // -- merge --------------------------------------------------------------

    "mergeJson recurses into nested objects rather than replacing them" {
        val target = obj("""{"style":{"classes":"a","color":"red"},"text":"x"}""")
        mergeJson(target, obj("""{"style":{"color":"blue"}}"""))
        target.getAsJsonObject("style").get("classes").asString shouldBe "a"
        target.getAsJsonObject("style").get("color").asString shouldBe "blue"
        target.get("text").asString shouldBe "x"
    }

    "mergeJson replaces scalars and arrays outright" {
        val target = obj("""{"n":1,"list":[1,2,3]}""")
        mergeJson(target, obj("""{"n":2,"list":[9]}"""))
        target.get("n").asInt shouldBe 2
        target.getAsJsonArray("list").size() shouldBe 1
    }
})
