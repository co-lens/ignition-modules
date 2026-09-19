package io.colens.report.common

import com.inductiveautomation.ignition.common.gson.Gson
import com.inductiveautomation.ignition.common.gson.JsonObject
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The golden period cases, shared BYTE-IDENTICALLY with the add-in's Vitest suite
 * (fixtures/period-cases.json in the report repo).
 *
 * Period resolution exists in two languages because the gateway is authoritative while the
 * taskpane needs an instant preview. Two implementations of the same rules drift silently — both
 * compile, both look right, and the only symptom is an interactive refresh and a 06:00 email
 * covering different hours, each plausible alone. This fixture is what turns that into a red test.
 */
class PeriodResolverTest : StringSpec({

    val fixture = Gson().fromJson(
        checkNotNull(javaClass.getResourceAsStream("/period-cases.json")) {
            "period-cases.json is missing from test resources — it must stay in sync with the add-in repo."
        }.reader(),
        JsonObject::class.java,
    )

    val zone = ZoneId.of(fixture.get("zone").asString)
    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun at(text: String): ZonedDateTime = LocalDateTime.parse(text).atZone(zone)

    for (element in fixture.getAsJsonArray("cases")) {
        val case = element.asJsonObject
        val name = case.get("name").asString

        name {
            val resolved = PeriodResolver.resolve(
                period = parsePeriod(case.getAsJsonObject("period")),
                now = at(case.get("now").asString),
                clampEndToNow = case.get("clampEndToNow").asBoolean,
            )
            val expected = case.getAsJsonObject("expect")
            resolved.start.format(format) shouldBe expected.get("start").asString
            resolved.end.format(format) shouldBe expected.get("end").asString
        }
    }
})

private fun parsePeriod(json: JsonObject): Period {
    fun duration(key: String): Duration = json.getAsJsonObject(key).let {
        Duration(it.get("count").asLong, DurationUnit.valueOf(it.get("unit").asString.uppercase()))
    }

    fun anchor(): PeriodAnchor = PeriodAnchor.valueOf(json.get("anchor").asString.uppercase())

    fun align(): Boolean = json.get("align")?.asBoolean ?: false

    fun startAt(): TimeOfDay? = json.getAsJsonObject("startAt")?.let {
        TimeOfDay(it.get("hour").asInt, it.get("minute").asInt)
    }

    return when (val kind = json.get("kind").asString) {
        "relative" -> Period.Relative(duration("duration"), anchor(), startAt(), align())
        "offset" -> Period.Offset(duration("duration"), anchor(), duration("offset"), startAt(), align())
        "absolute" -> Period.Absolute(json.get("start").asString, json.get("end").asString)
        "variable" -> Period.Variable(json.get("startVariable").asString, json.get("endVariable").asString)
        else -> error("Unknown period kind '$kind' in the fixture.")
    }
}
