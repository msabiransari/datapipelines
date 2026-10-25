package co.datapipelines.pipeline.release

import co.datapipelines.pipeline.PipelineReleaseService
import java.util.UUID

/**
 * The port `PipelineService.release` delegates to when the assembling layer wires one (record §4.2: contracts here,
 * the coordinator in `web`): every pipeline release — the REST route's and the dialog's — then runs through ONE
 * coordinator and ONE metadata transaction. A directly constructed service (unit tests) leaves it unset and releases
 * natively.
 */
fun interface PipelineReleaseCoordination {
    fun release(request: PipelineReleaseRequest): PipelineReleaseService.Released
}

/** A pipeline release as the surfaces ask for it. */
data class PipelineReleaseRequest(
    val workspaceId: UUID,
    val pipelineId: UUID,
    val expectedHash: String,
    val actor: UUID,
    val overrideChecksReason: String? = null,
    /** The legacy alias `release_pinned_templates` (record §6): scoped to the old direct template edges. */
    val releasePinnedTemplates: Boolean = false,
    /** The plan fields (record §6), null when the request carried none. */
    val plan: ReleasePlanRequest? = null,
)

/**
 * The submitted plan (record §6): the preview's `tree_hash`, the DRAFT dependencies the caller ticks (node ids of that
 * preview), and per-node check overrides. Each field is null when absent — absent and empty differ only for the
 * arming rule (a present field is a plan field).
 */
data class ReleasePlanRequest(
    val treeHash: String? = null,
    val includeDependencies: List<String>? = null,
    val checkOverrides: List<CheckOverride>? = null,
) {
    /** True when any plan field was supplied. */
    val present: Boolean get() = treeHash != null || includeDependencies != null || checkOverrides != null
}

/** One `check_overrides` entry: a node of the preview and the audited reason its failing checks are waived. */
data class CheckOverride(
    val nodeId: String,
    val reason: String,
)
