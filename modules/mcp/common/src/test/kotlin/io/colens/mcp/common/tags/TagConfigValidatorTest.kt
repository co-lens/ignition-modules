package io.colens.mcp.common.tags

import com.inductiveautomation.ignition.common.gson.JsonObject
import io.colens.mcp.common.McpJson
import io.colens.mcp.common.Severity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Stands in for the gateway's tag property model so the catalog-driven rules are testable offline. */
private class FakeTagPropertyCatalog(
    private val names: Set<String> = setOf(
        "name", "tagType", "dataType", "valueSource", "value", "typeId",
        "opcServer", "opcItemPath", "historyEnabled", "tagGroup",
    ),
) : TagPropertyCatalog {
    override fun propertyNames(): Set<String> = names
}

class TagConfigValidatorTest : StringSpec({

    fun config(json: String): JsonObject = McpJson.parse(json).asJsonObject

    fun findings(json: String, catalog: TagPropertyCatalog = NoTagPropertyCatalog) =
        TagConfigValidator(catalog).validate(config(json))

    fun codes(json: String, catalog: TagPropertyCatalog = NoTagPropertyCatalog) =
        findings(json, catalog).map { it.code }

    // -- clean baseline -----------------------------------------------------

    "an ordinary memory tag produces no findings" {
        findings(
            """{"name":"Count","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4","value":7}""",
            FakeTagPropertyCatalog(),
        ).shouldBeEmpty()
    }

    "a UDT definition with typed parameters and children produces no findings" {
        findings(
            """
            {"name":"Motor","tagType":"UdtType",
             "parameters":{"DeviceName":{"dataType":"String","value":"PLC1"}},
             "tags":[{"name":"Run","tagType":"AtomicTag","dataType":"Boolean","valueSource":"opc",
                      "opcServer":"Ignition OPC UA Server","opcItemPath":"ns=1;s=[{DeviceName}]Run"}]}
            """,
            FakeTagPropertyCatalog(),
        ).shouldBeEmpty()
    }

    // -- regressions for the four silent failures ---------------------------
    // Each of these returns success from TagUtilities.toTagConfiguration on 8.3.8, having either
    // corrupted the config or thrown something unreadable. Verified against common-8.3.8.jar.

    "SILENT FAILURE: a parameter value without dataType loses the value" {
        // toTagConfiguration emits {"DeviceName":{"dataType":"Integer"}} and reports success.
        val f = findings(
            """{"name":"M1","tagType":"UdtInstance","typeId":"Motor",
                "parameters":{"DeviceName":{"value":"PLC7"}}}""",
        )
        f.map { it.code } shouldContain "parameter_missing_datatype"
        f.first { it.code == "parameter_missing_datatype" }.severity shouldBe Severity.ERROR
        f.first { it.code == "parameter_missing_datatype" }.message shouldContain "DISCARDS the value"
    }

    "a parameter override IS still required to state its type, even though the UDT declares it" {
        // The natural thing to write for an instance override is value-only. It does not work.
        codes(
            """{"name":"M1","tagType":"UdtInstance","typeId":"Motor",
                "parameters":{"DeviceName":{"dataType":"String","value":"PLC7"}}}""",
        ).shouldBeEmpty()
    }

    "a parameter declared with a type but no value is fine" {
        codes("""{"name":"M1","tagType":"UdtInstance","typeId":"M","parameters":{"P":{"dataType":"String"}}}""")
            .shouldBeEmpty()
    }

    "SILENT FAILURE: a case-typo'd property is retained as a custom property" {
        // toTagConfiguration keeps "datatype" verbatim and leaves the real dataType unset.
        val f = findings(
            """{"name":"X","tagType":"AtomicTag","datatype":"Int4"}""",
            FakeTagPropertyCatalog(),
        )
        f.map { it.code } shouldContain "misspelled_property"
        f.first().severity shouldBe Severity.ERROR
        f.first().message shouldContain "dataType"
    }

    "SILENT FAILURE: a missing name throws an index error from Ignition, not a useful message" {
        val f = findings("""{"tagType":"AtomicTag","dataType":"Int4"}""")
        f.map { it.code } shouldContain "missing_name"
        f.first().message shouldContain "index error"
    }

    "SILENT FAILURE: an invalid name is accepted by Ignition's parser" {
        // TagUtilities.isValidName("bad/name") is false, but toTagConfiguration accepts it.
        codes("""{"name":"bad/name","tagType":"AtomicTag","dataType":"Int4"}""") shouldContain "invalid_name"
    }

    // -- names --------------------------------------------------------------

    "a name with a dot is rejected" {
        codes("""{"name":"a.b","tagType":"AtomicTag"}""") shouldContain "invalid_name"
    }

    "an ordinary name with underscores and digits is accepted" {
        codes("""{"name":"Pump_2","tagType":"AtomicTag"}""") shouldNotContain "invalid_name"
    }

    // -- tag types ----------------------------------------------------------

    "an unrecognised tagType is an error and lists the valid ones" {
        val f = findings("""{"name":"X","tagType":"Widget"}""")
        f.map { it.code } shouldContain "unknown_tag_type"
        f.first().fix!! shouldContain "UdtInstance"
    }

    "omitting tagType is allowed, since Ignition infers it" {
        codes("""{"name":"X","dataType":"Int4"}""", FakeTagPropertyCatalog()).shouldBeEmpty()
    }

    "a UDT instance without typeId is an error" {
        codes("""{"name":"M1","tagType":"UdtInstance"}""") shouldContain "udt_instance_missing_type"
    }

    "a UDT instance with a blank typeId is an error" {
        codes("""{"name":"M1","tagType":"UdtInstance","typeId":""}""") shouldContain "udt_instance_missing_type"
    }

    // -- properties ---------------------------------------------------------

    "an unknown property with no near match is only a warning, since custom properties are legal" {
        val f = findings(
            """{"name":"X","tagType":"AtomicTag","myCustomThing":1}""",
            FakeTagPropertyCatalog(),
        )
        f.map { it.code } shouldContain "unknown_property"
        f.first { it.code == "unknown_property" }.severity shouldBe Severity.WARNING
    }

    "without a catalog neither property rule fires" {
        codes("""{"name":"X","tagType":"AtomicTag","datatype":"Int4","whatever":1}""").shouldBeEmpty()
    }

    "structural keys are never reported as unknown properties" {
        codes(
            """{"name":"F","tagType":"Folder","tags":[],"parameters":{}}""",
            FakeTagPropertyCatalog(),
        ).shouldBeEmpty()
    }

    // -- children -----------------------------------------------------------

    "an AtomicTag carrying children is an error" {
        codes("""{"name":"X","tagType":"AtomicTag","tags":[{"name":"Y","tagType":"AtomicTag"}]}""")
            .shouldContain("children_on_atomic_tag")
    }

    "a folder carrying children is fine" {
        codes("""{"name":"F","tagType":"Folder","tags":[{"name":"Y","tagType":"AtomicTag"}]}""")
            .shouldBeEmpty()
    }

    "duplicate sibling names are an error" {
        codes(
            """{"name":"F","tagType":"Folder","tags":[
                 {"name":"A","tagType":"AtomicTag"},{"name":"A","tagType":"AtomicTag"}]}""",
        ) shouldContain "duplicate_child_name"
    }

    "the same name under different parents is fine" {
        codes(
            """{"name":"F","tagType":"Folder","tags":[
                 {"name":"G","tagType":"Folder","tags":[{"name":"A","tagType":"AtomicTag"}]},
                 {"name":"H","tagType":"Folder","tags":[{"name":"A","tagType":"AtomicTag"}]}]}""",
        ).shouldBeEmpty()
    }

    "'tags' that is not an array is an error" {
        codes("""{"name":"F","tagType":"Folder","tags":"nope"}""") shouldContain "invalid_children"
    }

    "'parameters' that is not an object is an error" {
        codes("""{"name":"M","tagType":"UdtType","parameters":"nope"}""") shouldContain "invalid_parameters"
    }

    // -- recursion and paths ------------------------------------------------

    "findings from nested tags carry a slash path" {
        val f = findings(
            """{"name":"Motor","tagType":"UdtType","tags":[{"tagType":"AtomicTag","dataType":"Int4"}]}""",
        )
        f.map { it.code } shouldContain "missing_name"
        f.first { it.code == "missing_name" }.path shouldBe "Motor/?"
    }

    "nested parameter errors are found at any depth" {
        codes(
            """{"name":"Plant","tagType":"Folder","tags":[
                 {"name":"Line","tagType":"Folder","tags":[
                   {"name":"M1","tagType":"UdtInstance","typeId":"Motor",
                    "parameters":{"P":{"value":"x"}}}]}]}""",
        ) shouldContain "parameter_missing_datatype"
    }

    "validateAll names entries by tag name, falling back to index" {
        val v = TagConfigValidator()
        val f = v.validateAll(
            listOf(
                config("""{"name":"bad/name","tagType":"AtomicTag"}"""),
                config("""{"tagType":"AtomicTag"}"""),
            ),
        )
        f.map { it.path } shouldBe listOf("bad/name", "[1]")
    }
})
