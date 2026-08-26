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
     * A soft-deleted pipeline still **resolves** — soft-delete does not affect existing pinned
     * references (D7) — and comes back with [ResolvedPipeline.deleted] set, so save-time
     * validation can block the NEW reference with `pipeline_reference_deleted`.
     */
    fun resolve(
        workspaceId: UUID,
        name: String,
        version: Int,
    ): ResolvedPipeline?
}

/** The outcome of a successful [PipelineResolver.resolve]. */
data class ResolvedPipeline(
    /** The pinned version's parsed body. */
    val pipeline: Pipeline,
    /** True when the pipeline is soft-deleted: resolves for existing references, blocked for new ones (D7). */
    val deleted: Boolean,
)
