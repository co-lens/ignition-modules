package io.colens.report.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Difference-with-rollover and Mean Kinetic Temperature — the two calculations most likely to be
 * quietly wrong.
 *
 * A meter difference that mishandles a wrap reports a large NEGATIVE consumption, and MKT that
 * weights by sample instead of by duration under-reports exactly the excursions it exists to
 * catch. Neither raises anything; both produce a number somebody signs off.
 */
class DifferenceAndMktTest : StringSpec({

    fun at(second: Long, value: Double) = Sample(second * 1000, value)

    fun interval(vararg samples: Sample, seed: Sample? = null) =
        IntervalSamples(0, 100_000, samples.toList(), seed)

    "a rising meter reports the increase" {
        val meter = interval(at(1, 100.0), at(2, 110.0), at(3, 125.0))
        CalculationEngine.difference(meter, DifferenceOptions(rising = true))!! shouldBe (25.0 plusOrMinus 1e-9)
    }

    "the seed is included, so consumption since the interval began is not lost" {
        // Without it the first sample inside the interval becomes the baseline and everything
        // consumed between the interval start and that sample vanishes.
        val meter = interval(at(1, 110.0), at(2, 125.0), seed = at(-1, 100.0))
        CalculationEngine.difference(meter, DifferenceOptions(rising = true))!! shouldBe (25.0 plusOrMinus 1e-9)
    }

    "a rollover is accounted for rather than reported as negative consumption" {
        /*
         * THE case this exists for. A meter wrapping at 1000 reads 980 then 30. Naive
         * last-minus-first gives -950 — a plausible-looking number that no error accompanies.
         * The true consumption is (1000-980) + (30-0) = 50.
         */
        val meter = interval(at(1, 980.0), at(2, 30.0))
        val options = DifferenceOptions(rising = true, rollover = 1000.0, reset = 0.0)
        CalculationEngine.difference(meter, options)!! shouldBe (50.0 plusOrMinus 1e-9)
    }

    "a rollover to a non-zero reset value" {
        // Some counters wrap to 1 rather than 0.
        val meter = interval(at(1, 999.0), at(2, 5.0))
        val options = DifferenceOptions(rising = true, rollover = 1000.0, reset = 1.0)
        CalculationEngine.difference(meter, options)!! shouldBe (5.0 plusOrMinus 1e-9)
    }

    "several wraps in one interval each get counted" {
        val meter = interval(at(1, 900.0), at(2, 50.0), at(3, 950.0), at(4, 100.0))
        val options = DifferenceOptions(rising = true, rollover = 1000.0)
        // (1000-900+50) + (950-50) + (1000-950+100) = 150 + 900 + 150 = 1200
        CalculationEngine.difference(meter, options)!! shouldBe (1200.0 plusOrMinus 1e-9)
    }

    "a floor of ZERO on a falling counter is a real floor, not 'unconfigured'" {
        // Guarding on `rollover <= 0` instead of `rollover == null` silently dropped every
        // refill's consumption on any tank that drains to empty — which is most of them.
        val scale = interval(at(1, 50.0), at(2, 90.0))
        val options = DifferenceOptions(rising = false, rollover = 0.0, reset = 100.0)
        CalculationEngine.difference(scale, options)!! shouldBe (60.0 plusOrMinus 1e-9)
    }

    "with no rollover configured a backwards step counts nothing" {
        // A hand reset is indistinguishable from a wrap here, and inventing a spike from a counter
        // somebody zeroed is worse than reporting no consumption for that step.
        val meter = interval(at(1, 500.0), at(2, 10.0), at(3, 40.0))
        CalculationEngine.difference(meter, DifferenceOptions(rising = true))!! shouldBe (30.0 plusOrMinus 1e-9)
    }

    "a falling counter is the same arithmetic with the sign reversed" {
        // A weigh scale emptying from 100 to 60 dispensed 40.
        val scale = interval(at(1, 100.0), at(2, 80.0), at(3, 60.0))
        CalculationEngine.difference(scale, DifferenceOptions(rising = false))!! shouldBe (40.0 plusOrMinus 1e-9)
    }

    "a falling counter that refills is handled by its rollover" {
        /*
         * For a FALLING counter the two settings mean the mirror of the rising case, and getting
         * them the wrong way round is easy: `rollover` is the floor it drains to before refilling,
         * `reset` is the level it refills TO. A tank draining to 0 and refilling to 100.
         *
         * Readings 100 -> 20 -> 90. The first step dispensed 80. The second is negative, so it
         * refilled: the model charges the remaining drain to the floor (20) plus the drain from
         * the refilled level down to 90 (10), giving 30.
         *
         * That assumption — that it drained fully before refilling — is the only one available.
         * Two samples either side of a refill cannot distinguish "drained to empty then refilled"
         * from "topped up from 20", and inventing the more flattering reading would understate
         * consumption on every refill.
         */
        val scale = interval(at(1, 100.0), at(2, 20.0), at(3, 90.0))
        val options = DifferenceOptions(rising = false, rollover = 0.0, reset = 100.0)
        CalculationEngine.difference(scale, options)!! shouldBe (110.0 plusOrMinus 1e-9)
    }

    "fewer than two readings is no difference at all" {
        CalculationEngine.difference(interval(at(1, 100.0)), DifferenceOptions()).shouldBeNull()
        CalculationEngine.difference(interval(), DifferenceOptions()).shouldBeNull()
    }

    "MKT of a constant temperature is that temperature" {
        // The one case with an answer that needs no arithmetic — if this drifts, the exponentials
        // are wrong somewhere.
        val steady = IntervalSamples(0, 86_400_000, emptyList(), seed = Sample(-1, 25.0))
        CalculationEngine.meanKineticTemperature(steady, MeanKineticOptions())!! shouldBe (25.0 plusOrMinus 1e-6)
    }

    "MKT exceeds the arithmetic mean, which is the whole point of it" {
        /*
         * Half an interval at 20 °C and half at 40 °C. The arithmetic mean is 30; MKT is higher,
         * because degradation is exponential in temperature and the warm half does
         * disproportionate damage. A measure that returned 30 here would be worthless for the
         * stability reporting it exists for.
         */
        val day = 86_400_000L
        val swing = IntervalSamples(0, day, listOf(Sample(day / 2, 40.0)), seed = Sample(-1, 20.0))
        val mkt = CalculationEngine.meanKineticTemperature(swing, MeanKineticOptions())!!
        (mkt > 30.0) shouldBe true
        (mkt < 40.0) shouldBe true
    }

    "MKT is duration-weighted, not sample-weighted" {
        // One brief excursion recorded as one sample must not count as much as the steady value
        // it interrupted. Sample-weighting would pull this far higher.
        val day = 86_400_000L
        val brief = IntervalSamples(
            0, day,
            listOf(Sample(day - 60_000, 60.0)),  // one minute at 60 °C, at the very end
            seed = Sample(-1, 20.0),
        )
        val mkt = CalculationEngine.meanKineticTemperature(brief, MeanKineticOptions())!!
        (mkt < 25.0) shouldBe true
    }

    "MKT in Fahrenheit round-trips through Kelvin" {
        val steady = IntervalSamples(0, 86_400_000, emptyList(), seed = Sample(-1, 77.0))
        val mkt = CalculationEngine.meanKineticTemperature(
            steady, MeanKineticOptions(unit = TemperatureUnit.FAHRENHEIT),
        )!!
        mkt shouldBe (77.0 plusOrMinus 1e-6)
    }

    "temperature unit conversions" {
        TemperatureUnit.CELSIUS.toKelvin(0.0) shouldBe (273.15 plusOrMinus 1e-9)
        TemperatureUnit.FAHRENHEIT.toKelvin(32.0) shouldBe (273.15 plusOrMinus 1e-9)
        TemperatureUnit.KELVIN.toKelvin(300.0) shouldBe (300.0 plusOrMinus 1e-9)
        TemperatureUnit.FAHRENHEIT.fromKelvin(273.15) shouldBe (32.0 plusOrMinus 1e-9)
    }
})
