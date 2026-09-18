package io.colens.report.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Planning is where a report's intent becomes a historian query, and most of what can go wrong
 * here is arithmetic that produces a believable answer — a window three times too small, lead time
 * leaking into the output, headings sliding onto the wrong series.
 */
class DataGroupPlanTest : StringSpec({

    val zone = ZoneId.of("America/New_York")
    val now = LocalDateTime.parse("2026-04-12T10:00:00").atZone(zone)
    val defaults = HistorianPaths.Defaults(historian = "Plant", gateway = "gw1", tagProvider = "default")

    fun group(
        columns: List<Column> = listOf(Column("Line1/Flow", "Average")),
        interval: Interval = Interval.Every(Duration(1, DurationUnit.HOUR)),
        leadTimeSeconds: Long = 0,
    ) = DataGroup(
        id = "g1",
        name = "Test",
        period = Period.Relative(Duration(1, DurationUnit.DAY), PeriodAnchor.PREVIOUS, TimeOfDay(8, 0)),
        interval = interval,
        columns = columns,
        leadTimeSeconds = leadTimeSeconds,
    )

    "the period is resolved on the gateway, not sent pre-resolved" {
        val plan = DataGroupPlan.plan(group(), now, defaults)
        plan.periodStart shouldBe LocalDateTime.parse("2026-04-11T08:00:00").atZone(zone)
        plan.periodEnd shouldBe LocalDateTime.parse("2026-04-12T08:00:00").atZone(zone)
    }

    "lead time shifts only the QUERY start, never the reported period" {
        // Conflating the two puts rows from before the period start into the user's sheet.
        val plan = DataGroupPlan.plan(group(leadTimeSeconds = 3600), now, defaults)
        plan.periodStart shouldBe LocalDateTime.parse("2026-04-11T08:00:00").atZone(zone)
        plan.queryStart shouldBe LocalDateTime.parse("2026-04-11T07:00:00").atZone(zone)
    }

    "with no lead time the query start is the period start" {
        val plan = DataGroupPlan.plan(group(), now, defaults)
        plan.queryStart shouldBe plan.periodStart
    }

    "short tag paths are qualified against the defaults" {
        DataGroupPlan.plan(group(), now, defaults).paths shouldBe
            listOf("histprov:Plant:/sys:gw1:/prov:default:/tag:Line1/Flow")
    }

    "one tag with several calculations produces one column each, in order" {
        // The ordinary case for a report, not an edge one.
        val plan = DataGroupPlan.plan(
            group(columns = listOf(
                Column("Line1/Flow", "Minimum"),
                Column("Line1/Flow", "Maximum"),
                Column("Line1/Temp", "SimpleAverage"),
            )),
            now, defaults,
        )
        plan.aggregates shouldBe listOf("Minimum", "Maximum", "SimpleAverage")
        plan.paths.size shouldBe 3
        plan.columnNames shouldBe listOf(
            "Line1/Flow (Minimum)", "Line1/Flow (Maximum)", "Line1/Temp (SimpleAverage)",
        )
    }

    "a supplied heading stays on its own column and does not shift the rest" {
        val plan = DataGroupPlan.plan(
            group(columns = listOf(
                Column("Line1/Flow", "Minimum", heading = "Low"),
                Column("Line1/Flow", "Maximum"),
            )),
            now, defaults,
        )
        plan.columnNames shouldBe listOf("Low", "Line1/Flow (Maximum)")
    }

    "aggregate names are canonicalised, since they come from a spreadsheet" {
        DataGroupPlan.plan(group(columns = listOf(Column("A", "simpleaverage"))), now, defaults)
            .aggregates shouldBe listOf("SimpleAverage")
    }

    "an unknown aggregate names the column that asked for it" {
        val e = shouldThrow<DataGroupPlan.InvalidGroup> {
            DataGroupPlan.plan(group(columns = listOf(Column("Line1/Flow", "Mean"))), now, defaults)
        }
        e.message!!.contains("Line1/Flow") shouldBe true
        e.message!!.contains("Mean") shouldBe true
    }

    "an hourly interval becomes an hour window" {
        DataGroupPlan.plan(group(), now, defaults).windowSize shouldBe (1L to ChronoUnit.HOURS)
    }

    "a quarterly interval becomes THREE months, not one" {
        // Mapping the unit and keeping the count turns "every quarter" into "every month" — a
        // report with three times too many rows, each of which looks perfectly reasonable.
        val plan = DataGroupPlan.plan(
            group(interval = Interval.Every(Duration(1, DurationUnit.QUARTER))), now, defaults,
        )
        plan.windowSize shouldBe (3L to ChronoUnit.MONTHS)
    }

    "two quarters is six months" {
        DataGroupPlan.plan(
            group(interval = Interval.Every(Duration(2, DurationUnit.QUARTER))), now, defaults,
        ).windowSize shouldBe (6L to ChronoUnit.MONTHS)
    }

    "a count interval divides the range instead of setting a window" {
        val plan = DataGroupPlan.plan(group(interval = Interval.Count(24)), now, defaults)
        plan.windowSize.shouldBeNull()
        plan.returnSize shouldBe 24
        plan.raw shouldBe false
    }

    "an All interval is a raw pull with no window" {
        val plan = DataGroupPlan.plan(group(interval = Interval.All), now, defaults)
        plan.windowSize.shouldBeNull()
        plan.returnSize.shouldBeNull()
        plan.raw shouldBe true
    }

    "a group with no columns is rejected at construction" {
        shouldThrow<IllegalArgumentException> { group(columns = emptyList()) }
    }

    "a group beyond the 40-column cap is rejected" {
        shouldThrow<IllegalArgumentException> {
            group(columns = (1..41).map { Column("Tag$it") })
        }
    }

    "an unknown fill mode is rejected rather than silently ignored" {
        shouldThrow<DataGroupPlan.InvalidGroup> {
            DataGroupPlan.plan(group().copy(fillMode = "interpolated"), now, defaults)
        }
    }
})
