package io.colens.mcp.common

import com.inductiveautomation.ignition.common.gson.JsonObject

enum class Severity { ERROR, WARNING }

/**
 * One problem found by a validator.
 *
 * [fix] matters as much as [message]: these findings are read by a model that is about to try
 * again, so saying what to do instead turns a failed edit into a corrected one.
 *
 * Deliberately not specific to any one subject. Perspective views and tag configuration both
 * report through this type so a caller sees a single findings shape whatever it validated, and so
 * a client that learned to render one has learned to render all of them.
 */
data class Finding(
    val path: String,
    val code: String,
    val severity: Severity,
    val message: String,
    val fix: String? = null,
) {
    fun toJson(): JsonObject = jsonObject {
        put("path", path)
        put("code", code)
        put("severity", severity.name.lowercase())
        put("message", message)
        put("fix", fix)
    }
}

/** Findings as JSON, plus the counts a caller needs to decide whether to proceed. */
fun findingsJson(findings: List<Finding>): JsonObject {
    val errors = findings.count { it.severity == Severity.ERROR }
    return jsonObject {
        put("valid", errors == 0)
        put("errorCount", errors)
        put("warningCount", findings.size - errors)
        put("findings", jsonArrayOf(findings.map { it.toJson() }))
    }
}
