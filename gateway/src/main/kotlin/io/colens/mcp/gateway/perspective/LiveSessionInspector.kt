package io.colens.mcp.gateway.perspective

import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.perspective.common.config.ComponentConfig
import com.inductiveautomation.perspective.gateway.api.Component
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext
import com.inductiveautomation.perspective.gateway.model.ViewModel
import com.inductiveautomation.perspective.gateway.session.InternalSession
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.put
import io.colens.mcp.common.toJsonValue
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Reads running Perspective sessions.
 *
 * **Every use of Perspective's session and model internals is confined to this file.** Those
 * types (`InternalSession`, `PageModel`, `ViewModel`) are public but sit below the documented API
 * surface and could shift between Ignition releases; keeping them here means a future break is
 * one file to fix, and the two tools backed by it can fail without taking the rest down.
 *
 * Note there is no reflection here. Flint spends ~34 KB of Java reaching into `ComponentModel`'s
 * protected fields; walking the `ComponentConfig` tree alongside the live `Component` tree gets
 * the same information through public methods.
 */
class LiveSessionInspector(private val perspective: () -> PerspectiveContext?) {

    private val logger = LoggerFactory.getLogger("mcp.Perspective.Sessions")

    private fun context(): PerspectiveContext =
        perspective() ?: throw McpArgumentException("Perspective is not running on this gateway")

    /** Sessions, serialized with Perspective's own Gson so the shape matches the status page. */
    fun listSessions(projectFilter: String?): JsonObject {
        val ctx = context()
        val gson = ctx.sharedGson
        val monitor = ctx.sessionMonitor

        val sessions = JsonArray()
        monitor.sessionInfos.forEach { info ->
            val json = try {
                gson.toJsonTree(info).takeIf { it.isJsonObject }?.asJsonObject
            } catch (t: Throwable) {
                logger.debug("Could not serialize session info: {}", t.toString())
                null
            } ?: return@forEach

            if (projectFilter != null && json.get("project")?.asString != projectFilter) return@forEach
            sessions.add(json)
        }

        return jsonObject {
            put("count", sessions.size())
            put("pageCount", monitor.pageCount)
            put("viewCount", monitor.viewCount)
            put("componentCount", monitor.componentCount)
            put("bindingCount", monitor.bindingCount)
            put("sessions", sessions)
        }
    }

    /**
     * Walks a running view and reports, for every configured property, its binding and its
     * **current value and quality**.
     *
     * Perspective surfaces binding failures as quality overlays rather than exceptions, so a bad
     * quality next to the binding that produced it is usually the entire diagnosis.
     */
    fun diagnoseView(sessionId: String, viewFilter: String?): JsonObject {
        val session = findSession(sessionId)

        val views = JsonArray()
        var badCount = 0

        session.pages.forEach { page ->
            page.views.forEach { view ->
                val viewPath = viewPathOf(view)
                if (viewFilter != null && !viewPath.contains(viewFilter, ignoreCase = true)) return@forEach

                val properties = JsonArray()
                try {
                    walk(view.config?.root, view.rootContainer, "root", properties)
                } catch (t: Throwable) {
                    logger.debug("Could not walk view {}: {}", viewPath, t.toString())
                }

                badCount += properties.count {
                    it.asJsonObject.get("qualityGood")?.let { g -> g.isJsonPrimitive && !g.asBoolean } == true
                }

                views.add(jsonObject {
                    put("view", viewPath)
                    put("pageId", page.id)
                    put("propertyCount", properties.size())
                    put("properties", properties)
                })
            }
        }

        return jsonObject {
            put("sessionId", sessionId)
            put("project", session.projectName)
            put("viewCount", views.size())
            put("badQualityCount", badCount)
            put("views", views)
            if (views.size() == 0) {
                put("note", "No open views matched. Sessions only hold views that are currently displayed.")
            }
        }
    }

    // -----------------------------------------------------------------------

    private fun findSession(sessionId: String): InternalSession {
        val monitor = context().sessionMonitor
        val uuid = try {
            UUID.fromString(sessionId)
        } catch (e: IllegalArgumentException) {
            throw McpArgumentException(
                "'$sessionId' is not a session id. Call perspective_list_sessions to get one."
            )
        }
        return monitor.findSession(uuid).orElse(null)
            ?: throw McpArgumentException("No running session with id '$sessionId'")
    }

    private fun viewPathOf(view: ViewModel): String =
        try {
            view.id?.resourcePath ?: view.name ?: "?"
        } catch (t: Throwable) {
            "?"
        }

    /**
     * Walks the static config tree and the live component tree together. The config supplies
     * *which* properties are configured; the live component supplies what they currently hold.
     */
    private fun walk(config: ComponentConfig?, component: Component?, path: String, into: JsonArray) {
        if (config == null) return

        val propConfig = config.propConfig
        propConfig?.properties?.forEach { key ->
            val propertyConfig = propConfig.findConfig(key).orElse(null)
            val binding = propertyConfig?.binding

            val live = try {
                component?.getPropertyTreeOf(key.scope)?.read(key.path)?.orElse(null)
            } catch (t: Throwable) {
                null
            }

            // A property with neither a binding nor a change script isn't interesting here.
            if (binding == null && propertyConfig?.onChange == null) return@forEach

            into.add(jsonObject {
                put("component", path)
                put("property", key.toString())
                put("bindingType", binding?.type)
                put("bindingConfig", binding?.config)
                put("bindingEnabled", binding?.let { runCatching { it.isEnabled }.getOrNull() })
                put("transformCount", binding?.transforms?.size)
                put("hasChangeScript", propertyConfig?.onChange != null)
                put("value", toJsonValue(live?.value))
                put("quality", live?.quality?.toString())
                put("qualityGood", live?.quality?.isGood)
                put("timestamp", live?.timestamp?.toInstant()?.toString())
            })
        }

        config.children?.forEachIndexed { index, childConfig ->
            val childComponent = try {
                component?.getChild(index)?.orElse(null)
            } catch (t: Throwable) {
                null
            }
            val name = childConfig.name ?: index.toString()
            walk(childConfig, childComponent, "$path/$name", into)
        }
    }
}
