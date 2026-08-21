package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonElement
import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.ignition.common.jsonschema.JsonSchema
import io.colens.mcp.common.McpJson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Perspective's shipped `schemas/binding-tag.json`, verbatim. Byte-identical in perspective-common
 * 2.1.54 (8.1) and 3.3.8 (8.3). Embedded rather than read from the jar because `:common:test`
 * deliberately runs without Perspective on the classpath — Ignition, and so `JsonSchema`, is there.
 */
private val SHIPPED_TAG_SCHEMA = """
{
  "type": "object",
  "properties": {
    "tagPath": { "type": "string", "description": "The tag path", "default": "" },
    "mode": {
      "type": "string",
      "enum": ["direct", "indirect", "expression"],
      "default": "direct"
    },
    "bidirectional": { "type": "boolean", "default": false },
    "references": { "type": "object", "default": {}, "additionalProperties": true }
  },
  "required": ["tagPath"],
  "default": { "tagPath": "", "mode": "direct", "bidirectional": false },
  "additionalProperties": false
}
""".trimIndent()

/** The binding from issue #5, as authored by the Designer and working at runtime. */
private val REPORTED_CONFIG = """
{
  "fallbackDelay": 2.5,
  "mode": "indirect",
  "references": { "alarmName": "{view.params.alarmName}", "tagPath": "{view.params.tagPath}" },
  "tagPath": "{tagPath}/Status/{alarmName}/Disabled"
}
""".trimIndent()

private fun schemaOf(json: JsonElement): JsonSchema =
    JsonSchema.parse(McpJson.toString(json).byteInputStream(Charsets.UTF_8))

private fun violations(schema: JsonSchema, config: String): List<String> {
    val value = McpJson.parse(config)
    return schema.validate(value, value, "config").map { it.message ?: it.toString() }
}

private fun shipped(): JsonObject = McpJson.parse(SHIPPED_TAG_SCHEMA).asJsonObject

class BindingSchemaPatchesTest : StringSpec({

    // Pins the premise: without the patch, Perspective's own schema rejects Perspective's own
    // output. If this ever stops failing, the patch has become unnecessary.
    "the shipped schema rejects the binding the Designer wrote" {
        val messages = violations(schemaOf(shipped()), REPORTED_CONFIG)
        messages.joinToString() shouldContain "fallbackDelay"
        messages.joinToString() shouldContain "additional properties are not allowed"
    }

    "the patched schema accepts it" {
        val patched = BindingSchemaPatches.patch("tag", shipped())
        violations(schemaOf(patched), REPORTED_CONFIG).shouldBeEmpty()
    }

    "publishInitial and coalesce are accepted too" {
        val patched = schemaOf(BindingSchemaPatches.patch("tag", shipped()))
        violations(
            patched,
            """{ "tagPath": "[default]A", "publishInitial": true, "coalesce": true }"""
        ).shouldBeEmpty()
    }

    // readNumber coerces a numeric string, so the platform honours this and we must not flag it.
    "a string fallbackDelay is accepted, because Perspective coerces it" {
        val patched = schemaOf(BindingSchemaPatches.patch("tag", shipped()))
        violations(patched, """{ "tagPath": "[default]A", "fallbackDelay": "2.5" }""").shouldBeEmpty()
    }

    // readBoolean does not coerce; a non-boolean is silently dropped, which is worth flagging.
    "a non-boolean publishInitial is still rejected" {
        val patched = schemaOf(BindingSchemaPatches.patch("tag", shipped()))
        violations(patched, """{ "tagPath": "[default]A", "publishInitial": "yes" }""")
            .shouldNotBe(emptyList<String>())
    }

    // We restored declarations rather than opening the schema up.
    "a misspelled key is still rejected" {
        val patched = schemaOf(BindingSchemaPatches.patch("tag", shipped()))
        violations(patched, """{ "tagPath": "[default]A", "fallbackDelays": 2.5 }""")
            .joinToString() shouldContain "fallbackDelays"
    }

    "the rest of the schema is untouched" {
        val patched = BindingSchemaPatches.patch("tag", shipped()).asJsonObject
        patched.get("required") shouldBe shipped().get("required")
        patched.get("additionalProperties") shouldBe shipped().get("additionalProperties")
        patched.getAsJsonObject("properties").get("tagPath") shouldBe
            shipped().getAsJsonObject("properties").get("tagPath")
        violations(schemaOf(patched), """{ "mode": "direct" }""")
            .joinToString() shouldContain "tagPath"
        violations(schemaOf(patched), """{ "tagPath": "[default]A", "mode": "sideways" }""")
            .shouldNotBe(emptyList<String>())
    }

    "patching twice is the same as patching once" {
        val once = BindingSchemaPatches.patch("tag", shipped())
        val twice = BindingSchemaPatches.patch("tag", once)
        twice shouldBe once
    }

    // A future Perspective that declares these itself wins; we never overwrite.
    "an existing declaration is left alone" {
        val theirs = shipped().apply {
            getAsJsonObject("properties").add(
                "fallbackDelay",
                McpJson.parse("""{ "type": "integer", "minimum": 0 }""")
            )
        }
        val patched = BindingSchemaPatches.patch("tag", theirs).asJsonObject
        patched.getAsJsonObject("properties").get("fallbackDelay") shouldBe
            theirs.getAsJsonObject("properties").get("fallbackDelay")
    }

    "a schema that allows additional properties is returned unchanged" {
        val open = shipped().apply { addProperty("additionalProperties", true) }
        (BindingSchemaPatches.patch("tag", open) === open) shouldBe true
    }

    "a binding type with no known gap is returned unchanged" {
        val other = shipped()
        (BindingSchemaPatches.patch("expr", other) === other) shouldBe true
    }

    "malformed input is returned unchanged rather than throwing" {
        val notAnObject = McpJson.parse("""[1, 2]""")
        (BindingSchemaPatches.patch("tag", notAnObject) === notAnObject) shouldBe true

        val noProperties = McpJson.parse("""{ "additionalProperties": false }""")
        (BindingSchemaPatches.patch("tag", noProperties) === noProperties) shouldBe true
    }
})
