package io.colens.mcp.gateway.tools

import com.inductiveautomation.ignition.common.alarming.AlarmFilter
import com.inductiveautomation.ignition.common.logging.Level
import com.inductiveautomation.ignition.common.logging.LogQueryConfig
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.Tool
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonArrayOfStrings
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.optBoolean
import io.colens.mcp.common.optInt
import io.colens.mcp.common.optString
import io.colens.mcp.common.put
import io.colens.mcp.common.requireString
import io.colens.mcp.common.schema
import io.colens.mcp.common.stringList
import io.colens.mcp.common.toJsonValue
import io.colens.mcp.gateway.licensing.TrialResetter
import io.colens.mcp.gateway.licensing.TrialWatchdog
import java.time.Instant

class SystemTools(private val context: GatewayContext) {

    private val trial = TrialResetter(context)

    fun tools(): List<Tool> = listOf(
        gatewayInfo(),
        listModules(),
        queryLogs(),
        listActiveAlarms(),
        runScript(),
        resetTrial(),
    )

    private fun gatewayInfo() = Tool(
        name = "gateway_info",
        title = "Gateway info",
        description = "Overall gateway status: version, edition, redundancy role, running state and " +
            "licensed tag usage. Start here when diagnosing a gateway you don't know.",
        inputSchema = schema(),
        handler = {
            jsonObject {
                put("state", context.state?.toString())
                put("stateMessage", context.stateMessage)
                put("version", gatewayVersion())
                put("activated", runCatching { context.licenseManager?.isActivated }.getOrNull())
                put("demoTimeRemaining", runCatching {
                    context.licenseManager?.demoTimeRemaining
                }.getOrNull())
                // Enough for a caller seeing demoTimeRemaining at 0 to conclude reset_trial is the
                // fix, and for a human to see why a gateway has been up for days on a 2-hour trial.
                put("licenseMode", trial.licenseMode()?.name)
                put("trialExpired", trial.trialExpired())
                put("trialWatchdog", TrialWatchdog.enabled())
                put("redundancyRole", runCatching {
                    context.redundancyManager?.currentState?.toString()
                }.getOrNull())
                put("isMaster", runCatching { context.redundancyManager?.isMaster }.getOrNull())
                put("moduleCount", context.moduleManager?.moduleCount)
                put("tagProviders", jsonArrayOfStrings(context.tagManager.tagProviderNames))
                put("projects", jsonArrayOfStrings(context.projectManager.names))
                put("licensedTagCount", runCatching { context.tagManager.licensedTagCount }.getOrNull())
                put("licensedTagLimit", runCatching { context.tagManager.licensedTagLimit }.getOrNull())
            }
        },
    )

    /**
     * The running platform version. `GatewayContext` exposes no accessor for it and the
     * `ignition.version` system property isn't set on a normal gateway, so fall back to the
     * install descriptor — `SystemManager.getLibDir()` gives us that path through a public API.
     * Returns null rather than throwing; a missing version shouldn't fail the whole tool.
     */
    private fun gatewayVersion(): String? {
        System.getProperty("ignition.version")?.let { return it }
        return runCatching {
            val installInfo = context.systemManager.libDir.resolve("install-info.txt")
            installInfo.takeIf { it.isFile }
                ?.readLines()
                ?.firstOrNull { it.startsWith("gateway.version=") }
                ?.substringAfter('=')
                ?.trim()
        }.getOrNull()
    }

    private fun listModules() = Tool(
        name = "list_modules",
        title = "List modules",
        description = "Lists installed gateway modules with version and running state, including any " +
            "fault reason for modules that failed to start.",
        inputSchema = schema(),
        handler = {
            jsonObject {
                put("modules", jsonArrayOf(context.moduleManager.modules.map { module ->
                    jsonObject {
                        put("id", module.info?.id)
                        put("name", module.info?.name)
                        put("version", module.info?.version?.toString())
                        put("state", module.state?.toString())
                        put("faultReason", module.faultReason?.message)
                    }
                }))
            }
        },
    )

    private fun queryLogs() = Tool(
        name = "query_logs",
        title = "Query gateway logs",
        description = "Searches the gateway's log. This is usually the fastest way to find out why " +
            "something is failing. Narrow with minLevel and searchTerms rather than pulling everything.",
        inputSchema = schema {
            enumString(
                name = "minLevel",
                description = "Minimum severity to include.",
                values = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"),
                default = "WARN",
            )
            stringArray("loggers", "Only include these logger names (exact match).")
            stringArray("searchTerms", "Only include events whose message contains these terms.")
            integer("rangeMinutes", "Look back this many minutes from now.", default = 60)
            integer("limit", "Maximum events to return.", default = 100)
        },
        handler = { args ->
            val minLevel = args.optString("minLevel")?.let { name ->
                runCatching { Level.valueOf(name.uppercase()) }.getOrNull()
                    ?: throw McpArgumentException("Unknown log level '$name'")
            } ?: Level.WARN

            val end = Instant.now()
            val start = end.minusSeconds(args.optInt("rangeMinutes", 60) * 60L)

            val config = LogQueryConfig().apply {
                this.minLevel = minLevel
                limit = args.optInt("limit", 100)
                setTimeRange(start.toEpochMilli(), end.toEpochMilli())
                args.stringList("loggers").forEach { addAllowedLogger(it) }
                args.stringList("searchTerms").forEach { addSearchTerm(it) }
            }

            val results = context.loggingManager.queryLogEvents(config)

            jsonObject {
                put("minLevel", minLevel.name)
                put("startTime", start.toString())
                put("endTime", end.toString())
                put("count", results.events?.size ?: 0)
                put("estimatedTotal", results.estimatedTotalResults)
                put("events", jsonArrayOf(results.events.orEmpty().map { event ->
                    jsonObject {
                        put("timestamp", Instant.ofEpochMilli(event.timestamp).toString())
                        put("level", event.level?.name)
                        put("logger", event.loggerName)
                        put("message", event.message)
                        event.exception?.takeIf { it.isNotEmpty() }?.let {
                            put("exception", jsonArrayOfStrings(it.toList()))
                        }
                    }
                }))
            }
        },
    )

    private fun listActiveAlarms() = Tool(
        name = "list_active_alarms",
        title = "List active alarms",
        description = "Returns the gateway's current alarm status — what is active and what is " +
            "unacknowledged right now.",
        inputSchema = schema {
            integer("limit", "Maximum alarms to return.", default = 200)
        },
        handler = { args ->
            val limit = args.optInt("limit", 200)
            val results = context.alarmManager.queryStatus(AlarmFilter())
            val alarms = results.toList()

            jsonObject {
                put("total", alarms.size)
                put("truncated", alarms.size > limit)
                put("alarms", jsonArrayOf(alarms.take(limit).map { alarm ->
                    jsonObject {
                        put("name", alarm.name)
                        put("source", alarm.source?.toString())
                        put("displayPath", alarm.displayPathOrSource)
                        put("label", alarm.label)
                        put("priority", alarm.priority?.name)
                        put("state", alarm.state?.toString())
                        put("acked", alarm.isAcked)
                        put("cleared", alarm.isCleared)
                        put("shelved", alarm.isShelved)
                        put("activeTime", alarm.activeData?.timestamp
                            ?.let { Instant.ofEpochMilli(it).toString() })
                    }
                }))
            }
        },
    )

    private fun runScript() = Tool(
        name = "run_script",
        title = "Run a Jython script (gateway scope)",
        description = "Executes Python (Jython) in gateway scope with the project's script context, " +
            "giving access to the whole system.* API. This is arbitrary code execution on the " +
            "gateway — prefer a purpose-built tool when one exists, and read the script back to the " +
            "user before running it. Assign to a variable named 'result' to return a value.",
        inputSchema = schema {
            string("script", "Jython source to execute.", required = true)
            string("project", "Project whose script context to run in. Defaults to the first project.")
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val source = args.requireString("script")
            val project = args.optString("project")
                ?: context.projectManager.names.firstOrNull()
                ?: throw McpArgumentException("No projects on this gateway; specify 'project'.")

            val scriptManager = context.projectManager.getProjectScriptManager(project)
                ?: throw McpArgumentException("No script manager for project '$project'")

            val locals = scriptManager.createLocalsMap()
            scriptManager.runCode(source, locals, "mcp-run-script")

            jsonObject {
                put("project", project)
                put("result", toJsonValue(locals.__finditem__("result")?.toString()))
            }
        },
    )

    private fun resetTrial() = Tool(
        name = "reset_trial",
        title = "Reset the gateway trial timer",
        description = "Restarts the gateway's two-hour trial countdown — the same action as the " +
            "'Reset Trial' button on the gateway home page. By default this only works once the " +
            "trial has actually expired, which is the rule Ignition itself enforces; pass " +
            "force=true to top the timer up mid-session. Refused on an activated gateway, where " +
            "there is no trial to reset. Use this when gateway_info shows demoTimeRemaining at 0 " +
            "and tags, history or Perspective have stopped working.",
        inputSchema = schema {
            boolean(
                "force",
                "Reset even if the trial hasn't expired yet, topping the timer back up to two hours.",
                default = false,
            )
        },
        readOnly = false,
        destructive = true,
        handler = { args ->
            val outcome = trial.reset(force = args.optBoolean("force", false))
            jsonObject {
                put("reset", outcome.reset)
                put("reason", outcome.reason)
                put("forced", outcome.forced)
                put("licenseMode", outcome.licenseMode)
                put("trialExpired", outcome.trialExpired)
                put("secondsBefore", outcome.secondsBefore)
                put("secondsAfter", outcome.secondsAfter)
            }
        },
    )
}
