package io.colens.mcp.common.perspective

import io.colens.mcp.common.McpJson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class TransformShapesTest : StringSpec({

    fun transform(json: String) = McpJson.parse(json).asJsonObject

    "the key table matches what Perspective's factories read" {
        TransformShapes.knownTypes() shouldBe setOf("expression", "format", "map", "script")
        TransformShapes.REQUIRED_KEYS["expression"] shouldContainExactly listOf("expression")
        TransformShapes.REQUIRED_KEYS["format"] shouldContainExactly listOf("formatType", "formatValue")
        TransformShapes.REQUIRED_KEYS["map"] shouldContainExactly
            listOf("mappings", "inputType", "outputType")
        TransformShapes.REQUIRED_KEYS["script"] shouldContainExactly listOf("code")
    }

    "a correctly written inline transform is missing nothing" {
        TransformShapes.missingKeys(
            transform("""{ "type": "expression", "expression": "!{value}" }""")
        ).shouldBeEmpty()
    }

    "the binding-shaped form reports the key it buried under config" {
        TransformShapes.missingKeys(
            transform("""{ "type": "expression", "config": { "expression": "{value} = 8" } }""")
        ) shouldContainExactly listOf("expression")
    }

    "only the absent keys are reported" {
        TransformShapes.missingKeys(
            transform("""{ "type": "format", "formatType": "numeric" }""")
        ) shouldContainExactly listOf("formatValue")
    }

    "a type we do not know the shape of is left alone" {
        TransformShapes.missingKeys(transform("""{ "type": "nonesuch" }""")).shouldBeEmpty()
        TransformShapes.missingKeys(transform("""{ }""")).shouldBeEmpty()
    }

    "the fix names the config wrapper only when one was used" {
        val nested = TransformShapes.fixFor(
            transform("""{ "type": "expression", "config": { "expression": "x" } }""")
        )
        nested shouldContain "no 'config' wrapper"
        nested shouldContain """{"type": "expression", "expression": "{value} = 8"}"""

        val plain = TransformShapes.fixFor(transform("""{ "type": "expression" }"""))
        plain shouldNotContain "no 'config' wrapper"
        plain shouldContain "inline siblings"
    }

    "an unknown type still gets the general advice, without an example" {
        TransformShapes.fixFor(transform("""{ "type": "nonesuch" }""")) shouldBe
            "Transform keys are inline siblings of 'type'."
    }
})
