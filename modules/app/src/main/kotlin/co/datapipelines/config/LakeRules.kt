package co.datapipelines.config

import co.datapipelines.web.config.DatasourcesProperties
import co.datapipelines.web.config.ReaperDelay
import org.springframework.boot.convert.DurationStyle
import org.springframework.util.unit.DataSize
import java.net.URI
import java.time.Duration

/**
 * §7 / §3.36 (#785, dp-lake P2) — the managed lake's keys at boot (dp-lake design record §6 `LakeRules`). Judged
 * only when `datapipelines.lake.enabled=true`: a deployment without a lake is never refused for lake keys it does
 * not use. Values arrive as raw STRINGS (`ConfigSnapshot.lake`), so a malformed one is a NAMED violation here
 * rather than a binder crash; application.yml supplies every key, so an unset one is judged as its default.
 *
 * The record's rules, and where each one lives:
 * - storage: enabled → a bucket, and a region or an endpoint ([checkLakeStorage]);
 * - `protection-days ≥ 1`; `orphan-grace-hours × 3600 > job-timeout + statement-timeout-seconds + 3600` — a
 *   REFUSAL, the safety premise of §8a part (b) ([checkLakeRetention]);
 * - `lease-wait-seconds < node-query-timeout-max-seconds`; `max-delivery-bytes × staging-expansion-factor ≤
 *   scratch-bytes` ([checkLakeAdmission]);
 * - every other number positive, the maintenance window `HH:MM-HH:MM` ([checkLakeBounds]);
 * - `freshness-tick-seconds < max-generation-age-seconds`, `reads.memory-limit` a positive size, and
 *   `max-generation-age + 2 × reaper-delay + retire-ceiling + freshness-tick ≤
 *   protection-days × 86400 / 24` (record §10 RULING 4 as amended, #818 P5 / #818c / #818d) ([checkLakeReads]). The
 *   retire ceiling is the one `datasources` applies: `retire-ceiling-seconds` when set, else
 *   `node-query-timeout-seconds + 30` (datasources.md §5.2). The reaper delay is a code constant, not a key:
 *   [ReaperDelay.SECONDS] (the scheduling layer) derived and pinned where it is produced.
 *
 * Deferred, with the lane that consumes the input: `scratch-bytes ≤ the pod's /tmp limit` and the cgroup memory
 * sum to P1/P6 (the sum's amendment adds keys that do not exist yet, and E10b is its acceptance — owner
 * 2026-11-02); the sum's `reads.memory-limit` term is a filed follow-up of #818 (owner 2026-11-04).
 */
internal object LakeRules {
    private const val PREFIX = "datapipelines.lake"

    const val ENABLED = "$PREFIX.enabled"
    const val NAME = "$PREFIX.name"
    const val MODE = "$PREFIX.mode"
    const val STORAGE_KIND = "$PREFIX.storage.kind"
    const val BUCKET = "$PREFIX.storage.bucket"
    const val PREFIX_KEY = "$PREFIX.storage.prefix"
    const val LANDING_PREFIX = "$PREFIX.storage.landing-prefix"
    const val REGION = "$PREFIX.storage.region"
    const val ENDPOINT = "$PREFIX.storage.endpoint"
    const val ACCESS_KEY = "$PREFIX.storage.access-key"
    const val SECRET_KEY = "$PREFIX.storage.secret-key"
    const val READER_ACCESS_KEY = "$PREFIX.storage.reader-access-key"
    const val READER_SECRET_KEY = "$PREFIX.storage.reader-secret-key"
    const val MAX_CONCURRENT_INGESTIONS = "$PREFIX.admission.max-concurrent-ingestions"
    const val MAX_CONCURRENT_MAINTENANCE = "$PREFIX.admission.max-concurrent-maintenance"
    const val MAX_DELIVERY_BYTES = "$PREFIX.admission.max-delivery-bytes"
    const val STAGING_EXPANSION_FACTOR = "$PREFIX.admission.staging-expansion-factor"
    const val SCRATCH_BYTES = "$PREFIX.admission.scratch-bytes"
    const val STAGING_MEMORY_LIMIT = "$PREFIX.admission.staging-memory-limit"
    const val MAX_OPEN_PARTITIONS = "$PREFIX.admission.max-open-partitions-per-append"
    const val JOB_TIMEOUT = "$PREFIX.admission.job-timeout"
    const val LEASE_WAIT_SECONDS = "$PREFIX.admission.lease-wait-seconds"
    const val LOCK_TIMEOUT_SECONDS = "$PREFIX.catalog.lock-timeout-seconds"
    const val STATEMENT_TIMEOUT_SECONDS = "$PREFIX.catalog.statement-timeout-seconds"
    const val MAX_GENERATION_AGE_SECONDS = "$PREFIX.reads.max-generation-age-seconds"
    const val FRESHNESS_TICK_SECONDS = "$PREFIX.reads.freshness-tick-seconds"
    const val READS_MEMORY_LIMIT = "$PREFIX.reads.memory-limit"
    const val MAINTENANCE_WINDOW = "$PREFIX.maintenance.window"
    const val MAX_BYTES_REWRITTEN = "$PREFIX.maintenance.max-bytes-rewritten-per-run"
    const val PROTECTION_DAYS = "$PREFIX.retention.protection-days"
    const val ORPHAN_GRACE_HOURS = "$PREFIX.retention.orphan-grace-hours"

    /** Every key `ConfigValidator.snapshotFrom` reads off the environment for these rules. */
    val KEYS: List<String> =
        listOf(
            ENABLED,
            NAME,
            MODE,
            STORAGE_KIND,
            BUCKET,
            PREFIX_KEY,
            LANDING_PREFIX,
            REGION,
            ENDPOINT,
            ACCESS_KEY,
            SECRET_KEY,
            READER_ACCESS_KEY,
            READER_SECRET_KEY,
            MAX_CONCURRENT_INGESTIONS,
            MAX_CONCURRENT_MAINTENANCE,
            MAX_DELIVERY_BYTES,
            STAGING_EXPANSION_FACTOR,
            SCRATCH_BYTES,
            STAGING_MEMORY_LIMIT,
            MAX_OPEN_PARTITIONS,
            JOB_TIMEOUT,
            LEASE_WAIT_SECONDS,
            LOCK_TIMEOUT_SECONDS,
            STATEMENT_TIMEOUT_SECONDS,
            MAX_GENERATION_AGE_SECONDS,
            FRESHNESS_TICK_SECONDS,
            READS_MEMORY_LIMIT,
            MAINTENANCE_WINDOW,
            MAX_BYTES_REWRITTEN,
            PROTECTION_DAYS,
            ORPHAN_GRACE_HOURS,
        )

    /** The credential keys: a rule needs only whether each is set, so the snapshot never holds their values. */
    val CREDENTIAL_KEYS: Set<String> = setOf(ACCESS_KEY, SECRET_KEY, READER_ACCESS_KEY, READER_SECRET_KEY)

    /** What `ConfigSnapshot.lake` carries for [key]: the raw value, or for a credential only [PRESENT] / empty. */
    fun carried(
        key: String,
        raw: String?,
    ): String? = if (key in CREDENTIAL_KEYS) (if (raw.isNullOrBlank()) "" else PRESENT) else raw

    /** A set credential's stand-in in the snapshot (its toString may be logged; the value never is). */
    const val PRESENT = "<set>"

    private val MODES = setOf("normal", "restoring", "replaying")
    private val LAKE_NAME = Regex("^[a-z0-9_]{1,63}$")
    private val WINDOW = Regex("^([01]\\d|2[0-3]):[0-5]\\d-([01]\\d|2[0-3]):[0-5]\\d$")
    private val ENDPOINT_SCHEMES = setOf("http", "https")

    /** The fixed allowance for the object store's clock against the catalog's `now()` (record §8a, call 33). */
    private const val SKEW_ALLOWANCE_SECONDS = 3_600L
    private const val SECONDS_PER_HOUR = 3_600L

    /** §3.2's default for `node-query-timeout-max-seconds`, read when the key is unset (ConfigValidator's twin). */
    private const val DEFAULT_NODE_QUERY_TIMEOUT_MAX_SECONDS = 900L

    /** §3.2's default for `node-query-timeout-seconds`, read when the key is unset (the executor's own default). */
    private const val DEFAULT_NODE_QUERY_TIMEOUT_SECONDS = 60L

    /** The share of the protection window the read bounds may use (record §10 RULING 4: one hour of a 24-hour day). */
    private const val PROTECTION_SHARE_DIVISOR = 24L
    private const val SECONDS_PER_DAY = 86_400L

    private fun enabled(snapshot: ConfigSnapshot): Boolean = snapshot.lake[ENABLED]?.trim()?.equals("true", ignoreCase = true) == true

    private fun value(
        snapshot: ConfigSnapshot,
        key: String,
    ): String = snapshot.lake[key]?.trim().orEmpty()

    /** enabled → a bucket and a region or an endpoint; a known kind, mode and name; a usable endpoint; whole key pairs. */
    fun checkLakeStorage(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        if (!enabled(snapshot)) return
        if (value(snapshot, STORAGE_KIND) != "s3") violations += "$STORAGE_KIND must be 's3' (§3.36; the only kind)."
        if (value(snapshot, BUCKET).isEmpty()) violations += "$BUCKET is required when $ENABLED=true (§3.36)."
        val endpoint = value(snapshot, ENDPOINT)
        if (value(snapshot, REGION).isEmpty() && endpoint.isEmpty()) {
            violations += "$REGION or $ENDPOINT is required when $ENABLED=true (§3.36)."
        }
        if (endpoint.isNotEmpty() && !usableEndpoint(endpoint)) {
            violations += "$ENDPOINT must be an http:// or https:// URL with a host and no credentials, query or fragment " +
                "(§3.36); its scheme decides TLS."
        }
        if (value(snapshot, MODE) !in MODES) violations += "$MODE must be one of $MODES (§3.36)."
        if (!LAKE_NAME.matches(value(snapshot, NAME))) violations += "$NAME must match [a-z0-9_]{1,63} (§3.36)."
        requirePair(snapshot, ACCESS_KEY, SECRET_KEY, violations)
        requirePair(snapshot, READER_ACCESS_KEY, READER_SECRET_KEY, violations)
    }

    /** `protection-days ≥ 1`, and the orphan grace above the longest lease-free life of a run's output plus the skew. */
    fun checkLakeRetention(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        if (!enabled(snapshot)) return
        positiveLong(snapshot, PROTECTION_DAYS, violations)
        val grace = positiveLong(snapshot, ORPHAN_GRACE_HOURS, violations)
        val job = duration(snapshot, JOB_TIMEOUT, violations)
        val statement = positiveLong(snapshot, STATEMENT_TIMEOUT_SECONDS, violations)
        if (grace != null && job != null && statement != null) {
            val bound = job.seconds + statement + SKEW_ALLOWANCE_SECONDS
            if (grace * SECONDS_PER_HOUR <= bound) {
                violations +=
                    "$ORPHAN_GRACE_HOURS × 3600 (${grace * SECONDS_PER_HOUR}) must exceed $JOB_TIMEOUT + " +
                    "$STATEMENT_TIMEOUT_SECONDS + 3600 ($bound) (§3.36 — the remover's safety premise)."
            }
        }
    }

    /** The lease wait under the node query ceiling; a delivery's staging footprint within the scratch budget. */
    fun checkLakeAdmission(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        if (!enabled(snapshot)) return
        positiveLong(snapshot, MAX_CONCURRENT_INGESTIONS, violations)
        positiveLong(snapshot, MAX_CONCURRENT_MAINTENANCE, violations)
        positiveLong(snapshot, MAX_OPEN_PARTITIONS, violations)
        dataSize(snapshot, STAGING_MEMORY_LIMIT, violations)
        val wait = positiveLong(snapshot, LEASE_WAIT_SECONDS, violations)
        val ceiling = snapshot.executorNodeQueryTimeoutMaxSeconds?.trim()?.toLongOrNull() ?: DEFAULT_NODE_QUERY_TIMEOUT_MAX_SECONDS
        if (wait != null && wait >= ceiling) {
            violations +=
                "$LEASE_WAIT_SECONDS ($wait) must be below datapipelines.executor.node-query-timeout-max-seconds ($ceiling) (§3.36)."
        }
        val delivery = dataSize(snapshot, MAX_DELIVERY_BYTES, violations)
        val factor = positiveLong(snapshot, STAGING_EXPANSION_FACTOR, violations)
        val scratch = dataSize(snapshot, SCRATCH_BYTES, violations)
        if (delivery == null || factor == null || scratch == null) return
        if (delivery.toBytes() > scratch.toBytes() / factor) {
            violations += "$MAX_DELIVERY_BYTES × $STAGING_EXPANSION_FACTOR must not exceed $SCRATCH_BYTES (§3.36)."
        }
    }

    /** The catalog timeouts and the maintenance budget positive; the window `HH:MM-HH:MM` (UTC). */
    fun checkLakeBounds(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        if (!enabled(snapshot)) return
        positiveLong(snapshot, LOCK_TIMEOUT_SECONDS, violations)
        dataSize(snapshot, MAX_BYTES_REWRITTEN, violations)
        if (!WINDOW.matches(value(snapshot, MAINTENANCE_WINDOW))) {
            violations += "$MAINTENANCE_WINDOW must be HH:MM-HH:MM in UTC (§3.36)."
        }
    }

    /**
     * The managed reads (record §10 RULING 4, #818): the tick below the age bound (a tick at or above it would
     * never retire a generation before the bound does), the reader's memory bound a positive size, and the
     * longest a generation can keep serving a snapshot within an hour of every protected day, so no reader can
     * hold a snapshot the remover may already have expired.
     *
     * #818c (RULING 4 amended 2026-11-05) — that longest is `age bound + 2 × reaper delay + retire ceiling + one
     * tick`: the reaper retires an over-age generation with no borrower within one [ReaperDelay] of its age bound,
     * and hard-closes it within one more of its ceiling. The delay is [ReaperDelay.SECONDS], never typed here.
     *
     * #818e: this bounds staleness; live reader leases independently pin snapshot expiry.
     */
    fun checkLakeReads(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        if (!enabled(snapshot)) return
        dataSize(snapshot, READS_MEMORY_LIMIT, violations)
        val age = positiveLong(snapshot, MAX_GENERATION_AGE_SECONDS, violations)
        val tick = positiveLong(snapshot, FRESHNESS_TICK_SECONDS, violations)
        if (age != null && tick != null && tick >= age) {
            violations += "$FRESHNESS_TICK_SECONDS ($tick) must be below $MAX_GENERATION_AGE_SECONDS ($age) (§3.36)."
        }
        val days = value(snapshot, PROTECTION_DAYS).toLongOrNull()?.takeIf { it > 0 } ?: return // checkLakeRetention names it
        val ceiling = retireCeilingSeconds(snapshot, violations) ?: return
        if (age == null || tick == null) return
        val window = days * SECONDS_PER_DAY / PROTECTION_SHARE_DIVISOR
        val reaperDelay = ReaperDelay.SECONDS
        if (age + 2 * reaperDelay + ceiling + tick > window) {
            violations +=
                "$MAX_GENERATION_AGE_SECONDS ($age) + 2 × the reaper delay ($reaperDelay) + the retire ceiling ($ceiling) + " +
                "$FRESHNESS_TICK_SECONDS ($tick) must not exceed " +
                "$PROTECTION_DAYS × 86400 / 24 ($window) (§3.36 — staleness bound; reader leases protect live readers)."
        }
    }

    /** The ceiling `datasources` applies (datasources.md §5.2); a malformed explicit value is named here. */
    private fun retireCeilingSeconds(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ): Long? {
        val explicit = snapshot.datasourcesRetireCeilingSeconds?.trim().orEmpty()
        if (explicit.isNotEmpty()) {
            val parsed = explicit.toLongOrNull()?.takeIf { it > 0 }
            if (parsed == null) violations += "datapipelines.datasources.retire-ceiling-seconds must be a positive whole number (§3.36)."
            return parsed
        }
        // A malformed node timeout is named by the executor's own check; fall back to its default here.
        val nodeTimeout =
            snapshot.executorNodeQueryTimeoutSeconds
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0 } ?: DEFAULT_NODE_QUERY_TIMEOUT_SECONDS
        return nodeTimeout + DatasourcesProperties.CEILING_SLACK_SECONDS
    }

    private fun usableEndpoint(endpoint: String): Boolean =
        runCatching { URI(endpoint) }
            .getOrNull()
            ?.let {
                it.scheme?.lowercase() in ENDPOINT_SCHEMES && !it.host.isNullOrBlank() &&
                    it.userInfo == null && it.query == null && it.fragment == null
            } == true

    private fun requirePair(
        snapshot: ConfigSnapshot,
        first: String,
        second: String,
        violations: MutableList<String>,
    ) {
        if (value(snapshot, first).isEmpty() != value(snapshot, second).isEmpty()) {
            violations += "$first and $second are set together or not at all (§3.36)."
        }
    }

    private fun positiveLong(
        snapshot: ConfigSnapshot,
        key: String,
        violations: MutableList<String>,
    ): Long? {
        val parsed = value(snapshot, key).toLongOrNull()?.takeIf { it > 0 }
        if (parsed == null) violations += "$key must be a positive whole number (§3.36)."
        return parsed
    }

    private fun dataSize(
        snapshot: ConfigSnapshot,
        key: String,
        violations: MutableList<String>,
    ): DataSize? {
        val parsed = runCatching { DataSize.parse(value(snapshot, key)) }.getOrNull()?.takeIf { it.toBytes() > 0 }
        if (parsed == null) violations += "$key must be a positive size such as 2GB or 512MB (§3.36)."
        return parsed
    }

    private fun duration(
        snapshot: ConfigSnapshot,
        key: String,
        violations: MutableList<String>,
    ): Duration? {
        val parsed = runCatching { DurationStyle.detectAndParse(value(snapshot, key)) }.getOrNull()?.takeIf { !it.isNegative && !it.isZero }
        if (parsed == null) violations += "$key must be a positive duration such as 2h or 90m (§3.36)."
        return parsed
    }
}
