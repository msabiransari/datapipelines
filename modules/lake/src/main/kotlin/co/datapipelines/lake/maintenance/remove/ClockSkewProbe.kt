package co.datapipelines.lake.maintenance.remove

import org.springframework.jdbc.core.JdbcTemplate
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The catalog's clock, the one way it reaches the remover (D7, the cut's L17): the probe's two readings and every
 * file's age come from it. Production reads `clock_timestamp()` on DP's metadata database; a test shifts it by
 * wrapping this function, against the real object store's own clock.
 */
fun interface CatalogClock {
    fun now(): Instant

    companion object {
        /** `SELECT clock_timestamp()` — the wall clock at the statement, not a transaction's start. */
        fun of(jdbc: JdbcTemplate): CatalogClock =
            CatalogClock {
                requireNotNull(jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime::class.java)) {
                    "clock_timestamp() answered null"
                }.toInstant()
            }
    }
}

/**
 * The skew interval of record §8a (iii): `[LastModified − after, LastModified + 1 s − before]`, in the store-minus-catalog
 * direction. The store stamps whole seconds, so the true skew lies inside it whatever the PUT's latency.
 */
data class SkewInterval(
    val lower: Duration,
    val upper: Duration,
) {
    /** Both ends inside ±[allowance]: only then does the pass run. A midpoint test would admit an interval that crosses it. */
    fun within(allowance: Duration): Boolean = lower >= allowance.negated() && upper <= allowance

    companion object {
        fun of(
            before: Instant,
            after: Instant,
            lastModified: Instant,
        ): SkewInterval =
            SkewInterval(
                lower = Duration.between(after, lastModified),
                upper = Duration.between(before, lastModified.plusSeconds(1)),
            )
    }
}

/** What the probe saw: the marker it PUT, the interval, and whether the pass may run. */
data class ProbeReading(
    val markerKey: String,
    val interval: SkewInterval,
    val admitted: Boolean,
)

/**
 * The clock-skew probe (D6): read the catalog clock, PUT the marker `dp/clock/<run-id>`, read the clock again, read the
 * marker's `LastModified` back (HEAD), and admit the pass only when the interval lies within ±[allowance]. This checks the
 * cleanup-time half of call 33's assumption only (the record's residual, E1 o). The probe never deletes: its marker is the
 * pass's to remove, under the lease and mode checks (D5).
 */
class ClockSkewProbe(
    private val s3: S3Client,
    private val clock: CatalogClock,
    private val allowance: Duration = SKEW_ALLOWANCE,
) {
    fun probe(
        bucket: String,
        markerKey: String,
    ): ProbeReading {
        val before = clock.now()
        s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(markerKey)
                .build(),
            RequestBody.empty(),
        )
        val after = clock.now()
        val lastModified =
            s3
                .headObject(
                    HeadObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(markerKey)
                        .build(),
                ).lastModified()
        val interval = SkewInterval.of(before, after, lastModified)
        return ProbeReading(markerKey, interval, interval.within(allowance))
    }

    companion object {
        /**
         * The record's fixed allowance (call 33): 3600 s. The same number is `LakeRules.SKEW_ALLOWANCE_SECONDS` in `app`,
         * where the grace rule uses it; `app` depends on this module, not the other way, so the value is stated twice.
         */
        val SKEW_ALLOWANCE: Duration = Duration.ofSeconds(3_600)
    }
}
