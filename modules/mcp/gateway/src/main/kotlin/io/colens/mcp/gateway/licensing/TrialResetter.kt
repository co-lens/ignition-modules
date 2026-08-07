package io.colens.mcp.gateway.licensing

import com.inductiveautomation.ignition.common.licensing.LicenseMode
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.mcp.common.McpArgumentException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a reset attempt did.
 *
 * [reset] false with a [reason] is a normal answer — "the trial hasn't expired yet" is information,
 * not a failure. A broken reflective path throws instead, so the caller sees an error rather than a
 * silent no-op.
 */
data class TrialResetOutcome(
    val reset: Boolean,
    val reason: String,
    val forced: Boolean,
    val licenseMode: String?,
    val trialExpired: Boolean?,
    val secondsBefore: Int?,
    val secondsAfter: Int?,
)

/**
 * Resets the gateway's two-hour trial timer — the same thing the "Reset Trial" button on the
 * gateway home page does, by the same mechanism: Ignition's own `LicensingRoutes.resetTrial`
 * reaches `LicenseManagerImpl.resetTrial()` reflectively and guards it with the identical
 * "only once the timer has run out" rule implemented below.
 *
 * Reflection is required, not chosen. [GatewayContext.getLicenseManager] is typed as the
 * `LicenseManager` interface from `gateway-api`, which declares only reads (`isActivated`,
 * `getPlatformLicenseState`, `getDemoTimeRemaining`). `resetTrial()` lives on the runtime
 * implementation in `gateway-<version>.jar`, which is not a Maven artifact and is not on our
 * compile classpath — see `gateway/build.gradle.kts`, `compileOnly(libs.bundles.gateway)`. Both
 * the implementation class and the method are public, so [Class.getMethod] and [Method.invoke]
 * need no `setAccessible`.
 *
 * **Every reflective reach into the platform's licensing internals is confined to this file**, the
 * same rule `perspective/LiveSessionInspector.kt` states for Perspective's internals. If a future
 * Ignition renames or removes the method, this is the one place to fix, and the failure surfaces as
 * a clear error naming the actual implementation class rather than as a crash.
 */
class TrialResetter(private val context: GatewayContext) {

    private val logger = LoggerFactory.getLogger(LOGGER_NAME)

    /** Seconds of trial left, or null if this gateway doesn't report one. */
    fun demoTimeRemaining(): Int? =
        runCatching { context.licenseManager?.demoTimeRemaining }.getOrNull()

    fun licenseMode(): LicenseMode? =
        runCatching { context.licenseManager?.platformLicenseState?.licenseMode }.getOrNull()

    fun trialExpired(): Boolean? =
        runCatching { context.licenseManager?.platformLicenseState?.isTrialExpired }.getOrNull()

    fun isActivated(): Boolean =
        runCatching { context.licenseManager?.isActivated }.getOrNull() == true

    /**
     * Resets the trial if the guard allows it.
     *
     * The guard mirrors Ignition's own (`if (getDemoTimeRemaining() > 0) sendError(403)`): only
     * reset once the timer has actually run out. [force] lifts that so a developer can top the
     * timer up mid-session rather than waiting for the gateway to fall over — but nothing lifts the
     * activated-gateway refusal.
     *
     * Synchronized because the MCP tool and [TrialWatchdog] can both call it. Two resets are
     * harmless in themselves, but the before/after in the log would lie.
     */
    @Synchronized
    fun reset(force: Boolean): TrialResetOutcome {
        val licenseManager = context.licenseManager
            ?: throw McpArgumentException("This gateway has no license manager.")

        val mode = licenseMode()
        val before = demoTimeRemaining()

        fun outcome(reset: Boolean, reason: String, after: Int?) = TrialResetOutcome(
            reset = reset,
            reason = reason,
            forced = force,
            licenseMode = mode?.name,
            trialExpired = trialExpired(),
            secondsBefore = before,
            secondsAfter = after,
        )

        // Refused on an activated gateway even when force is set. There is no trial to reset, so
        // the call would be nothing but poking at a licensed system's licensing internals — the one
        // thing this class must never do. Checked two ways because on an edition we haven't seen,
        // either one alone might be the meaningful signal.
        if (isActivated() || mode == LicenseMode.Activated) {
            return outcome(false, "Gateway is activated; there is no trial to reset.", before)
        }
        if (before == null) {
            return outcome(false, "This gateway reports no trial timer; nothing to reset.", null)
        }
        if (before > 0 && !force) {
            return outcome(
                false,
                "Trial still has ${before}s remaining. Ignition only resets an expired trial; " +
                    "pass force=true to top it up now.",
                before,
            )
        }

        // Deliberately not gated on redundancyManager.isMaster: licence state is per-node and a
        // backup running on an expired trial needs resetting exactly as much as the master does.
        // Gating on master would silently leave the backup dead.
        invokeNoArg(licenseManager, RESET_TRIAL)
        val after = demoTimeRemaining()
        logger.info(
            "Trial reset ({}): {}s -> {}s remaining.",
            if (force) "forced" else "expired",
            before,
            after,
        )
        return outcome(true, "Trial reset.", after)
    }

    /**
     * The implementation class and method are public today, so the `setAccessible` retry below
     * exists only for a future Ignition that makes the class non-public while keeping the method.
     */
    private fun invokeNoArg(target: Any, name: String) {
        val method: Method = runCatching { target.javaClass.getMethod(name) }.getOrNull()
            ?: throw McpArgumentException(
                "This gateway's license manager (${target.javaClass.name}) has no $name(); " +
                    "Ignition's internal licensing API has changed and this tool needs updating."
            )
        try {
            method.invoke(target)
        } catch (e: IllegalAccessException) {
            runCatching { method.isAccessible = true }
            runCatching { method.invoke(target) }.getOrElse {
                throw McpArgumentException("$name() is not accessible from this module: $e")
            }
        } catch (e: InvocationTargetException) {
            throw McpArgumentException("$name() failed: ${e.targetException}")
        }
    }

    internal companion object {
        const val LOGGER_NAME = "mcp.Gateway.Trial"
        const val RESET_TRIAL = "resetTrial"
    }
}

/**
 * Optionally auto-resets the trial when it runs out, so a dev gateway doesn't stop mid-session.
 *
 * **On the ethics of this, honestly.** Resetting the trial is a first-party Ignition feature: there
 * is a "Reset Trial" button on the gateway home page, and [TrialResetter] calls the same
 * `LicenseManagerImpl.resetTrial()` that Ignition's own web route calls, under the same "only once
 * the timer has run out" guard. Automating a button is not circumventing a licence check. But doing
 * it *forever, unattended* turns a deliberately time-boxed trial into an unbounded one, and that is
 * a different thing from clicking a button when you notice the gateway has stopped. So this is:
 *
 *  - off unless someone explicitly asks for it (`-Dmcp.trialWatchdog=true`),
 *  - refused outright on an activated gateway,
 *  - logged at WARN on every reset, so the log tells the truth about what kept this gateway up.
 *
 * It exists for the develop-test-restart loop against a throwaway gateway. It is not something to
 * enable on anything a customer touches; buy a licence for that.
 */
class TrialWatchdog private constructor(
    private val resetter: TrialResetter,
    private val intervalSeconds: Long,
) {
    private val logger = LoggerFactory.getLogger(TrialResetter.LOGGER_NAME)

    @Volatile private var executor: ScheduledExecutorService? = null

    @Volatile private var task: ScheduledFuture<*>? = null

    private val consecutiveFailures = AtomicInteger()

    fun start() {
        // GatewayContext.getExecutionManager() would also do. A thread we own is preferred here
        // because the watchdog's whole job is to run correctly at the moment the gateway is
        // degraded by trial expiry, independent of whatever the shared pools are doing then.
        val exec = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mcp-trial-watchdog").apply { isDaemon = true }
        }
        task = exec.scheduleWithFixedDelay(::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS)
        executor = exec
        logger.warn(
            "Trial watchdog is ON (-D{}=true, every {}s): this gateway's trial will be reset " +
                "automatically whenever it expires, keeping it running indefinitely. Intended for " +
                "a development gateway only.",
            ENABLED_PROPERTY,
            intervalSeconds,
        )
    }

    fun stop() {
        task?.cancel(false)
        executor?.shutdownNow()
        task = null
        executor = null
    }

    private fun tick() {
        try {
            val remaining = resetter.demoTimeRemaining() ?: return
            if (remaining > 0) return

            val outcome = resetter.reset(force = false)
            if (outcome.reset) {
                logger.warn(
                    "Trial had expired; the watchdog reset it — {}s of trial restored. " +
                        "Disable with -D{}=false.",
                    outcome.secondsAfter,
                    ENABLED_PROPERTY,
                )
            } else {
                // e.g. the gateway was activated while we were running: nothing left to do.
                logger.info("Trial watchdog standing down: {}", outcome.reason)
                stopFromTick()
            }
            consecutiveFailures.set(0)
        } catch (t: Throwable) {
            val failures = consecutiveFailures.incrementAndGet()
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                logger.error(
                    "Trial watchdog failed {} times in a row; giving up. Last failure: {}",
                    failures,
                    t.toString(),
                    t,
                )
                stopFromTick()
            } else {
                logger.warn("Trial watchdog reset attempt failed ({}): {}", failures, t.toString())
            }
        }
    }

    /** Cancel without shutting the pool down from inside its own task. */
    private fun stopFromTick() {
        task?.cancel(false)
        executor?.shutdown()
    }

    companion object {
        /** `-Dmcp.trialWatchdog=true`, named to match `mcp.allowedOrigins` / `mcp.designer.port`. */
        const val ENABLED_PROPERTY = "mcp.trialWatchdog"
        const val INTERVAL_PROPERTY = "mcp.trialWatchdog.intervalSeconds"
        const val DEFAULT_INTERVAL_SECONDS = 30L
        const val MIN_INTERVAL_SECONDS = 5L
        const val MAX_CONSECUTIVE_FAILURES = 3

        fun enabled(): Boolean =
            System.getProperty(ENABLED_PROPERTY)?.trim()?.equals("true", ignoreCase = true) == true

        /**
         * Null when the watchdog shouldn't run at all: not asked for, or an activated gateway where
         * there is no trial to watch. [TrialResetter.reset]'s own guard would refuse anyway; not
         * starting is simply the quieter way to be right.
         */
        fun createIfEnabled(context: GatewayContext, logger: Logger): TrialWatchdog? {
            if (!enabled()) return null

            val resetter = TrialResetter(context)
            if (resetter.isActivated() || resetter.licenseMode() == LicenseMode.Activated) {
                logger.info(
                    "-D{}=true, but this gateway is activated — trial watchdog not started.",
                    ENABLED_PROPERTY,
                )
                return null
            }

            val interval = System.getProperty(INTERVAL_PROPERTY)?.trim()?.toLongOrNull()
                ?.coerceAtLeast(MIN_INTERVAL_SECONDS)
                ?: DEFAULT_INTERVAL_SECONDS
            return TrialWatchdog(resetter, interval)
        }
    }
}
