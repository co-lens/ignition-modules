package io.colens.mcp.common

/**
 * `-Dmcp.devMode=true` — the single switch that drops every credential this module checks.
 *
 * On, the gateway serves both `/data/mcp/mcp` and `/data/mcp/mcp-readonly` without a bearer secret,
 * the Designer bridge stops requiring its own, the Origin allowlist is ignored, `save_project`
 * registers, and the trial watchdog runs. Off — the default — nothing here has any effect.
 *
 * It lives in `common` rather than beside the gateway's other properties because the Designer runs
 * in its own JVM: a `mcp.gateway.*` property set in `ignition.conf` cannot reach it. One name, set
 * in both places.
 *
 * On this line the flag buys less than it does on 8.3, where it replaces an API key plus a custom
 * security level. Here the credential is already a `-D` property, so dev mode only saves you
 * inventing a secret — and it is correspondingly easier to leave on by accident. Every scope that
 * acts on it logs a WARN saying so.
 */
object DevMode {

    const val PROPERTY: String = "mcp.devMode"

    /** Tolerant of the whitespace a shell-quoted `-D` can leave behind, as `mcp.designer.allowSave` is. */
    fun enabled(): Boolean =
        System.getProperty(PROPERTY)?.trim()?.equals("true", ignoreCase = true) == true
}
