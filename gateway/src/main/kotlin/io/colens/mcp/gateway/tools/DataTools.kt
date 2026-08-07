package io.colens.mcp.gateway.tools

import com.inductiveautomation.ignition.common.Dataset
import com.inductiveautomation.ignition.common.QualifiedPath
import com.inductiveautomation.ignition.common.StreamingDatasetWriter
import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.ignition.common.model.values.QualityCode
import com.inductiveautomation.ignition.common.sqltags.history.AggregationMode
import com.inductiveautomation.ignition.common.sqltags.history.BasicTagHistoryQueryParams
import com.inductiveautomation.ignition.common.sqltags.history.ReturnFormat
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser
import com.inductiveautomation.ignition.gateway.model.GatewayContext
import io.colens.mcp.common.McpArgumentException
import io.colens.mcp.common.Tool
import io.colens.mcp.common.jsonArrayOf
import io.colens.mcp.common.jsonArrayOfStrings
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.optInt
import io.colens.mcp.common.optString
import io.colens.mcp.common.put
import io.colens.mcp.common.requireString
import io.colens.mcp.common.requireStringList
import io.colens.mcp.common.schema
import io.colens.mcp.common.stringList
import io.colens.mcp.common.toJsonValue
import java.time.Duration
import java.time.Instant
import java.util.Date

class DataTools(private val context: GatewayContext) {

    fun tools(): List<Tool> = listOf(
        listDatasources(),
        runQuery(),
        queryTagHistory(),
    )

    private fun listDatasources() = Tool(
        name = "list_datasources",
        title = "List database connections",
        description = "Lists the gateway's database connections and their current status.",
        inputSchema = schema(),
        handler = {
            jsonObject {
                put("datasources", jsonArrayOf(context.datasourceManager.datasources.map { ds ->
                    jsonObject {
                        put("name", ds.name)
                        put("description", ds.description)
                        put("vendor", ds.vendor?.toString())
                        put("status", ds.status?.toString())
                        put("activeConnections", ds.activeConnections)
                        put("maxConnections", ds.maxConnections)
                        put("problem", ds.problemDescription ?: ds.problem?.message)
                    }
                }))
                put("historyProviders", jsonArrayOfStrings(context.tagHistoryManager.tagHistoryProviders))
            }
        },
    )

    private fun runQuery() = Tool(
        name = "run_query",
        title = "Run a SQL query",
        description = "Runs a SELECT against a gateway database connection and returns the rows. " +
            "Use parameters rather than string-concatenating values. Row count is capped by 'limit'. " +
            "This tool refuses anything that isn't a SELECT or WITH — use it for analysis, not for " +
            "changing data.",
        inputSchema = schema {
            string("datasource", "Database connection name (see list_datasources).", required = true)
            string("sql", "SQL to run. Use '?' placeholders for parameters.", required = true)
            stringArray("params", "Values for the '?' placeholders, in order.")
            integer("limit", "Maximum rows to return.", default = 200)
        },
        handler = { args ->
            val datasource = args.requireString("datasource")
            val sql = args.requireString("sql")
            val limit = args.optInt("limit", 200)
            val params = args.stringList("params").toTypedArray<Any?>()

            requireReadOnlySql(sql)

            val dataset = context.datasourceManager.getConnection(datasource).use { connection ->
                connection.runPrepLimitQuery(sql, limit, *params)
            }

            jsonObject {
                put("datasource", datasource)
                put("rowCount", dataset.rowCount)
                put("truncated", dataset.rowCount >= limit)
                put("columns", jsonArrayOfStrings(dataset.columnNames))
                put("rows", datasetRows(dataset))
            }
        },
    )

    private fun queryTagHistory() = Tool(
        name = "query_tag_history",
        title = "Query tag history",
        description = "Queries the historian for one or more tags over a time window. Give either " +
            "'rangeMinutes' (relative to now) or explicit ISO-8601 'startDate'/'endDate'.",
        inputSchema = schema {
            stringArray("paths", "Historical tag paths, e.g. ['[default]Area1/Temp'].", required = true)
            integer("rangeMinutes", "Look back this many minutes from now.", default = 60)
            string("startDate", "ISO-8601 start, e.g. '2026-08-01T00:00:00Z'. Overrides rangeMinutes.")
            string("endDate", "ISO-8601 end. Defaults to now when startDate is given.")
            enumString(
                name = "aggregation",
                description = "Aggregation mode applied to each interval.",
                values = AggregationMode.values().map { it.name },
                default = "Average",
            )
            integer("returnSize", "Number of intervals to return. Use 0 for raw on-change values.", default = 100)
        },
        handler = { args ->
            val paths = args.requireStringList("paths").map { raw ->
                val tagPath = try {
                    if (raw.startsWith("[")) TagPathParser.parse(raw) else TagPathParser.parse("default", raw)
                } catch (e: Exception) {
                    throw McpArgumentException("Invalid tag path '$raw': ${e.message}")
                }
                QualifiedPath.Builder()
                    .setProvider(tagPath.source)
                    .setTag(tagPath.toStringPartial())
                    .build()
            }

            val end = args.optString("endDate")?.let { parseInstant(it, "endDate") } ?: Instant.now()
            val start = args.optString("startDate")?.let { parseInstant(it, "startDate") }
                ?: end.minus(Duration.ofMinutes(args.optInt("rangeMinutes", 60).toLong()))

            val aggregation = args.optString("aggregation")?.let {
                AggregationMode.valueOfCaseInsensitive(it)
                    ?: throw McpArgumentException("Unknown aggregation mode '$it'")
            } ?: AggregationMode.Average

            val params = BasicTagHistoryQueryParams.newBuilder()
                .paths(paths)
                .startDate(Date.from(start))
                .endDate(Date.from(end))
                .returnSize(args.optInt("returnSize", 100))
                .aggregationMode(aggregation)
                .returnFormat(ReturnFormat.Wide)
                .build()

            val collector = DatasetCollector()
            context.tagHistoryManager.queryHistory(params, collector)
            collector.failure?.let { throw it }

            jsonObject {
                put("startDate", start.toString())
                put("endDate", end.toString())
                put("aggregation", aggregation.name)
                put("rowCount", collector.rows.size())
                put("columns", jsonArrayOfStrings(collector.columns))
                put("rows", collector.rows)
            }
        },
    )

    // -----------------------------------------------------------------------

    /**
     * Collects a streaming history result into JSON. The writer contract is only four methods,
     * so this is cheaper than going through an intermediate Dataset.
     */
    private class DatasetCollector : StreamingDatasetWriter {
        var columns: List<String> = emptyList()
        val rows = JsonArray()
        var failure: Exception? = null

        override fun initialize(names: Array<String>, types: Array<Class<*>>, p2: Boolean, p3: Int) {
            columns = names.toList()
        }

        override fun write(values: Array<Any?>, qualities: Array<QualityCode>?) {
            rows.add(jsonObject {
                columns.forEachIndexed { i, name -> put(name, toJsonValue(values.getOrNull(i))) }
            })
        }

        override fun finish() = Unit

        override fun finishWithError(e: Exception) {
            failure = e
        }
    }

    private fun datasetRows(dataset: Dataset): JsonArray {
        val rows = JsonArray()
        for (r in 0 until dataset.rowCount) {
            rows.add(JsonObject().apply {
                for (c in 0 until dataset.columnCount) {
                    add(dataset.getColumnName(c), toJsonValue(dataset.getValueAt(r, c)))
                }
            })
        }
        return rows
    }

    private fun parseInstant(text: String, field: String): Instant = try {
        Instant.parse(text)
    } catch (e: Exception) {
        throw McpArgumentException("'$field' must be ISO-8601 (e.g. 2026-08-01T00:00:00Z), got '$text'")
    }

    /**
     * A cheap guard, not a security boundary — the real boundary is that this tool is read-only
     * and therefore reachable with a read-scoped token. It exists so an agent that drafts an
     * UPDATE by mistake gets a clear refusal instead of mutating a plant database.
     */
    private fun requireReadOnlySql(sql: String) {
        val normalized = sql.trim().trimStart('(').trimStart()
        val firstWord = normalized.takeWhile { !it.isWhitespace() }.uppercase()
        if (firstWord != "SELECT" && firstWord != "WITH") {
            throw McpArgumentException(
                "run_query only executes SELECT/WITH statements; got '$firstWord'."
            )
        }
        if (FORBIDDEN.containsMatchIn(sql)) {
            throw McpArgumentException(
                "run_query rejected this statement: it contains a data-modifying keyword."
            )
        }
    }

    private companion object {
        val FORBIDDEN = Regex(
            "\\b(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|CREATE|GRANT|REVOKE|MERGE|EXEC|EXECUTE)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
