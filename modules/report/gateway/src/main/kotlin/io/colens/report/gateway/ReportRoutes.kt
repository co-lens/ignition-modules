package io.colens.report.gateway

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.report.common.Constants
import org.apache.poi.Version as PoiVersion
import java.time.ZoneId

/**
 * Route table for `/data/report/`.
 *
 * Auth follows the pattern the MCP module arrived at the hard way, and the reasoning is worth
 * repeating rather than referencing: ApiTokenManager.TOKEN_ACCESS and TOKEN_WRITE resolve to the
 * gateway-wide accessPermissions and writePermissions properties, and a permission set that is an
 * empty AllOf is *vacuously true* — it grants everyone, including a caller who presented no token
 * at all. accessPermissions ships as exactly that empty AllOf. So a TOKEN_* strategy must never
 * appear on its own; it is always AND-ed with a check that a token actually validated.
 *
 * /health is deliberately open. It carries no plant data, and an operator diagnosing a broken
 * add-in has no token to present yet — which is precisely when they need it.
 */
class ReportRoutes(
    private val context: GatewayContext,
    private val moduleVersion: () -> String,
) {

    fun mount(routes: RouteGroup) {
        routes.newRoute("/health")
            .method(HttpMethod.GET)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .handler { _, _ -> health() }
            .mount()
    }

    /**
     * Reports the POI version found on the classpath.
     *
     * Not decoration. POI is compileOnly against the 4.1.2 the Ignition platform ships in
     * lib/core/common; if a future Ignition moves it, code compiled here fails with
     * NoSuchMethodError at RENDER time — hours after a green build, inside a scheduled job nobody
     * is watching. Surfacing the actual loaded version makes that a five-second check.
     */
    private fun health(): Map<String, Any?> = mapOf(
        "status" to "ok",
        "module" to Constants.MODULE_ID,
        "version" to moduleVersion(),
        "schemaVersion" to Constants.SCHEMA_VERSION,
        "poiVersion" to PoiVersion.getVersion(),
        "addinUrl" to "/res/${Constants.SHORT_MODULE_ID}/addin/taskpane.html",
        "manifestUrl" to "/data/${Constants.SHORT_MODULE_ID}/manifest.xml",
        // The JVM default IS the gateway's zone, and it is what every schedule resolves
        // against. Reported because a schedule that fires an hour late twice a year is
        // otherwise diagnosed by guesswork.
        "gatewayTimezone" to ZoneId.systemDefault().id,
    )
}
