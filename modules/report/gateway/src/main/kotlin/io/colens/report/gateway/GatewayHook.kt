package io.colens.report.gateway

import com.inductiveautomation.ignition.common.licensing.LicenseState
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.report.common.Constants
import org.slf4j.LoggerFactory
import java.util.Optional

/**
 * Gateway hook for the Excel reporting module.
 *
 * Two mount points, and they are not the same mechanism:
 *
 *   /data/report/...  JSON routes, mounted here via mountRouteHandlers
 *   /res/report/...   the Excel add-in's static bundle, served by the platform from the
 *                     resource folder named by getMountedResourceFolder
 *
 * Both derive their path segment from getMountPathAlias.
 */
class GatewayHook : AbstractGatewayModuleHook() {

    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var context: GatewayContext

    override fun setup(context: GatewayContext) {
        this.context = context
    }

    override fun startup(activationState: LicenseState) {
        logger.info(
            "Ignition Report {} started. Routes at /data/{}/, add-in at /res/{}/addin/.",
            moduleVersion(),
            Constants.SHORT_MODULE_ID,
            Constants.SHORT_MODULE_ID,
        )
    }

    override fun shutdown() {
        logger.info("Ignition Report stopped.")
    }

    /** Produces the `/data/report/` and `/res/report/` prefixes. */
    override fun getMountPathAlias(): Optional<String> = Optional.of(Constants.SHORT_MODULE_ID)

    /**
     * Serves everything under `src/main/resources/addin` at `/res/report/addin`.
     *
     * (Glob stars are spelled out in prose here on purpose: Kotlin block comments NEST, so a
     * literal slash-star-star inside KDoc opens a second comment and the closing delimiter
     * then only closes the inner one. The file compiles as one giant comment, and the error
     * points at a brace hundreds of lines away.)
     *
     * The platform's own static-resource mechanism rather than a hand-rolled route, and serving
     * the add-in from the gateway is load-bearing rather than merely convenient: it makes the
     * custom functions' HTTP calls SAME-ORIGIN. Microsoft's docs disagree with themselves about
     * whether the JS-only custom-function runtime supports full CORS or only simple CORS — and
     * simple CORS cannot carry a custom header, which would make X-Ignition-API-Token impossible.
     * Same-origin makes the question moot.
     */
    override fun getMountedResourceFolder(): Optional<String> = Optional.of("addin")

    override fun mountRouteHandlers(routes: RouteGroup) {
        ReportRoutes(context, ::moduleVersion).mount(routes)
    }

    override fun isFreeModule(): Boolean = true

    /**
     * Read from this jar's own manifest, written by the module build. Falls back to "dev" for an
     * IDE run, which is the signal that this is not a release artifact.
     */
    private fun moduleVersion(): String = javaClass.`package`?.implementationVersion ?: "dev"
}
