package io.colens.mcp.common

/**
 * `-Dmcp.devMode=true` — the switch that drops every credential the *gateway* checks.
 *
 * On, the gateway serves both `/data/mcp/mcp` and `/data/mcp/mcp-readonly` to anyone who can reach
 * the web port, the Origin allowlist is ignored, `save_project` registers, and the trial watchdog
 * runs. Off — the default — nothing here has any effect and the module behaves exactly as it did
 * before this existed.
 *
 * It deliberately does **not** touch the Designer bridge's credential. That bridge requires none by
 * default, so the only thing a bypass could still do there is ignore a [DesignerAuth] secret an
 * operator pinned on purpose — which is the wrong way round. Turning off Origin checking is the
 * part that matters on a Designer: that allowlist is what keeps a web page out of an endpoint which
 * otherwise needs no credential.
 *
 * It lives in `common` rather than beside the gateway's other properties because the Designer runs
 * in its own JVM: a `mcp.gateway.*` property set in `ignition.conf` cannot reach it. One name, set
 * in both places.
 *
 * Every scope that acts on this logs a WARN saying so. That is the whole safety mechanism — the
 * flag is invisible in the module's behaviour until you look at what it will answer, so the log
 * line is the only thing that tells an operator the gateway is open.
 */
object DevMode {

    const val PROPERTY: String = "mcp.devMode"

    /** Tolerant of the whitespace a shell-quoted `-D` can leave behind, as `mcp.designer.allowSave` is. */
    fun enabled(): Boolean =
        System.getProperty(PROPERTY)?.trim()?.equals("true", ignoreCase = true) == true
}
