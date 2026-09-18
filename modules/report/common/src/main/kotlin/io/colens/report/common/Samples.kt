package io.colens.report.common

/**
 * A stored value, as the historian returns it.
 *
 * `quality` is carried, never discarded. A bad-quality sample that is silently treated as good is
 * a fabricated reading that looks exactly like a measured one, and a report is only worth
 * something if the two are distinguishable.
 */
data class Sample(
    val timestampMillis: Long,
    val value: Double?,
    val good: Boolean = true,
)

/**
 * One interval's worth of samples, plus the value that was in force when it began.
 *
 * [seed] is the whole reason lead time exists. A time-weighted calculation integrates a stepwise
 * curve across the interval, and without the last sample from BEFORE the interval there is nothing
 * to propagate into the gap before the first one — so the curve starts late and the result comes
 * out low. Not an error: just a number that is quietly wrong.
 */
data class IntervalSamples(
    val startMillis: Long,
    val endMillis: Long,
    val samples: List<Sample>,
    val seed: Sample? = null,
) {
    init {
        require(endMillis > startMillis) { "An interval must end after it starts." }
    }

    val durationMillis: Long get() = endMillis - startMillis

    /**
     * The stepwise curve: each value paired with how long it held, clipped to this interval.
     *
     * The seed, when present, holds from the interval start until the first sample inside it. The
     * last sample holds until the interval end — a value stays in force until something replaces
     * it, which is what "stepwise" means and why a tag logged once an hour still has a meaningful
     * average over that hour.
     */
    fun steps(): List<Step> {
        val inside = samples.filter { it.timestampMillis in startMillis until endMillis }.sortedBy { it.timestampMillis }
        val steps = mutableListOf<Step>()

        var cursor = startMillis
        var current = seed

        for (sample in inside) {
            if (sample.timestampMillis > cursor && current != null) {
                steps += Step(current, sample.timestampMillis - cursor)
            }
            cursor = maxOf(cursor, sample.timestampMillis)
            current = sample
        }

        if (current != null && cursor < endMillis) {
            steps += Step(current, endMillis - cursor)
        }

        return steps
    }

    /** Samples actually recorded inside the interval. Excludes the seed, which happened earlier. */
    fun inside(): List<Sample> =
        samples.filter { it.timestampMillis in startMillis until endMillis }.sortedBy { it.timestampMillis }

    data class Step(val sample: Sample, val durationMillis: Long)
}
