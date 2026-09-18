package io.colens.report.common

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Calculations computed from raw samples, for everything the historian will not do itself.
 *
 * This is not a fallback path. Measured against a live QuestDB Core historian, only eight of the
 * seventeen aggregate names are native — and TIME-WEIGHTED AVERAGE is not among them. That is
 * XLReporter's default and the distinction its documentation leads with, so the engine is on the
 * critical path for the product's headline number rather than being tail-end polish.
 *
 * Two families of answer, and confusing them is the classic reporting error:
 *
 *   RAW      treats each stored sample as one observation of equal worth.
 *   WEIGHTED integrates a stepwise curve, so a value that held for an hour counts for an hour.
 *
 * For a process value — a flow, a level, a temperature — weighted is almost always the one meant.
 * A tag that logs on change will have sat at its resting value for most of the period and recorded
 * it once, so the raw mean is dominated by whatever was moving.
 *
 * Free of Ignition, of the historian API and of SDK types, so all of it is unit-testable.
 */
object CalculationEngine {

    /**
     * Time-weighted mean over the interval.
     *
     * XLReporter's worked example, kept as the golden case: samples of 20 and 30 inside a
     * four-unit interval, with a 10 in force from before it. The raw mean is (20+30)/2 = 25; the
     * time-weighted mean is (10·2 + 20·1 + 30·1)/4 = 17.5. They differ by a third, and both look
     * entirely plausible on a report.
     */
    fun timeWeightedAverage(interval: IntervalSamples): Double? {
        val steps = interval.steps().filter { it.sample.good && it.sample.value != null }
        val total = steps.sumOf { it.durationMillis }
        if (total == 0L) return null
        return steps.sumOf { (it.sample.value ?: 0.0) * it.durationMillis } / total
    }

    /** Unweighted mean of the samples inside the interval — Ignition's SimpleAverage. */
    fun simpleAverage(interval: IntervalSamples): Double? =
        interval.inside().values().ifEmpty { return null }.average()

    /**
     * Area under the stepwise curve, in value·days.
     *
     * Days rather than milliseconds because that is the unit a totaliser is read in: a flow in
     * m³/day integrated over a day should come out as a day's volume, not a number with eight
     * extra zeros on it.
     */
    fun integral(interval: IntervalSamples): Double? {
        val steps = interval.steps().filter { it.sample.good && it.sample.value != null }
        if (steps.isEmpty()) return null
        return steps.sumOf { (it.sample.value ?: 0.0) * it.durationMillis } / MILLIS_PER_DAY
    }

    fun minimum(interval: IntervalSamples): Double? = interval.inside().values().minOrNull()

    fun maximum(interval: IntervalSamples): Double? = interval.inside().values().maxOrNull()

    /** Max less min. Zero for a flat interval; null when nothing was recorded. */
    fun range(interval: IntervalSamples): Double? {
        val values = interval.inside().values()
        if (values.isEmpty()) return null
        return values.max() - values.min()
    }

    fun sum(interval: IntervalSamples): Double? =
        interval.inside().values().ifEmpty { return null }.sum()

    fun count(interval: IntervalSamples): Int = interval.inside().count { it.good && it.value != null }

    fun firstSample(interval: IntervalSamples): Double? = interval.inside().goodValues().firstOrNull()?.value

    fun lastSample(interval: IntervalSamples): Double? = interval.inside().goodValues().lastOrNull()?.value

    fun timeOfFirstSample(interval: IntervalSamples): Long? =
        interval.inside().goodValues().firstOrNull()?.timestampMillis

    fun timeOfLastSample(interval: IntervalSamples): Long? =
        interval.inside().goodValues().lastOrNull()?.timestampMillis

    /**
     * When the extreme occurred, not what it was.
     *
     * The FIRST occurrence when a value repeats, matching XLReporter. An operator asking "when did
     * it peak" means the moment it got there, not the last time it was still there.
     */
    fun timeOfMaximum(interval: IntervalSamples): Long? = extremeAt(interval) { a, b -> a > b }

    fun timeOfMinimum(interval: IntervalSamples): Long? = extremeAt(interval) { a, b -> a < b }

    /** Population standard deviation over the samples in the interval. */
    fun standardDeviation(interval: IntervalSamples): Double? = variance(interval)?.let(::sqrt)

    fun variance(interval: IntervalSamples): Double? {
        val values = interval.inside().values()
        if (values.size < 2) return null
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    /** Percentage of the interval's DURATION covered by good-quality values, not of its samples. */
    fun percentGood(interval: IntervalSamples): Double {
        val steps = interval.steps()
        val total = steps.sumOf { it.durationMillis }
        if (total == 0L) return 0.0
        return 100.0 * steps.filter { it.sample.good }.sumOf { it.durationMillis } / total
    }

    fun percentBad(interval: IntervalSamples): Double = 100.0 - percentGood(interval)

    /**
     * How long the value was non-zero (`on`), as a fraction of a day.
     *
     * Weighted by definition — "how long was the pump running" is a duration question, and
     * counting samples would answer a different one.
     */
    fun durationOn(interval: IntervalSamples): Double = durationWhere(interval) { it != 0.0 }

    fun durationOff(interval: IntervalSamples): Double = durationWhere(interval) { it == 0.0 }

    /** Transitions INTO the on state, which is what "how many times did it start" means. */
    fun countOn(interval: IntervalSamples): Int = transitionsInto(interval, on = true)

    fun countOff(interval: IntervalSamples): Int = transitionsInto(interval, on = false)

    // -----------------------------------------------------------------------

    /**
     * Consumption from a counter that wraps.
     *
     * Energy meters, totalisers and weigh scales all roll over, and a naive last-minus-first
     * reports a large NEGATIVE consumption for the interval in which it happens — a plausible
     * number, not an error, which is what makes it dangerous. [DifferenceOptions.rollover] is the
     * value at which the counter wraps; 0 means it never does.
     *
     * Falling counters (a weigh scale emptying) are the same arithmetic with the sign reversed.
     */
    fun difference(interval: IntervalSamples, options: DifferenceOptions): Double? {
        val values = interval.inside().goodValues().mapNotNull { it.value }
        val series = listOfNotNull(interval.seed?.takeIf { it.good }?.value) + values
        if (series.size < 2) return null

        var total = 0.0
        for (index in 1 until series.size) {
            total += step(series[index - 1], series[index], options)
        }
        return total
    }

    private fun step(previous: Double, current: Double, options: DifferenceOptions): Double {
        val raw = if (options.rising) current - previous else previous - current
        if (raw >= 0) return raw

        /* Negative means it wrapped — or that somebody reset the counter by hand, which is
           indistinguishable from two samples. With no rollover configured the only honest answer
           is to count nothing rather than invent a spike.
           
           The check is `== null`, NOT `<= 0`: on a FALLING counter a floor of zero is the ordinary
           case (a tank draining to empty), and treating it as "unconfigured" silently dropped
           every refill's consumption. */
        val rollover = options.rollover ?: return 0.0

        return if (options.rising) {
            // Up to the limit, wrap, then on from the reset value.
            (rollover - previous) + (current - options.reset)
        } else {
            // Down to the floor, refill, then on down from the reset level.
            (previous - rollover) + (options.reset - current)
        }
    }

    /**
     * Mean Kinetic Temperature: the single temperature whose effect equals that of the observed
     * fluctuation, used for pharmaceutical and food storage compliance.
     *
     * Time-weighted, because a excursion that lasted an hour matters more than one that lasted a
     * minute — and it is exactly those excursions the measure exists to capture.
     */
    fun meanKineticTemperature(interval: IntervalSamples, options: MeanKineticOptions): Double? {
        val steps = interval.steps().filter { it.sample.good && it.sample.value != null }
        val total = steps.sumOf { it.durationMillis }
        if (total == 0L) return null

        val activation = options.activationEnergyJPerMol
        val weighted = steps.sumOf { step ->
            val kelvin = options.unit.toKelvin(step.sample.value ?: 0.0)
            if (kelvin <= 0) return null
            step.durationMillis * exp(-activation / (GAS_CONSTANT * kelvin))
        }
        if (weighted <= 0.0) return null

        val kelvin = -activation / (GAS_CONSTANT * ln(weighted / total))
        return options.unit.fromKelvin(kelvin)
    }

    // -----------------------------------------------------------------------

    private const val MILLIS_PER_DAY = 86_400_000.0

    /** J/(mol·K). */
    private const val GAS_CONSTANT = 8.314462618

    private fun List<Sample>.goodValues(): List<Sample> = filter { it.good && it.value != null }

    private fun List<Sample>.values(): List<Double> = goodValues().mapNotNull { it.value }

    private fun extremeAt(interval: IntervalSamples, better: (Double, Double) -> Boolean): Long? {
        var best: Sample? = null
        for (sample in interval.inside().goodValues()) {
            val value = sample.value ?: continue
            val incumbent = best?.value
            /* Strictly better, so a repeated extreme keeps the FIRST occurrence. */
            if (incumbent == null || better(value, incumbent)) best = sample
        }
        return best?.timestampMillis
    }

    private fun durationWhere(interval: IntervalSamples, predicate: (Double) -> Boolean): Double {
        val matching = interval.steps()
            .filter { it.sample.good && it.sample.value != null && predicate(it.sample.value!!) }
            .sumOf { it.durationMillis }
        return matching / MILLIS_PER_DAY
    }

    private fun transitionsInto(interval: IntervalSamples, on: Boolean): Int {
        var previous = interval.seed?.takeIf { it.good }?.value?.let { it != 0.0 }
        var count = 0
        for (sample in interval.inside().goodValues()) {
            val state = (sample.value ?: 0.0) != 0.0
            if (previous != null && previous != state && state == on) count++
            previous = state
        }
        return count
    }
}

/**
 * How a wrapping counter behaves.
 *
 * The two limits MIRROR between the rising and falling cases, and getting them the wrong way round
 * is easy and silent:
 *
 *   rising  — counts up to [rollover], then jumps to [reset]. An energy meter wrapping 999→0.
 *   falling — counts down to [rollover], then jumps to [reset]. A tank draining to 0, refilled
 *             to 100.
 *
 * So [rollover] is always the limit it LEAVES and [reset] always the value it ARRIVES at.
 */
data class DifferenceOptions(
    /** True for a counter that counts up (an energy meter); false for one that counts down. */
    val rising: Boolean = true,
    /** The limit the counter reaches before wrapping. Null means it never wraps. */
    val rollover: Double? = null,
    /** The value the counter takes immediately after wrapping. */
    val reset: Double = 0.0,
)

data class MeanKineticOptions(
    /** Activation energy. The pharma convention is 83.144 kJ/mol. */
    val activationEnergyJPerMol: Double = 83_144.0,
    val unit: TemperatureUnit = TemperatureUnit.CELSIUS,
)

enum class TemperatureUnit {
    CELSIUS,
    FAHRENHEIT,
    KELVIN;

    fun toKelvin(value: Double): Double = when (this) {
        CELSIUS -> value + 273.15
        FAHRENHEIT -> (value - 32.0) * 5.0 / 9.0 + 273.15
        KELVIN -> value
    }

    fun fromKelvin(kelvin: Double): Double = when (this) {
        CELSIUS -> kelvin - 273.15
        FAHRENHEIT -> (kelvin - 273.15) * 9.0 / 5.0 + 32.0
        KELVIN -> kelvin
    }
}
