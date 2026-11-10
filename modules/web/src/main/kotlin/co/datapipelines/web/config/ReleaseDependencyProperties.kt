package co.datapipelines.web.config

import co.datapipelines.application.dependencies.ReleasePlanningLimits
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.pipeline.release.ReleaseBounds
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The `datapipelines.release.dependencies.*` keys ([Configuration §3.35](configuration.md), the one definition) — the
 * release-dependency bounds of the release-dependencies record §1.2 (ruled §17 row 4). The defaults equal the documented
 * ones and application.yml's; `ReleaseDependencyConfigKeysSpecDriftTest` holds the three together. Each bound is
 * range-checked here, so a bad value refuses the boot naming its key; the family-neutral core never sees this class,
 * only the plain [ReleaseBounds] value [toBounds] hands it.
 */
@ConfigurationProperties(prefix = "datapipelines.release.dependencies")
data class ReleaseDependencyProperties(
    /** Longest dependency path from the root, in edges. */
    val maxDepth: Int = ReleaseBounds.DEFAULT_MAX_DEPTH,
    /** Distinct versions in one closure, the root included. */
    val maxVersions: Int = ReleaseBounds.DEFAULT_MAX_VERSIONS,
    /** Pin occurrences in one closure. */
    val maxEdges: Int = ReleaseBounds.DEFAULT_MAX_EDGES,
    /** Blockers reported in one refusal; the rest set `blockers_truncated`. */
    val maxBlockers: Int = ReleaseBounds.DEFAULT_MAX_BLOCKERS,
    /** Bytes of one plan request or of a plan's canonical serialisation. */
    val maxWireBytes: Int = ReleaseBounds.DEFAULT_MAX_WIRE_BYTES,
    /** Seconds the workspace mutation guard waits for its lock before `<family>.release.busy`. */
    val lockWaitSeconds: Int = ReleaseBounds.DEFAULT_LOCK_WAIT_SECONDS,
    /** #786 §E.3 (record §25.4) — at most this many requests of one family per provider call in a release plan. */
    val maxResolveBatchSize: Int = DEFAULT_MAX_RESOLVE_BATCH_SIZE,
    /** #786 §E.3 — the ONE cumulative deadline of a release plan's metadata reads; expired → `<family>.release.metadata_unavailable`. */
    val metadataTimeoutSeconds: Int = DEFAULT_METADATA_TIMEOUT_SECONDS,
    /** #786 §E.3 — the ceiling of any one provider statement inside the plan's deadline; never above [metadataTimeoutSeconds]. */
    val statementTimeoutSeconds: Int = DEFAULT_STATEMENT_TIMEOUT_SECONDS,
) {
    init {
        require(maxDepth in 1..MAX_DEPTH_CEILING) { "$PREFIX.max-depth must be 1..$MAX_DEPTH_CEILING, was $maxDepth" }
        require(maxVersions in 1..MAX_VERSIONS_CEILING) {
            "$PREFIX.max-versions must be 1..$MAX_VERSIONS_CEILING, was $maxVersions"
        }
        require(maxEdges in 1..MAX_EDGES_CEILING) { "$PREFIX.max-edges must be 1..$MAX_EDGES_CEILING, was $maxEdges" }
        require(maxBlockers in 1..MAX_BLOCKERS_CEILING) {
            "$PREFIX.max-blockers must be 1..$MAX_BLOCKERS_CEILING, was $maxBlockers"
        }
        require(maxWireBytes in MIN_WIRE_BYTES..MAX_WIRE_BYTES_CEILING) {
            "$PREFIX.max-wire-bytes must be $MIN_WIRE_BYTES..$MAX_WIRE_BYTES_CEILING, was $maxWireBytes"
        }
        require(lockWaitSeconds in 1..MAX_LOCK_WAIT_SECONDS) {
            "$PREFIX.lock-wait-seconds must be 1..$MAX_LOCK_WAIT_SECONDS, was $lockWaitSeconds"
        }
        require(maxResolveBatchSize in 1..GraphLimits.MAX_RESOLVE_BATCH_SIZE) {
            "$PREFIX.max-resolve-batch-size must be 1..${GraphLimits.MAX_RESOLVE_BATCH_SIZE}, was $maxResolveBatchSize"
        }
        require(metadataTimeoutSeconds in 1..MAX_METADATA_TIMEOUT_SECONDS) {
            "$PREFIX.metadata-timeout-seconds must be 1..$MAX_METADATA_TIMEOUT_SECONDS, was $metadataTimeoutSeconds"
        }
        require(statementTimeoutSeconds in 1..MAX_STATEMENT_TIMEOUT_SECONDS && statementTimeoutSeconds <= metadataTimeoutSeconds) {
            "$PREFIX.statement-timeout-seconds must be 1..$MAX_STATEMENT_TIMEOUT_SECONDS and at most " +
                "$PREFIX.metadata-timeout-seconds ($metadataTimeoutSeconds), was $statementTimeoutSeconds"
        }
    }

    /** The planning walk's batch and time bounds (record §25.4). */
    fun toPlanningLimits(): ReleasePlanningLimits =
        ReleasePlanningLimits(
            resolveBatchSize = maxResolveBatchSize,
            metadataTimeout = Duration.ofSeconds(metadataTimeoutSeconds.toLong()),
            statementTimeout = Duration.ofSeconds(statementTimeoutSeconds.toLong()),
        )

    /** The plain value the core reads. */
    fun toBounds(): ReleaseBounds =
        ReleaseBounds(
            maxDepth = maxDepth,
            maxVersions = maxVersions,
            maxEdges = maxEdges,
            maxBlockers = maxBlockers,
            maxWireBytes = maxWireBytes,
            lockWaitSeconds = lockWaitSeconds,
        )

    companion object {
        const val PREFIX = "datapipelines.release.dependencies"

        /** The documented ranges' upper ends (configuration.md §3.35). */
        const val MAX_DEPTH_CEILING = 256
        const val MAX_VERSIONS_CEILING = 100_000
        const val MAX_EDGES_CEILING = 1_000_000
        const val MAX_BLOCKERS_CEILING = 1_000
        const val MIN_WIRE_BYTES = 1_024
        const val MAX_WIRE_BYTES_CEILING = 16_777_216

        /** A lock wait longer than a minute would hold an HTTP request thread past any sane client timeout. */
        const val MAX_LOCK_WAIT_SECONDS = 60

        /** #786 §E.3 defaults and ranges (configuration.md §3.35; record §25.4). */
        const val DEFAULT_MAX_RESOLVE_BATCH_SIZE = GraphLimits.DEFAULT_RESOLVE_BATCH_SIZE
        const val DEFAULT_METADATA_TIMEOUT_SECONDS = 30
        const val MAX_METADATA_TIMEOUT_SECONDS = 120
        const val DEFAULT_STATEMENT_TIMEOUT_SECONDS = 5
        const val MAX_STATEMENT_TIMEOUT_SECONDS = 30
    }
}
