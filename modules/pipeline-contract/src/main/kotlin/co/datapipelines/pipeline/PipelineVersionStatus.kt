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
 *   names one of these except under development posture, where the §3.4 fallback may name
 *   the draft.
 * - [DISCARDED] — a RELEASED version that was retired (101): the row stays (executions,
 *   promotion history and parents reference it) and [restore][PipelineService.restoreVersion]
 *   can bring it back. The pre-101 executed-draft tombstone meaning is withdrawn — drafts
 *   are PURGED with their executions and never reach this status.
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

        /** True where the pointer may name this version under the given posture (D60). */
        fun eligibleForPointer(
            status: PipelineVersionStatus,
            draftsEligible: Boolean,
        ): Boolean = status == RELEASED || (draftsEligible && status == DRAFT)
    }
}

/**
 * One `pipeline_versions` row's lifecycle metadata, without the body — the projection the
 * draft/release protocol reads and writes (versioning §4/§11).
 *
 * [bodyHash] is the row's SHA-256 content hash — the precondition token every mutation
 * carries and the cross-server content identity. [releasedAt] is database-generated at
 * release (never application-supplied; versioning §8's precondition) and is NOT touched by
 * discard or restore — a restored version keeps the release stamp of its one true release
 * (§8's draft-run derivation depends on that). [discardedAt]/[discardedBy] are the discard
 * stamps (§3.1, V19): both NULL unless the status is DISCARDED. [updatedBy] /
 * [updatedAt] carry the last DRAFT write, powering the 409 conflict details; they are
 * whatever the last draft write left on the row, including after release. [createdVia] /
 * [updatedVia] are the write-surface stamps (V20, 102): which door the write arrived on,
 * while the `_by` fields keep naming the person.
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
    val discardedAt: Instant? = null,
    val discardedBy: UUID? = null,
    val updatedBy: UUID? = null,
    val updatedAt: Instant? = null,
    val createdVia: String = WriteSurface.SESSION.wire,
    val updatedVia: String = WriteSurface.SESSION.wire,
)
