package io.colens.mcp.common.perspective

import com.inductiveautomation.ignition.common.gson.JsonObject
import io.colens.mcp.common.Severity
import io.colens.mcp.common.optString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Stands in for Perspective's registry so the catalog-driven checks are testable offline. */
private class FakeCatalog(
    private val types: Set<String> = setOf("ia.container.flex", "ia.display.label"),
    private val badProps: List<SchemaViolation> = emptyList(),
    private val bindings: Set<String> = setOf("tag", "expr", "property"),
    private val badBindingConfig: List<SchemaViolation> = emptyList(),
    private val transforms: Set<String> = setOf("expression", "format"),
    private val badTransform: List<SchemaViolation> = emptyList(),
) : ComponentCatalog {
    override fun componentTypes() = types
    override fun categories() = setOf("Containers", "Display")
    override fun describe(typeId: String): ComponentTypeInfo? = null
    override fun validateProps(typeId: String, props: JsonObject) = badProps
    override fun validateBindingConfig(bindingType: String, config: JsonObject) =
        if (bindingType in bindings) badBindingConfig else null
    override fun bindingTypes() = bindings
    override fun validateTransform(transformType: String, transform: JsonObject) =
        if (transformType in transforms) badTransform else null
    override fun transformTypes() = transforms
}

class ViewValidatorTest : StringSpec({

    fun view(root: String) = ViewDocument.parse("""{ "root": $root }""")

    fun label(body: String = "") = """
        { "type": "ia.display.label", "meta": { "name": "L" } ${if (body.isEmpty()) "" else ", $body"} }
    """

    fun container(children: String) = """
        { "type": "ia.container.flex", "meta": { "name": "root" }, "children": [ $children ] }
    """

    fun findings(root: String, catalog: ComponentCatalog = NoComponentCatalog) =
        ViewValidator(catalog).validate(view(root))

    fun codes(root: String, catalog: ComponentCatalog = NoComponentCatalog) =
        findings(root, catalog).map { it.code }

    // -- clean baseline -----------------------------------------------------

    "a well-formed view produces no findings" {
        val root = container(
            """
            {
              "type": "ia.display.label",
              "meta": { "name": "Title" },
              "props": { "text": "Hi" },
              "propConfig": {
                "props.text": { "binding": { "type": "tag", "config": { "tagPath": "[default]A" } } }
              },
              "events": { "dom": { "onClick": { "type": "script", "config": { "script": "\tpass" } } } }
            }
            """
        )
        findings(root, FakeCatalog()).shouldBeEmpty()
    }

    // -- structural ---------------------------------------------------------

    "a view without root is a single error" {
        val result = ViewValidator().validate(ViewDocument.parse("""{ "params": {} }"""))
        result.map { it.code } shouldBe listOf("missing_root")
    }

    "a component without a type is an error" {
        codes(container("""{ "meta": { "name": "X" } }""")) shouldContain "missing_type"
    }

    "children must be an array" {
        codes("""{ "type": "ia.container.flex", "children": {} }""") shouldContain "invalid_children"
    }

    "a nameless non-root component warns" {
        codes(container("""{ "type": "ia.display.label" }""")) shouldContain "missing_name"
    }

    "duplicate sibling names are an error" {
        val root = container("""${label()}, ${label()}""")
        codes(root) shouldContain "duplicate_name"
    }

    // -- catalog-driven -----------------------------------------------------

    "an unregistered component type is an error" {
        val f = findings(container("""{ "type": "ia.display.nope", "meta": {"name":"X"} }"""), FakeCatalog())
            .first { it.code == "unknown_component_type" }
        f.message shouldContain "ia.display.nope"
        f.fix.shouldNotBeNull() shouldContain "perspective_list_component_types"
    }

    "component types are not guessed at when no catalog is available" {
        codes(container("""{ "type": "totally.made.up", "meta": {"name":"X"} }""")) shouldBe
            emptyList<String>()
    }

    "props violating the component schema are reported" {
        val catalog = FakeCatalog(badProps = listOf(SchemaViolation("$.text", "type", "expected a string")))
        val f = findings(container(label(""""props": { "text": 5 }""")), catalog)
            .first { it.code == "invalid_prop" }
        f.message shouldContain "expected a string"
    }

    "an invalid binding config is reported against its property" {
        val catalog = FakeCatalog(
            badBindingConfig = listOf(SchemaViolation("$.tagPath", "required", "tagPath is required")),
        )
        val root = container(
            label(""""propConfig": { "props.text": { "binding": { "type": "tag", "config": {} } } }""")
        )
        val f = findings(root, catalog).first { it.code == "invalid_binding_config" }
        f.message shouldContain "props.text"
        f.message shouldContain "tagPath is required"
    }

    "an unrecognised binding type warns and lists the known ones" {
        val root = container(
            label(""""propConfig": { "props.text": { "binding": { "type": "wat", "config": {} } } }""")
        )
        val f = findings(root, FakeCatalog()).first { it.code == "unknown_binding_type" }
        f.fix.shouldNotBeNull() shouldContain "tag"
    }

    // -- the three authoring mistakes ---------------------------------------

    "a binding inline in props is an error that says where it belongs" {
        val root = container(
            label(""""props": { "text": { "binding": { "type": "tag", "config": { "tagPath": "[default]A" } } } }""")
        )
        val f = findings(root).first { it.code == "inline_binding" }
        f.message shouldContain "props.text"
        f.fix.shouldNotBeNull() shouldContain "propConfig"
    }

    "an inline binding nested deeper in props is still found" {
        val root = container(
            label(""""props": { "style": { "color": { "binding": { "type": "expr", "config": {} } } } }""")
        )
        findings(root).first { it.code == "inline_binding" }.message shouldContain "props.style.color"
    }

    "bidirectional on the binding instead of its config is an error" {
        val root = container(
            label(
                """"propConfig": { "props.text": { "binding": {
                     "type": "tag", "bidirectional": true, "config": { "tagPath": "[default]A" } } } }"""
            )
        )
        val f = findings(root).first { it.code == "bidirectional_misplaced" }
        f.fix.shouldNotBeNull() shouldContain "\"config\""
    }

    "an event script missing leading tabs is an error naming the line" {
        val root = container(
            label(""""events": { "dom": { "onClick": { "type": "script", "config": { "script": "\tok\nbad" } } } }""")
        )
        val f = findings(root).first { it.code == "script_indentation" }
        f.message shouldContain "line 2"
        f.fix.shouldNotBeNull() shouldContain "tab"
    }

    "a properly indented script is accepted" {
        val root = container(
            label(""""events": { "dom": { "onClick": { "type": "script", "config": { "script": "\tline1\n\n\tline2" } } } }""")
        )
        codes(root) shouldBe emptyList<String>()
    }

    // -- binding envelope ---------------------------------------------------

    "a binding without a type is an error" {
        codes(container(label(""""propConfig": { "props.text": { "binding": { "config": {} } } }"""))) shouldContain
            "binding_missing_type"
    }

    "a binding without a config is an error" {
        codes(container(label(""""propConfig": { "props.text": { "binding": { "type": "tag" } } }"""))) shouldContain
            "binding_missing_config"
    }

    "an unscoped propConfig key warns" {
        val f = findings(container(label(""""propConfig": { "text": { "persistent": true } }""")))
            .first { it.code == "invalid_property_key" }
        f.fix.shouldNotBeNull() shouldContain "props.text"
    }

    "a transform without a type is an error" {
        val root = container(
            label(
                """"propConfig": { "props.text": { "binding": {
                     "type": "tag", "config": { "tagPath": "[default]A" }, "transforms": [ { } ] } } }"""
            )
        )
        codes(root, FakeCatalog()) shouldContain "invalid_transform"
    }

    // -- transform shape (issue #6) -----------------------------------------

    fun transformed(transform: String) = container(
        label(
            """"propConfig": { "props.text": { "binding": {
                 "type": "tag", "config": { "tagPath": "[default]A" }, "transforms": [ $transform ] } } }"""
        )
    )

    "an expression transform nested under config is an error naming the wrapper" {
        val f = findings(
            transformed("""{ "type": "expression", "config": { "expression": "{value} = 8" } }"""),
            FakeCatalog(),
        ).first { it.code == "missing_transform_key" }

        f.message shouldContain "'expression'"
        f.fix.shouldNotBeNull() shouldContain "no 'config' wrapper"
        f.fix.shouldNotBeNull() shouldContain """{"type": "expression", "expression": "{value} = 8"}"""
    }

    "the inline expression transform is clean" {
        codes(transformed("""{ "type": "expression", "expression": "!{value}" }"""), FakeCatalog())
            .shouldNotContain("missing_transform_key")
    }

    "a script transform without code is an error even with no catalog" {
        val f = findings(transformed("""{ "type": "script" }""")).first {
            it.code == "missing_transform_key"
        }
        f.message shouldContain "'code'"
        // Perspective ships no transform-script.json, so TransformShapes is the only cover here.
        f.fix.shouldNotBeNull() shouldContain "inline siblings"
    }

    "a format transform missing formatValue names every key it needs" {
        val f = findings(transformed("""{ "type": "format", "formatType": "numeric" }"""), FakeCatalog())
            .first { it.code == "missing_transform_key" }
        f.message shouldContain "'formatValue'"
        f.message shouldNotContain "'formatType'"
    }

    "a schema violation on a well-shaped transform is reported" {
        val catalog = FakeCatalog(
            badTransform = listOf(SchemaViolation(null, null, "formatValue: not a known format")),
        )
        val f = findings(
            transformed("""{ "type": "format", "formatType": "numeric", "formatValue": "nope" }"""),
            catalog,
        ).first { it.code == "invalid_transform_config" }
        f.message shouldContain "not a known format"
    }

    "an unrecognised transform type warns rather than failing the view" {
        val f = findings(transformed("""{ "type": "nonesuch" }"""), FakeCatalog())
            .first { it.code == "unknown_transform_type" }
        f.severity shouldBe Severity.WARNING
        f.fix.shouldNotBeNull() shouldContain "script"
    }

    "script is known even though no schema ships for it" {
        codes(transformed("""{ "type": "script", "code": "\tdef transform(self, value): pass" }"""), FakeCatalog())
            .shouldNotContain("unknown_transform_type")
    }

    "a transform with no catalog is still shape-checked but never type-warned" {
        val codes = codes(transformed("""{ "type": "nonesuch" }"""))
        codes.shouldNotContain("unknown_transform_type")
    }

    // -- custom property references -----------------------------------------

    "a property binding onto an undefined custom property warns" {
        val root = container(
            label(""""propConfig": { "props.text": { "binding": { "type": "property", "config": { "path": "custom.missing" } } } }""")
        )
        val f = findings(root, FakeCatalog()).first { it.code == "undefined_custom_property" }
        f.message shouldContain "custom.missing"
    }

    "a property binding onto a defined custom property is fine" {
        val root = container(
            label(
                """"custom": { "gain": 1 },
                   "propConfig": { "props.text": { "binding": { "type": "property", "config": { "path": "custom.gain" } } } }"""
            )
        )
        codes(root, FakeCatalog()) shouldBe emptyList<String>()
    }

    // -- reporting ----------------------------------------------------------

    "toJson separates errors from warnings" {
        val root = container(
            """{ "type": "ia.display.label", "props": { "text": { "binding": { "type": "tag", "config": {} } } } }"""
        )
        val json = ViewValidator.toJson(findings(root))

        json.get("valid").asBoolean shouldBe false
        json.get("errorCount").asInt shouldBe 1      // inline_binding
        json.get("warningCount").asInt shouldBe 1    // missing_name
        json.getAsJsonArray("findings")[0].asJsonObject.optString("severity").shouldNotBeNull()
    }

    "a clean view reports valid" {
        val json = ViewValidator.toJson(findings(container(label()), FakeCatalog()))
        json.get("valid").asBoolean.shouldBeTrue()
    }
})
