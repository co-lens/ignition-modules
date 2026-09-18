package io.colens.report.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackagePartName
import org.apache.poi.openxml4j.opc.PackagingURIHelper
import org.apache.poi.openxml4j.opc.TargetMode
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The load-bearing assumption, tested first.
 *
 * Scheduled rendering only works if a definition written into a workbook by the Excel add-in is
 * still there after the gateway has opened that workbook with POI, filled in values, and written
 * it back out. POI knows nothing about custom XML parts — they are simply package parts it does
 * not model — so "does POI preserve parts it does not understand?" is the question the whole
 * architecture rests on.
 *
 * If this ever goes red, the persistence mechanism has to change and a large part of the design
 * changes with it. That is why it is here rather than buried in an integration suite.
 */
class DefinitionCodecTest : StringSpec({

    val json = """{"schema":"${Constants.DEFINITION_NAMESPACE}","schemaVersion":1,"groups":[]}"""

    "encode then decode returns the original JSON" {
        DefinitionCodec.decode(DefinitionCodec.encode(json)) shouldBe json
    }

    "a payload containing the CDATA terminator survives the split" {
        // Absurd as a tag name, but the failure mode is a corrupt file rather than an error, so
        // it is worth pinning down. A naive encoder closes the section early and truncates.
        val awkward = """{"name":"foo]]>bar","n":1}"""
        DefinitionCodec.decode(DefinitionCodec.encode(awkward)) shouldBe awkward
    }

    "decode ignores a custom XML part that is not ours" {
        DefinitionCodec.decode("<other xmlns=\"urn:example:other\"><![CDATA[{}]]></other>") shouldBe null
    }

    "a definition survives a full POI open, modify and write cycle" {
        val original = workbookWithDefinition(json)

        // Exactly what WorkbookRenderer will do: open, change a cell, write somewhere new.
        val rendered = ByteArrayOutputStream()
        OPCPackage.open(ByteArrayInputStream(original)).use { pkg ->
            DefinitionCodec.read(pkg).shouldNotBeNull() shouldBe json

            val workbook = XSSFWorkbook(pkg)
            workbook.getSheetAt(0).createRow(0).createCell(0).setCellValue(42.0)
            workbook.write(rendered)
        }

        OPCPackage.open(ByteArrayInputStream(rendered.toByteArray())).use { pkg ->
            DefinitionCodec.read(pkg).shouldNotBeNull() shouldBe json
        }
    }

    "a workbook with no definition reads as null rather than throwing" {
        val plain = ByteArrayOutputStream()
        XSSFWorkbook().use { it.createSheet("Sheet1"); it.write(plain) }

        OPCPackage.open(ByteArrayInputStream(plain.toByteArray())).use { pkg ->
            DefinitionCodec.read(pkg) shouldBe null
        }
    }
})

/**
 * Builds an .xlsx carrying a custom XML part, standing in for one the add-in would have written.
 * Excel adds an itemProps part alongside; this omits it deliberately, so the test also covers the
 * case where POI meets a part with no properties relationship.
 */
private fun workbookWithDefinition(json: String): ByteArray {
    val out = ByteArrayOutputStream()
    XSSFWorkbook().use { workbook ->
        workbook.createSheet("Report")
        val buffer = ByteArrayOutputStream()
        workbook.write(buffer)

        OPCPackage.open(ByteArrayInputStream(buffer.toByteArray())).use { pkg ->
            val name: PackagePartName = PackagingURIHelper.createPartName("/customXml/item1.xml")
            val part = pkg.createPart(name, "application/xml")
            part.outputStream.use { it.write(DefinitionCodec.encode(json).toByteArray(Charsets.UTF_8)) }
            pkg.addRelationship(
                name,
                TargetMode.INTERNAL,
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXml",
            )
            pkg.save(out)
        }
    }
    return out.toByteArray()
}
