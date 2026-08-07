package io.colens.mcp.common

object Constants {
    const val MODULE_ID: String = "io.colens.mcp-ign"

    /** Short id — also the gateway route mount alias, giving URLs under `/data/mcp/`. */
    const val SHORT_MODULE_ID: String = "mcp"

    const val SERVER_NAME: String = "ignition-mcp"

    /**
     * Protocol version we answer with when the client asks for something we don't recognise.
     * We speak the initialization-based era; a stateless POST-only server is compliant there
     * and under the 2026-07-28 revision alike.
     */
    const val DEFAULT_PROTOCOL_VERSION: String = "2025-06-18"

    /** Client-requested versions we'll echo back rather than downgrading. */
    val SUPPORTED_PROTOCOL_VERSIONS: Set<String> =
        setOf("2025-03-26", "2025-06-18", "2025-11-25")
}
