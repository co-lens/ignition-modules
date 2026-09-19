package io.colens.report.common

import com.inductiveautomation.ignition.common.gson.Gson
import com.inductiveautomation.ignition.common.gson.JsonObject
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

/**
 * The envelope cases, shared BYTE-IDENTICALLY with the add-in's Vitest suite
 * (fixtures/definition-envelope.json in the report repo).
 *
 * The add-in writes this into a workbook's custom XML part; this module reads it back with POI
 * when a schedule fires, with no Excel running anywhere. The two implementations never meet, so
 * nothing catches a disagreement about the exact bytes except this file — and the failure it would
 * otherwise produce is a report that silently stops working at 06:00 one morning.
 */
class DefinitionEnvelopeTest : StringSpec({

    val fixture = Gson().fromJson(
        checkNotNull(javaClass.getResourceAsStream("/definition-envelope.json")) {
            "definition-envelope.json is missing — it must stay in sync with the add-in repo."
        }.reader(),
        JsonObject::class.java,
    )

    "the namespace matches the fixture" {
        fixture.get("namespace").asString shouldBe Constants.DEFINITION_NAMESPACE
        fixture.get("schemaVersion").asInt shouldBe Constants.SCHEMA_VERSION
    }

    for (element in fixture.getAsJsonArray("cases")) {
        val case = element.asJsonObject
        val name = case.get("name").asString
        val json = case.get("json").asString
        val encoded = case.get("encoded").asString

        "encodes: $name" {
            DefinitionCodec.encode(json) shouldBe encoded
        }

        "round-trips: $name" {
            DefinitionCodec.decode(DefinitionCodec.encode(json)) shouldBe json
        }

        "decodes the stored form: $name" {
            // The fixture's own `encoded`, not our output — a matched pair of encode/decode bugs
            // would cancel out and the round-trip alone would pass.
            DefinitionCodec.decode(encoded) shouldBe json
        }

        "produces XML a parser accepts: $name" {
            // An unsplit CDATA terminator parses as garbage rather than failing loudly, so "looks
            // right" is not good enough for something POI has to read months later.
            val document = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(InputSource(StringReader(encoded)))
            document.documentElement.textContent shouldBe json
        }
    }

    for (xml in fixture.getAsJsonArray("notOurs")) {
        "rejects: ${xml.asString.take(40)}" {
            DefinitionCodec.decode(xml.asString).shouldBeNull()
        }
    }
})
