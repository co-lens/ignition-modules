package io.colens.report.common

object Constants {
    const val MODULE_ID = "io.colens.report-ign"

    /** Drives the `/data/report/...` route prefix and the `/res/report/...` resource prefix. */
    const val SHORT_MODULE_ID = "report"

    /**
     * Namespace of the workbook's custom XML part, and the schema identifier inside it.
     *
     * Versioned in the URI rather than only in a field so that a renderer can reject a workbook it
     * cannot read by NAMESPACE, before parsing anything. Bump only for a change an older renderer
     * cannot safely read.
     */
    const val DEFINITION_NAMESPACE = "urn:colens:ignition-report:1"

    const val SCHEMA_VERSION = 1

    /**
     * Workbook-scoped defined name that marks a file as ours.
     *
     * It exists so the add-in can tell "this was one of our reports and its definition has been
     * stripped" apart from "this is an ordinary workbook". Excel's Document Inspector deletes
     * custom XML parts without touching defined names, and that is not a hypothetical: the
     * workbook then opens fine, shows its last good numbers, and only the scheduled render fails.
     */
    const val MARKER_NAME = "IGN_REPORT"

    /** Per-group anchor defined name, `IGN_G_<groupId>`. */
    fun anchorName(groupId: String): String = "IGN_G_$groupId"
}
