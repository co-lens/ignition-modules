package io.colens.report.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import com.inductiveautomation.ignition.common.sqltags.history.AggregationMode

class AggregatesTest : StringSpec({

    "every advertised aggregate name is a real AggregationMode" {
        // The REST surface mirrors system.historian, whose aggregate names ARE the legacy
        // AggregationMode names — LegacyAggregateAdapter is what bridges them. A name in our list
        // that the enum does not have would be accepted by the API and then fail at query time.
        Aggregates.NAMES.forEach { name ->
            AggregationMode.valueOfCaseInsensitive(name) shouldBe AggregationMode.valueOf(name)
        }
    }

    "the list matches AggregationMode exactly, with nothing missing" {
        Aggregates.NAMES.toSet() shouldBe AggregationMode.values().map { it.name }.toSet()
    }

    "aggregate names resolve case-insensitively, because they come from spreadsheet formulas" {
        Aggregates.canonical("average") shouldBe "Average"
        Aggregates.canonical("SIMPLEAVERAGE") shouldBe "SimpleAverage"
        Aggregates.canonical("  stddev  ") shouldBe "StdDev"
    }

    "an unknown aggregate resolves to null rather than a default" {
        // Substituting a default would return a plausible number computed the wrong way, which is
        // worse than an error a user can read.
        Aggregates.canonical("Mean").shouldBeNull()
        Aggregates.canonical("").shouldBeNull()
    }

    "fill modes and return formats resolve case-insensitively too" {
        Aggregates.canonicalFillMode("prev") shouldBe "PREV"
        Aggregates.canonicalReturnFormat("wide") shouldBe "WIDE"
        Aggregates.canonicalFillMode("interpolated").shouldBeNull()
    }
})
