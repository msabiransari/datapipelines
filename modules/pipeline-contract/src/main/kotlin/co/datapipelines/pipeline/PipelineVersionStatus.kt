package co.datapipelines.pipeline

import java.time.Instant
import java.util.UUID

/**
 * The lifecycle status of a `pipeline_versions` / `template_versions` row (versioning §3.1).
 *
 * - [DRAFT] — the one mutable working copy. At most one per pipeline/template at any time,
 *   enforced by the partial unique indexes `uq_pipeline_versions_one_draft` /
 *   `uq_template_versions_one_draft`.
 * - [RELEASED] — the locked, executable, immutable version. `pipelines.current_version`
 *   always names one of these.
 * - [DISCARDED] — an executed draft that was thrown away: the `pipeline_executions`
 *   composite FK blocks the hard delete, so the row flips here and its version number
 *   stays consumed.
 */
enum class PipelineVersionStatus {
    DRAFT,
    RELEASED,
    DISCARDED,
    ;

    companion object {
        /** Strict read of a stored `status` cell — an unknown value is corruption, not a default. */
        fun fromWire(raw: String): PipelineVersionStatus =
            entries.firstOrNull { it.name == raw }
                ?: error("Unknown version status '$raw'")
    }
}

/**
 * One `pipeline_versions` row's lifecycle metadata, without the body — the projection the
 * draft/release protocol reads and writes (versioning §4/§11).
 *
 * [bodyHash] is the row's SHA-256 content hash — the precondition token every mutation
 * carries and the cross-server content identity. [releasedAt] is database-generated at
 * release (never application-supplied; versioning §8's precondition). [updatedBy] /
 * [updatedAt] carry the last DRAFT write, powering the 409 conflict details; they are
 * whatever the last draft write left on the row, including after release.
 */
data class PipelineVersionDetail(
    val pipelineId: UUID,
    val version: Int,
    val status: PipelineVersionStatus,
    val bodyHash: String,
    val createdAt: Instant,
    val createdBy: UUID,
    val releasedAt: Instant? = null,
    val releasedBy: UUID? = null,
    val updatedBy: UUID? = null,
    val updatedAt: Instant? = null,
)
