package io.colens.report.gateway

import com.inductiveautomation.ignition.common.licensing.LicenseState
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.report.common.Constants
import org.slf4j.LoggerFactory
import jakarta.servlet.http.HttpServletResponse
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

    private companion object {
        /** The Office hosts that legitimately embed a task pane. */
        const val FRAME_ANCESTORS =
            "frame-ancestors 'self' https://*.office.com https://*.officeapps.live.com " +
                "https://*.cloud.microsoft https://*.microsoft365.com"
    }


    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var context: GatewayContext

    override fun setup(context: GatewayContext) {
        this.context = context
    }

    override fun startup(activationState: LicenseState) {
        logger.info(
            "Ignition Report {} started. Routes at /data/{}/, add-in at /res/{}/.",
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
     * Serves the CONTENTS of `src/main/resources/addin` at `/res/report` — the folder named
     * here is stripped, not appended.
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

    /**
     * Response headers for the add-in bundle, which the platform otherwise serves with none at all.
     *
     * Two problems, both of which present as the add-in simply not working and neither of which
     * says anything useful:
     *
     * FRAMING. The gateway sends `X-Frame-Options: SAMEORIGIN` on everything, including /res/.
     * Office hosts a task pane in an iframe from its OWN origin (excel.cloud.microsoft), so
     * SAMEORIGIN blocks the add-in from rendering: the pane opens and shows a broken-image
     * placeholder, with nothing in the Office UI to explain it. A `frame-ancestors` policy says
     * precisely who may embed this, which is what SAMEORIGIN was reaching for and getting wrong
     * for this use.
     *
     * CACHING. Nothing sets a cache header, so browsers apply heuristic caching and Office caches
     * hard. A rebuilt add-in then keeps loading the PREVIOUS taskpane.html — which references the
     * previous bundle hash — so a gateway serving new code still hands out old code, indefinitely.
     * That cost a debugging session: the gateway, the vendored copy and the .modl all held the new
     * bundle while Excel loaded the old one.
     *
     * So: entry points must revalidate, hashed assets may cache forever. That split is the whole
     * reason the asset filenames carry a content hash.
     */
    override fun onMountedResourceRequest(path: String, response: HttpServletResponse) {
        response.setHeader("Content-Security-Policy", FRAME_ANCESTORS)
        // Belt and braces: the platform sets SAMEORIGIN, and a header it writes after this would
        // otherwise win. DENY-ing our own override is worse than a duplicate.
        response.setHeader("X-Frame-Options", "")

        if (path.endsWith(".html") || path.endsWith(".json")) {
            response.setHeader("Cache-Control", "no-cache, must-revalidate")
        } else {
            response.setHeader("Cache-Control", "public, max-age=31536000, immutable")
        }
    }

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
