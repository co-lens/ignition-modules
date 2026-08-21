package io.colens.mcp.common

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Optional bearer auth for the Designer bridge.
 *
 * Unset — the default — means the bridge accepts every request, including one still carrying a
 * stale `Authorization` header from a previous session. That is deliberate. The bridge binds to
 * loopback, and the secret it used to mint was regenerated on every Designer start, so a client
 * configured with it broke on the next restart. It cost every user a re-paste and bought nothing a
 * same-UID attacker did not already have: the discovery file holding it was readable by that user
 * anyway.
 *
 * What it *did* buy, and what is given up with it: loopback is not UID-scoped. On a shared machine
 * another signed-in user cannot read your discovery file but can still reach your port. This turns
 * "same UID" into "same machine" — which is why the property below exists, and why the docs tell
 * you to set it on a shared host or any bind beyond loopback.
 *
 * `-Dmcp.designer.secret=<value>` opts back in, with that exact value. Pinned by the operator, so
 * it survives a restart by construction.
 *
 * Note the inversion against the 8.1 gateway's `BearerAccessControl`, whose shape this otherwise
 * follows: there an unset secret fails closed, meaning *nobody*. Here it means *everybody*. The
 * difference is the whole point of this class, so it is spelled out rather than left to be
 * inferred from [authorize].
 *
 * Lives in `common` rather than beside the Designer's other code for the same reason [DevMode]
 * does — the Designer runs in its own JVM — and because `:designer` has no test source set, so
 * anything left there cannot be tested.
 */
class DesignerAuth(secret: String?) {

    /** Trimmed, blank-as-unset. Null when the bridge requires no credential. */
    val secret: String? = secret?.trim()?.takeIf { it.isNotEmpty() }

    private val expected: ByteArray? = this.secret?.toByteArray(StandardCharsets.UTF_8)

    /** True when a credential is configured and will be checked. */
    val required: Boolean get() = expected != null

    /** Advisory only, as on 8.1: warned about at startup, never enforced. An operator's call. */
    val secretIsShort: Boolean get() = secret.let { it != null && it.length < MIN_SECRET_LENGTH }

    /**
     * With no secret configured this returns true **without inspecting [header] at all** — a client
     * still sending last session's `Bearer <hex>` has to keep working, and rejecting a credential
     * we do not require would reintroduce exactly the staleness this class removes.
     */
    fun authorize(header: String?): Boolean {
        val want = expected ?: return true
        if (header == null) return false
        if (!header.regionMatches(0, BEARER, 0, BEARER.length, ignoreCase = true)) return false
        val presented = header.substring(BEARER.length).trim().toByteArray(StandardCharsets.UTF_8)
        // Constant-time. The loopback default makes timing fanciful, but this credential exists
        // for the non-loopback case, and a length-or-prefix oracle is how a shared secret leaks.
        return MessageDigest.isEqual(presented, want)
    }

    companion object {
        private const val BEARER = "Bearer "

        /** Opt in to bearer auth on the Designer bridge, with this exact value. */
        const val SECRET_PROPERTY: String = "mcp.designer.secret"

        /** Minimum length before the module complains. Not enforced — an operator's call. */
        const val MIN_SECRET_LENGTH: Int = 32

        /**
         * Read once at Designer startup. The endpoint, the connect dialog and the discovery file
         * must agree on one answer; re-reading the property per request would let them drift, and
         * `run_script` can call `System.setProperty` — the same reasoning the 8.1 gateway records
         * for snapshotting its secrets at construction.
         *
         * Not unit-tested: it is two lines, and covering it would force `System.setProperty` into
         * a suite whose specs can run concurrently. The logic worth testing takes its value
         * through the constructor instead.
         */
        fun fromSystemProperties(): DesignerAuth = DesignerAuth(System.getProperty(SECRET_PROPERTY))
    }
}
