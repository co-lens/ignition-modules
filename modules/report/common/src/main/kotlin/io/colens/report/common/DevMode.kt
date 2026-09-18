package io.colens.report.common

/**
 * `-Dreport.devMode=true` — drops the API-token requirement from every data route.
 *
 * On, anyone who can reach the gateway's web port can query the historian through this module.
 * Off — the default — nothing here has any effect.
 *
 * It exists because the alternative makes the dev loop unusable: without it, bringing up a
 * throwaway gateway means commissioning it, creating an API token in the web UI, and wiring a
 * security level into Gateway Access Permissions before a single curl can succeed. That ritual
 * gets skipped, and what gets skipped instead is testing.
 *
 * The scope of what it exposes is worth being precise about, because it is narrower than the MCP
 * module's equivalent flag: this module has no scripting endpoint and no way to write anything.
 * The exposure is READ access to historical process data. That is still plant data, and a
 * container with this set has no business being reachable from anything but localhost.
 *
 * The WARN logged at startup is the whole safety mechanism. The flag is invisible in the module's
 * behaviour until you look at what it will answer without a credential, so the log line is the
 * only thing that tells an operator this gateway is open.
 */
object DevMode {

    const val PROPERTY: String = "report.devMode"

    /** Tolerant of whitespace a shell-quoted `-D` can leave behind. */
    fun enabled(): Boolean =
        System.getProperty(PROPERTY)?.trim()?.equals("true", ignoreCase = true) == true
}
