package co.datapipelines.lake

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * The `datapipelines.lake.*` keys (configuration.md §3.36; dp-lake design record §6) — the
 * managed lake's server configuration, never UI. The defaults here equal the documented ones and
 * application.yml's; `LakeConfigKeysSpecDriftTest` holds the three together, and `LakeRules` in `app`'s
 * ConfigValidator refuses a bad combination at boot before this binding is ever consumed.
 *
 * P2 (#785) consumes [enabled], [name], [storage] (but the landing prefix) and [catalog]; P5 (#818) consumes
 * [reads] and the reader pair. [mode], [admission], [maintenance] and [retention] bind with their documented defaults and are
 * consumed by later lanes (P1/P4/P5/P6/P7) — bound now so one key set is documented, validated and
 * drift-tested from the first release of the module.
 *
 * The four credential values are secrets (environment variables only, `deploy/secrets.env.example`):
 * [toString] is overridden at every level that holds one, so logging this object never prints them.
 */
@ConfigurationProperties(prefix = "datapipelines.lake")
class LakeProperties(
    /** The lake exists on this server. `false` = the module is inert: no catalog, no boot check, no S3 client. */
    val enabled: Boolean = false,
    /** The lake's logical name: `catalog_name` in the catalog tables and the first segment of `<lake>.<ns>.<table>`. */
    val name: String = DEFAULT_NAME,
    /** `normal | restoring | replaying` (§9). P2 validates the value only (`LakeRules`); the recovery procedure (P7) consumes it. */
    val mode: String = MODE_NORMAL,
    val storage: Storage = Storage(),
    val admission: Admission = Admission(),
    val catalog: Catalog = Catalog(),
    val reads: Reads = Reads(),
    val maintenance: Maintenance = Maintenance(),
    val retention: Retention = Retention(),
) {
    /** The object store (§6 `storage.*`). Only `s3` exists (D11: local filesystem is an interface only). */
    class Storage(
        val kind: String = KIND_S3,
        val bucket: String = "",
        /** Key prefix of the managed lake under [bucket]; normalised to end with `/` (see [normalisedPrefix]). */
        val prefix: String = DEFAULT_PREFIX,
        /** Where deliveries land (P4); bound, unused by P2. */
        val landingPrefix: String = DEFAULT_LANDING_PREFIX,
        val region: String = "",
        /** Empty = the AWS endpoint for [region]; the scheme decides TLS (`https` → TLS, call 24). */
        val endpoint: String = "",
        val pathStyle: Boolean = false,
        /** The writer pair (Java side). Both empty = the SDK's default provider chain (instance credentials). */
        val accessKey: String = "",
        val secretKey: String = "",
        /** Optional read-only pair for DuckDB (call 23; P5). Bound, unused by P2. */
        val readerAccessKey: String = "",
        val readerSecretKey: String = "",
    ) {
        /** [prefix] with exactly one trailing `/` and no leading `/` (`""` stays the bucket root). */
        val normalisedPrefix: String get() = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }

        /** `s3://<bucket>/<prefix>` without a trailing slash: the root every table location sits under (call 1). */
        val root: String get() = "s3://$bucket/$normalisedPrefix".trimEnd('/')

        val hasStaticCredentials: Boolean get() = accessKey.isNotBlank() && secretKey.isNotBlank()

        override fun toString(): String =
            "Storage(kind=$kind, bucket=$bucket, prefix=$prefix, landingPrefix=$landingPrefix, region=$region, " +
                "endpoint=$endpoint, pathStyle=$pathStyle, accessKey=${redact(accessKey)}, secretKey=${redact(secretKey)}, " +
                "readerAccessKey=${redact(readerAccessKey)}, readerSecretKey=${redact(readerSecretKey)})"
    }

    /** §6 `admission.*` (D12) — consumed by P1/P4/P6; bound here with the record's defaults. */
    class Admission(
        val maxConcurrentIngestions: Int = DEFAULT_MAX_CONCURRENT_INGESTIONS,
        val maxConcurrentMaintenance: Int = 1,
        val maxDeliveryBytes: DataSize = DataSize.ofGigabytes(DEFAULT_MAX_DELIVERY_GB),
        val stagingExpansionFactor: Int = DEFAULT_STAGING_EXPANSION_FACTOR,
        val scratchBytes: DataSize = DataSize.ofGigabytes(DEFAULT_SCRATCH_GB),
        val stagingMemoryLimit: DataSize = DataSize.ofGigabytes(1),
        val maxOpenPartitionsPerAppend: Int = DEFAULT_MAX_OPEN_PARTITIONS,
        val jobTimeout: Duration = Duration.ofHours(2),
        val leaseWaitSeconds: Long = DEFAULT_LEASE_WAIT_SECONDS,
    ) {
        override fun toString(): String =
            "Admission(maxConcurrentIngestions=$maxConcurrentIngestions, maxConcurrentMaintenance=$maxConcurrentMaintenance, " +
                "maxDeliveryBytes=$maxDeliveryBytes, stagingExpansionFactor=$stagingExpansionFactor, scratchBytes=$scratchBytes, " +
                "stagingMemoryLimit=$stagingMemoryLimit, maxOpenPartitionsPerAppend=$maxOpenPartitionsPerAppend, " +
                "jobTimeout=$jobTimeout, leaseWaitSeconds=$leaseWaitSeconds)"
    }

    /** §6 `catalog.*` (call 27) — set on every catalog connection the fenced pool opens (§8a). */
    class Catalog(
        val lockTimeoutSeconds: Long = DEFAULT_LOCK_TIMEOUT_SECONDS,
        val statementTimeoutSeconds: Long = DEFAULT_STATEMENT_TIMEOUT_SECONDS,
    ) {
        override fun toString(): String =
            "Catalog(lockTimeoutSeconds=$lockTimeoutSeconds, statementTimeoutSeconds=$statementTimeoutSeconds)"
    }

    /**
     * §6 `reads.*` (§10, P5 #818) — the managed reader generations. [maxGenerationAgeSeconds] bounds a generation's
     * life inside the pool manager's lookup; [freshnessTickSeconds] is the cadence of the catalog comparison that
     * retires a stale generation early; [memoryLimit] is the DuckDB `memory_limit` written onto the managed
     * datasource row, so every reader instance is bounded (the §6 amendment's `reads.memory-limit` term; the cgroup
     * boot check that sums it is a follow-up). `LakeRules.checkLakeReads` refuses a bad combination at boot.
     *
     * #845 (owner ruling 2026-11-06 option B): [egressProxy] is the `host:port` of the egress proxy the managed
     * datasource's object-store traffic leaves through (empty = none; compose and Helm ship one ON), written onto the
     * managed row as its `egress_proxy` dialect key; [egressMaxAgeSeconds] is the proxy's connection and stream max-age,
     * which the proxy declares at `/dp-egress` and the boot check compares. Hygiene, not safety: reader leases protect
     * snapshots. A temporary accommodation until duckdb-httpfs#519 lands.
     */
    class Reads(
        val maxGenerationAgeSeconds: Long = DEFAULT_MAX_GENERATION_AGE_SECONDS,
        val freshnessTickSeconds: Long = DEFAULT_FRESHNESS_TICK_SECONDS,
        val memoryLimit: DataSize = DataSize.ofMegabytes(DEFAULT_READS_MEMORY_LIMIT_MB),
        val egressProxy: String = "",
        val egressMaxAgeSeconds: Long = DEFAULT_EGRESS_MAX_AGE_SECONDS,
    ) {
        init {
            require(maxGenerationAgeSeconds > 0) { "$PREFIX.reads.max-generation-age-seconds must be > 0" }
            require(freshnessTickSeconds > 0) { "$PREFIX.reads.freshness-tick-seconds must be > 0" }
            // A tick at or above the bound never retires a generation before the bound does (#818 brief A5).
            require(freshnessTickSeconds < maxGenerationAgeSeconds) {
                "$PREFIX.reads.freshness-tick-seconds ($freshnessTickSeconds) must be below " +
                    "$PREFIX.reads.max-generation-age-seconds ($maxGenerationAgeSeconds)"
            }
            require(memoryLimit.toBytes() > 0) { "$PREFIX.reads.memory-limit must be a positive size" }
            require(egressMaxAgeSeconds > 0) { "$PREFIX.reads.egress-max-age-seconds must be > 0" }
        }

        override fun toString(): String =
            "Reads(maxGenerationAgeSeconds=$maxGenerationAgeSeconds, freshnessTickSeconds=$freshnessTickSeconds, " +
                "memoryLimit=$memoryLimit, egressProxy=$egressProxy, egressMaxAgeSeconds=$egressMaxAgeSeconds)"
    }

    /** §6 `maintenance.*` (§11, P6) — bound, unused by P2. The window is UTC (call 8). */
    class Maintenance(
        val window: String = DEFAULT_MAINTENANCE_WINDOW,
        val maxBytesRewrittenPerRun: DataSize = DataSize.ofGigabytes(DEFAULT_MAX_BYTES_REWRITTEN_GB),
    ) {
        override fun toString(): String = "Maintenance(window=$window, maxBytesRewrittenPerRun=$maxBytesRewrittenPerRun)"
    }

    /** §6 `retention.*` (§8a, §9; P6/P7) — bound, unused by P2. The grace rule is enforced by `LakeRules` already. */
    class Retention(
        val protectionDays: Int = DEFAULT_PROTECTION_DAYS,
        val orphanGraceHours: Int = DEFAULT_ORPHAN_GRACE_HOURS,
    ) {
        override fun toString(): String = "Retention(protectionDays=$protectionDays, orphanGraceHours=$orphanGraceHours)"
    }

    override fun toString(): String =
        "LakeProperties(enabled=$enabled, name=$name, mode=$mode, storage=$storage, admission=$admission, catalog=$catalog, " +
            "reads=$reads, maintenance=$maintenance, retention=$retention)"

    companion object {
        const val PREFIX = "datapipelines.lake"
        const val DEFAULT_NAME = "lake"
        const val MODE_NORMAL = "normal"
        const val KIND_S3 = "s3"
        const val DEFAULT_PREFIX = "lake/"
        const val DEFAULT_LANDING_PREFIX = "landing/"
        const val DEFAULT_MAX_CONCURRENT_INGESTIONS = 2
        const val DEFAULT_MAX_DELIVERY_GB = 2L
        const val DEFAULT_STAGING_EXPANSION_FACTOR = 3
        const val DEFAULT_SCRATCH_GB = 20L
        const val DEFAULT_MAX_OPEN_PARTITIONS = 64
        const val DEFAULT_LEASE_WAIT_SECONDS = 60L
        const val DEFAULT_LOCK_TIMEOUT_SECONDS = 5L
        const val DEFAULT_STATEMENT_TIMEOUT_SECONDS = 30L
        const val DEFAULT_MAX_GENERATION_AGE_SECONDS = 900L
        const val DEFAULT_FRESHNESS_TICK_SECONDS = 60L
        const val DEFAULT_READS_MEMORY_LIMIT_MB = 512L

        /** #845 D1: `node-query-timeout-max-seconds` (900) + 60 — a healthy query is never cut before its own timeout. */
        const val DEFAULT_EGRESS_MAX_AGE_SECONDS = 960L
        const val DEFAULT_MAINTENANCE_WINDOW = "02:00-05:00"
        const val DEFAULT_MAX_BYTES_REWRITTEN_GB = 50L
        const val DEFAULT_PROTECTION_DAYS = 7
        const val DEFAULT_ORPHAN_GRACE_HOURS = 72

        /** A secret's presence, never its value. */
        internal fun redact(secret: String): String = if (secret.isEmpty()) "<empty>" else "<redacted>"
    }
}
