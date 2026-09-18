package io.colens.report.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * The calculation engine.
 *
 * Every failure here produces a plausible number rather than an error, which is the worst kind: a
 * report nobody questions and everybody acts on. So the cases are built from series whose answers
 * were worked out by hand, and the headline one comes straight from XLReporter's specification.
 *
 * Times are plain millisecond offsets; nothing here needs a calendar.
 */
class CalculationEngineTest : StringSpec({

    fun at(second: Long, value: Double?, good: Boolean = true) = Sample(second * 1000, value, good)

    "XLReporter's worked example: raw 25 versus time-weighted 17.5" {
        /*
         * Verbatim from the History Data Groups specification, and the single most important case
         * in this file — it is the distinction the whole product is sold on.
         *
         * A four-second interval. A value of 10 was in force before it began and holds for the
         * first two seconds; 20 arrives at t+2 and holds one second; 30 arrives at t+3 and holds
         * to the end.
         *
         *   raw           (20 + 30) / 2                = 25
         *   time-weighted (10·2 + 20·1 + 30·1) / 4     = 17.5
         *
         * They differ by a third. Both look entirely reasonable on a printed report.
         */
        val interval = IntervalSamples(
            startMillis = 0,
            endMillis = 4000,
            samples = listOf(at(2, 20.0), at(3, 30.0)),
            seed = at(-5, 10.0),
        )

        CalculationEngine.simpleAverage(interval) shouldBe 25.0
        CalculationEngine.timeWeightedAverage(interval)!! shouldBe (17.5 plusOrMinus 1e-9)
    }

    "without a seed the curve starts late and the answer is quietly wrong" {
        // The reason lead time exists. Same samples, no value carried in from before the interval:
        // the first two seconds contribute nothing and the mean rises from 17.5 to 25.
        val noSeed = IntervalSamples(0, 4000, listOf(at(2, 20.0), at(3, 30.0)))
        CalculationEngine.timeWeightedAverage(noSeed)!! shouldBe (25.0 plusOrMinus 1e-9)
    }

    "a value held for the whole interval averages to itself" {
        val flat = IntervalSamples(0, 60_000, emptyList(), seed = at(-1, 42.0))
        CalculationEngine.timeWeightedAverage(flat)!! shouldBe (42.0 plusOrMinus 1e-9)
    }

    "an empty interval yields null, not zero" {
        // Zero is a measurement. "Nothing was recorded" is not, and a report must not present the
        // second as the first — that is the difference between "no data" and "the tank is empty".
        val empty = IntervalSamples(0, 1000, emptyList())
        CalculationEngine.timeWeightedAverage(empty).shouldBeNull()
        CalculationEngine.simpleAverage(empty).shouldBeNull()
        CalculationEngine.minimum(empty).shouldBeNull()
        CalculationEngine.sum(empty).shouldBeNull()
        CalculationEngine.count(empty) shouldBe 0
    }

    "bad-quality samples are excluded rather than counted as measurements" {
        val interval = IntervalSamples(
            0, 4000,
            listOf(at(1, 10.0), at(2, 999.0, good = false), at(3, 30.0)),
        )
        CalculationEngine.simpleAverage(interval)!! shouldBe (20.0 plusOrMinus 1e-9)
        CalculationEngine.maximum(interval) shouldBe 30.0
        CalculationEngine.count(interval) shouldBe 2
    }

    "extremes and their timestamps" {
        val interval = IntervalSamples(
            0, 10_000,
            listOf(at(1, 5.0), at(2, 50.0), at(3, 1.0), at(4, 50.0), at(5, 20.0)),
        )
        CalculationEngine.minimum(interval) shouldBe 1.0
        CalculationEngine.maximum(interval) shouldBe 50.0
        CalculationEngine.range(interval) shouldBe 49.0
        // FIRST occurrence of a repeated extreme: "when did it peak" means when it got there.
        CalculationEngine.timeOfMaximum(interval) shouldBe 2000L
        CalculationEngine.timeOfMinimum(interval) shouldBe 3000L
        CalculationEngine.timeOfFirstSample(interval) shouldBe 1000L
        CalculationEngine.timeOfLastSample(interval) shouldBe 5000L
        CalculationEngine.firstSample(interval) shouldBe 5.0
        CalculationEngine.lastSample(interval) shouldBe 20.0
    }

    "integral is value-days, so a rate over a day reads as a day's total" {
        // 100 units/day held for exactly one day is 100 units.
        val day = 86_400_000L
        val interval = IntervalSamples(0, day, emptyList(), seed = Sample(-1, 100.0))
        CalculationEngine.integral(interval)!! shouldBe (100.0 plusOrMinus 1e-9)
    }

    "integral halves when the value holds for half the interval" {
        val day = 86_400_000L
        val interval = IntervalSamples(0, day, listOf(Sample(day / 2, 0.0)), seed = Sample(-1, 100.0))
        CalculationEngine.integral(interval)!! shouldBe (50.0 plusOrMinus 1e-9)
    }

    "standard deviation and variance" {
        val interval = IntervalSamples(0, 10_000, listOf(at(1, 2.0), at(2, 4.0), at(3, 4.0), at(4, 6.0)))
        // mean 4, population variance ((4+0+0+4)/4) = 2
        CalculationEngine.variance(interval)!! shouldBe (2.0 plusOrMinus 1e-9)
        CalculationEngine.standardDeviation(interval)!! shouldBe (1.4142135623 plusOrMinus 1e-8)
    }

    "a single sample has no variance to speak of" {
        CalculationEngine.variance(IntervalSamples(0, 1000, listOf(at(0, 5.0)))).shouldBeNull()
    }

    "percent good is weighted by DURATION, not by sample count" {
        // One bad sample holding for three of four seconds is 75% bad, even though it is one
        // sample of two. Counting samples would call it 50% and understate the outage.
        val interval = IntervalSamples(
            0, 4000,
            listOf(at(0, 1.0), at(1, 0.0, good = false)),
        )
        CalculationEngine.percentGood(interval) shouldBe (25.0 plusOrMinus 1e-9)
        CalculationEngine.percentBad(interval) shouldBe (75.0 plusOrMinus 1e-9)
    }

    "duration on and off, as fractions of a day" {
        val hour = 3_600_000L
        // Runs for the first hour of a two-hour interval.
        val interval = IntervalSamples(0, 2 * hour, listOf(Sample(hour, 0.0)), seed = Sample(-1, 1.0))
        CalculationEngine.durationOn(interval) shouldBe ((1.0 / 24.0) plusOrMinus 1e-9)
        CalculationEngine.durationOff(interval) shouldBe ((1.0 / 24.0) plusOrMinus 1e-9)
    }

    "count on counts transitions INTO the on state, not samples that are on" {
        // Three consecutive on-samples are one start, not three.
        val interval = IntervalSamples(
            0, 10_000,
            listOf(at(1, 1.0), at(2, 1.0), at(3, 0.0), at(4, 1.0), at(5, 0.0)),
            seed = at(-1, 0.0),
        )
        CalculationEngine.countOn(interval) shouldBe 2
        CalculationEngine.countOff(interval) shouldBe 2
    }
})
