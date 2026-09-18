package io.colens.report.common

import com.inductiveautomation.ignition.common.gson.JsonObject

/**
 * Reads a [DataGroup] from JSON.
 *
 * Hand-written rather than reflective Gson binding, for one reason: the error messages. A report
 * definition arrives from a spreadsheet, and "unknown period kind 'realtive'" is actionable where
 * a Gson type exception is not. Sealed hierarchies also need a discriminator either way, so
 * reflection would not have been free.
 *
 * Lives in `common` because the SAME JSON is read twice — once from an HTTP body when the add-in
 * refreshes, and once out of a workbook's custom XML part when a schedule fires with no Excel
 * running. Two readers would drift, and the drift would only show at 06:00.
 */
object DataGroupCodec {

    class MalformedGroup(message: String) : Exception(message)

    fun read(json: JsonObject): DataGroup {
        val columns = json.getAsJsonArray("columns")
            ?: throw MalformedGroup("'columns' is required and must be a non-empty array.")

        return try {
            DataGroup(
                id = json.str("id") ?: "group",
                name = json.str("name") ?: "Group",
                period = readPeriod(
                    json.getAsJsonObject("period") ?: throw MalformedGroup("'period' is required."),
                ),
                interval = readInterval(json.getAsJsonObject("interval")),
                columns = columns.mapIndexed { index, element ->
                    val column = element.asJsonObject
                    Column(
                        path = column.str("path")
                            ?: throw MalformedGroup("columns[$index].path is required."),
                        aggregate = column.str("aggregate") ?: "Average",
                        heading = column.str("heading"),
                    )
                },
                historian = json.str("historian"),
                tagProvider = json.str("tagProvider"),
                leadTimeSeconds = json.get("leadTimeSeconds")?.takeIf { !it.isJsonNull }?.asLong ?: 0,
                includeBounds = json.get("includeBounds")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                fillMode = json.str("fillMode"),
            )
        } catch (e: IllegalArgumentException) {
            // The data class's own require() checks — empty columns, the 40-column cap.
            throw MalformedGroup(e.message ?: "The data group is not valid.")
        }
    }

    /** Defaults to hourly. A group with no interval is far more likely an oversight than a request
     *  for every stored value, and raw is the expensive answer to guess at. */
    fun readInterval(json: JsonObject?): Interval {
        if (json == null) return Interval.Every(Duration(1, DurationUnit.HOUR))

        return when (val kind = json.str("kind")?.lowercase() ?: "every") {
            "all" -> Interval.All
            "count" -> Interval.Count(
                json.get("count")?.asInt ?: throw MalformedGroup("interval.count is required."),
            )
            "every" -> Interval.Every(
                readDuration(
                    json.getAsJsonObject("duration") ?: throw MalformedGroup("interval.duration is required."),
                ),
            )
            else -> throw MalformedGroup("Unknown interval kind '$kind'. Use 'every', 'count' or 'all'.")
        }
    }

    fun readPeriod(json: JsonObject): Period = when (val kind = json.str("kind")?.lowercase() ?: "relative") {
        "relative" -> Period.Relative(
            duration = readDuration(json.requireObject("duration")),
            anchor = readAnchor(json),
            startAt = readTimeOfDay(json.getAsJsonObject("startAt")),
        )

        "offset" -> Period.Offset(
            duration = readDuration(json.requireObject("duration")),
            anchor = readAnchor(json),
            offset = readDuration(json.requireObject("offset")),
            startAt = readTimeOfDay(json.getAsJsonObject("startAt")),
        )

        "absolute" -> Period.Absolute(
            start = json.str("start") ?: throw MalformedGroup("period.start is required."),
            end = json.str("end") ?: throw MalformedGroup("period.end is required."),
        )

        "variable" -> Period.Variable(
            startVariable = json.str("startVariable")
                ?: throw MalformedGroup("period.startVariable is required."),
            endVariable = json.str("endVariable")
                ?: throw MalformedGroup("period.endVariable is required."),
        )

        else -> throw MalformedGroup(
            "Unknown period kind '$kind'. Use 'relative', 'offset', 'absolute' or 'variable'.",
        )
    }

    private fun readDuration(json: JsonObject): Duration {
        val unit = json.str("unit") ?: throw MalformedGroup("A duration needs a 'unit'.")
        return Duration(
            count = json.get("count")?.asLong ?: throw MalformedGroup("A duration needs a 'count'."),
            unit = try {
                DurationUnit.valueOf(unit.uppercase())
            } catch (e: IllegalArgumentException) {
                throw MalformedGroup(
                    "Unknown duration unit '$unit'. Valid values: " +
                        DurationUnit.entries.joinToString(", ") { it.name.lowercase() } + ".",
                )
            },
        )
    }

    private fun readAnchor(json: JsonObject): PeriodAnchor {
        val anchor = json.str("anchor") ?: "current"
        return try {
            PeriodAnchor.valueOf(anchor.uppercase())
        } catch (e: IllegalArgumentException) {
            throw MalformedGroup("Unknown anchor '$anchor'. Use 'current' or 'previous'.")
        }
    }

    private fun readTimeOfDay(json: JsonObject?): TimeOfDay? {
        if (json == null) return null
        return try {
            TimeOfDay(json.get("hour")?.asInt ?: 0, json.get("minute")?.asInt ?: 0)
        } catch (e: IllegalArgumentException) {
            throw MalformedGroup("startAt is not a valid time of day: ${e.message}")
        }
    }

    private fun JsonObject.str(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.requireObject(name: String): JsonObject =
        getAsJsonObject(name) ?: throw MalformedGroup("period.$name is required.")
}
