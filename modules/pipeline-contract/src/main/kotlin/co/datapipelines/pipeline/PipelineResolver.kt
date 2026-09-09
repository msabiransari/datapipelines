package co.datapipelines.pipeline

import java.util.UUID

/**
 * Resolves pinned pipeline references at save time (design 2026-08-13-pipeline-node-type §3, D5).
 *
 * pipeline-contract cannot depend on the persistence layer, so — like [TemplateDryRenderer] and
 * [DatasourceRegistry] before it — the module declares this port and the assembling layer
 * supplies the repository-backed implementation.
 *
 * References resolve **within the referenced pipeline's workspace** — the [workspaceId] of the
 * pipeline being validated (design 2026-08-16-workspaces §3: cross-workspace references do not
 * exist in v1). No default: validation without an explicit workspace must not compile.
 */
fun interface PipelineResolver {
    /**
     * The pinned version's parsed body, or null when the name or the pinned version is unknown
     * in [workspaceId].
     *
     * A pipeline whose entity is DISCARDED (every version discarded — the derived status that
     * replaced soft delete in 101) still **resolves** — discarding does not affect existing
     * pinned references (D7) — and comes back with [ResolvedPipeline.entityDiscarded] set, so
     * save-time validation can block the NEW reference with `pipeline_reference_deleted`.
     */
    fun resolve(
        workspaceId: UUID,
        name: String,
        version: Int,
    ): ResolvedPipeline?
}

/**
 * The outcome of a successful [PipelineResolver.resolve].
 *
 * [versionStatus] is the PINNED VERSION's lifecycle status (101/D58): composition references
 * reviewed content only, so a pin on anything but a RELEASED child version is refused at
 * save time — a DRAFT child can be purged out from under its parent, which an exact-version
 * pin must never allow.
 */
data class ResolvedPipeline(
    /** The pinned version's parsed body. */
    val pipeline: Pipeline,
    /**
     * True when the referenced entity is DISCARDED (every version discarded): resolves for
     * existing references, blocked for new ones (D7).
     */
    val entityDiscarded: Boolean,
    /** The pinned version's own status — D58's save-time check reads it. */
    val versionStatus: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
)
