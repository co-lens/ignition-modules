package io.colens.mcp.gateway

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccessControl
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.servlet.http.HttpServletResponse

/**
 * Bearer-secret access control for the Ignition 8.1 line.
 *
 * 8.1 has no `ApiTokenManager` and no managed API tokens of any kind — the only route strategies
 * it ships are Wicket browser-session ones, which are useless for a JSON-RPC POST from a
 * non-browser client. So the read/write endpoint split is carried by two shared secrets supplied
 * as JVM system properties in `ignition.conf`:
 *
 * ```
 * wrapper.java.additional.9=-Dmcp.gateway.readSecret=<32+ random characters>
 * wrapper.java.additional.10=-Dmcp.gateway.writeSecret=<32+ random characters>
 * ```
 *
 * **Read this before deploying.** Unlike an 8.3 API token these secrets are not revocable without
 * restarting the gateway, they are visible in the process table and on the gateway's own status
 * page, and they are shared by every client rather than issued per client. The write secret grants
 * `run_script`, which is arbitrary Jython in gateway scope — that is gateway root. The recommended
 * posture on 8.1 is to set `readSecret` only and leave the write endpoint permanently closed.
 *
 * `-Dmcp.devMode=true` bypasses all of this and serves both endpoints to anyone who can reach the
 * port. On this line that is a small step from an unset secret, which already 401s everything —
 * but it is a large one from a *set* secret, and it hands over `run_script`. Dev gateways only.
 *
 * The compare is constant-time, matching the Designer bridge: these endpoints are typically
 * reachable from the plant network, and a length-or-prefix oracle is exactly how a shared secret
 * leaks.
 */
class BearerAccessControl(
    private val label: String,
    private val accepted: List<ByteArray>,
    /**
     * `-Dmcp.devMode=true`: accept every request, secret or not.
     *
     * Passed in rather than read here, so it is snapshotted at construction for the same reason
     * the secrets are — a later `System.setProperty()`, reachable from `run_script` on the write
     * endpoint, must not be able to open a gateway that started closed.
     */
    private val devMode: Boolean = false,
) : RouteAccessControl {

    /** False when no secret is configured for this endpoint, in which case nothing can pass. */
    val configured: Boolean get() = accepted.isNotEmpty()

    override fun canAccess(ctx: RequestContext): Boolean {
        if (devMode) return true

        // Fail closed. An unset secret means "nobody", never "everybody".
        if (accepted.isEmpty()) return false

        val header = ctx.request?.getHeader("Authorization") ?: return false
        if (!header.regionMatches(0, BEARER, 0, BEARER.length, ignoreCase = true)) return false
        val presented = header.substring(BEARER.length).trim().toByteArray(StandardCharsets.UTF_8)

        // `or`, not `||`: Kotlin's infix `or` on Boolean does not short-circuit, so the number of
        // comparisons performed doesn't depend on which secret matched.
        var ok = false
        for (candidate in accepted) ok = MessageDigest.isEqual(presented, candidate) or ok
        return ok
    }

    /**
     * 401 rather than the framework default. A 403 would tell a client "your credentials are valid
     * but insufficient", which is wrong here — there is exactly one credential per route. The body
     * is JSON-RPC shaped so an MCP client parsing the failure doesn't choke on an HTML error page.
     *
     * Deliberately identical whether the secret was absent or wrong: distinguishing them tells an
     * attacker which half of the problem to work on.
     */
    override fun handleAccessDenied(ctx: RequestContext, res: HttpServletResponse) {
        res.status = 401
        res.setHeader("WWW-Authenticate", "Bearer realm=\"ignition-mcp\"")
        res.contentType = "application/json"
        res.writer.write(
            """{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized ($label)"}}"""
        )
        res.writer.flush()
    }

    companion object {
        private const val BEARER = "Bearer "

        const val READ_SECRET_PROPERTY = "mcp.gateway.readSecret"
        const val WRITE_SECRET_PROPERTY = "mcp.gateway.writeSecret"

        /** Minimum length before the module complains. Not enforced — an operator's call. */
        const val MIN_SECRET_LENGTH = 32

        fun secret(property: String): String? =
            System.getProperty(property)?.trim()?.takeIf { it.isNotEmpty() }

        fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
    }
}
