package io.colens.mcp.common

object Constants {

    /**
     * 8.1's `ProjectResource` has no `DEFAULT_JSON_KEY` — only `DEFAULT_DATA_KEY` ("data.bin").
     * This is the literal value 8.3's constant carries, read off `common-8.3.8.jar`, kept here so
     * the two call sites can't drift. It matters most in `write_resource`, where it is the key a
     * brand-new resource is created under: a wrong value there produces a resource the Designer
     * cannot open, silently.
     */
    const val DEFAULT_JSON_KEY: String = "config.json"
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
