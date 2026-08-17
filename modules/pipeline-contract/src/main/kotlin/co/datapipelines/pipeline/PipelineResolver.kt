package co.datapipelines.pipeline

/**
 * Resolves pinned pipeline references at save time (design 2026-08-13-pipeline-node-type §3, D5).
 *
 * pipeline-contract cannot depend on the persistence layer, so — like [TemplateDryRenderer] and
 * [DatasourceRegistry] before it — the module declares this port and the assembling layer
 * supplies the repository-backed implementation.
 */
fun interface PipelineResolver {
    /**
     * The pinned version's parsed body, or null when the name or the pinned version is unknown.
     *
     * A soft-deleted pipeline still **resolves** — soft-delete does not affect existing pinned
     * references (D7) — and comes back with [ResolvedPipeline.deleted] set, so save-time
     * validation can block the NEW reference with `pipeline_reference_deleted`.
     */
    fun resolve(
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
