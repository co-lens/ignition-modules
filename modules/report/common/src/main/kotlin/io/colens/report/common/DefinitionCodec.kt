package io.colens.report.common

import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackagePart
import java.util.regex.Pattern

/**
 * Reads and writes the report definition stored in a workbook's OOXML custom XML part.
 *
 * This is the seam the whole product balances on: the definition is authored by the add-in in
 * Excel and read back here, in a headless JVM, hours later. Both sides must agree on the exact
 * bytes, so the format is deliberately dull — a namespaced wrapper element containing the JSON in
 * a CDATA section:
 *
 *     <report xmlns="urn:colens:ignition-report:1" v="1"><![CDATA[{...}]]></report>
 *
 * JSON-in-CDATA rather than real XML because ONE schema then drives everything: JSON.stringify in
 * the add-in, Gson here, and a single golden fixture shared by both test suites. Mapping the
 * definition to elements and back would mean writing (and drifting) that mapping twice.
 *
 * Deliberately built on OPCPackage, not XSSFWorkbook: custom XML parts are package parts that the
 * spreadsheet model knows nothing about, and reaching for them through the workbook API would
 * mean depending on POI internals that do not expose them.
 */
object DefinitionCodec {

    private val CUSTOM_XML_PART = Pattern.compile("/customXml/item\\d+\\.xml")

    /**
     * CDATA cannot nest, so a payload containing the terminator has to be split across two
     * sections — `]]>` becomes `]]` + `]]><![CDATA[` + `>`. A tag literally named `foo]]>bar` is
     * absurd but not impossible, and the failure it causes is a corrupt file rather than an error.
     */
    private const val CDATA_OPEN = "<![CDATA["
    private const val CDATA_CLOSE = "]]>"

    fun encode(json: String): String =
        buildString {
            append("<report xmlns=\"").append(Constants.DEFINITION_NAMESPACE)
            append("\" v=\"").append(Constants.SCHEMA_VERSION).append("\">")
            append(CDATA_OPEN)
            append(json.replace(CDATA_CLOSE, "]]$CDATA_CLOSE$CDATA_OPEN>"))
            append(CDATA_CLOSE)
            append("</report>")
        }

    /** Returns the JSON payload, or null when this is not one of our parts. */
    fun decode(xml: String): String? {
        if (!xml.contains(Constants.DEFINITION_NAMESPACE)) return null

        val builder = StringBuilder()
        var cursor = 0
        while (true) {
            val open = xml.indexOf(CDATA_OPEN, cursor)
            if (open < 0) break
            val start = open + CDATA_OPEN.length
            val close = xml.indexOf(CDATA_CLOSE, start)
            if (close < 0) break
            builder.append(xml, start, close)
            cursor = close + CDATA_CLOSE.length
        }
        return builder.takeIf { it.isNotEmpty() }?.toString()
    }

    /**
     * Finds our definition among the package's custom XML parts.
     *
     * A workbook can carry several — Office itself adds them for things like data-model metadata —
     * so parts are matched on namespace rather than on being the first one found.
     */
    fun read(pkg: OPCPackage): String? =
        definitionParts(pkg).firstNotNullOfOrNull { part ->
            part.inputStream.use { decode(it.readBytes().toString(Charsets.UTF_8)) }
        }

    fun definitionParts(pkg: OPCPackage): List<PackagePart> =
        pkg.getPartsByName(CUSTOM_XML_PART).filter { part ->
            part.inputStream.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8).contains(Constants.DEFINITION_NAMESPACE)
            }
        }
}
