package co.datapipelines.lake.maintenance.compaction

import java.time.Duration

/**
 * The deployment values compaction plans with (brief 969 D3, D4), from `LakeProperties`: [scratchFactor] scratch bytes
 * needed per byte of a group's input, [bytesPerSecond] the input bytes a group is assumed to get through, [jobTimeout] a
 * whole run's length, and [declarationCeilingBytes] the most one run may declare: `scratch-bytes` minus one delivery's
 * reservation, so a run cannot take the scratch a delivery needs to start.
 */
data class CompactionSettings(
    val scratchFactor: Int,
    val bytesPerSecond: Long,
    val jobTimeout: Duration,
    val declarationCeilingBytes: Long,
) {
    init {
        require(scratchFactor >= 1) { "compaction-scratch-factor is at least 1: $scratchFactor" }
        require(bytesPerSecond > 0) { "compaction-bytes-per-second is above 0: $bytesPerSecond" }
        require(!jobTimeout.isNegative && !jobTimeout.isZero) { "job-timeout is positive: $jobTimeout" }
        require(declarationCeilingBytes >= 0) { "the declaration ceiling is never negative: $declarationCeilingBytes" }
    }

    /** What a whole `job-timeout` writes at [bytesPerSecond]. */
    val wholeRunBytes: Long
        get() = bytesIn(jobTimeout)

    /** The input bytes [time] gets through at [bytesPerSecond]; zero for no time, saturating at [Long.MAX_VALUE]. */
    fun bytesIn(time: Duration): Long {
        if (time.isNegative || time.isZero) return 0
        val millis = time.toMillis()
        return if (millis > Long.MAX_VALUE / bytesPerSecond) Long.MAX_VALUE else millis * bytesPerSecond / MILLIS_PER_SECOND
    }

    /** The scratch [inputBytes] need: the factor times them, saturating. */
    fun scratchFor(inputBytes: Long): Long =
        if (inputBytes > Long.MAX_VALUE / scratchFactor) Long.MAX_VALUE else inputBytes * scratchFactor

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L

        /** One delivery's scratch reservation (`DeliveryAdmission`): `max-delivery-bytes × staging-expansion-factor`. */
        fun ceiling(
            scratchBytes: Long,
            maxDeliveryBytes: Long,
            stagingExpansionFactor: Int,
        ): Long {
            val delivery =
                if (maxDeliveryBytes > Long.MAX_VALUE / stagingExpansionFactor) Long.MAX_VALUE else maxDeliveryBytes * stagingExpansionFactor
            return (scratchBytes - delivery).coerceAtLeast(0)
        }
    }
}
