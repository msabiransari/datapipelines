package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.Dialect
import java.time.Instant
import java.util.UUID

// The row shapes the 106 explorer detail renders — one per region, so the markup reads facts
// off a value and never derives a lifecycle rule for itself.
//
// The rule this file exists to hold: a button is rendered because a FLAG says the server would
// accept it, not because a template guessed from a status string. 101 owns the lifecycle
// (versioning §3/§7, D57–D60); these flags are computed once, next to the query that produced
// the rows, and the two partials that render them cannot drift apart.

/** One version in the acting column's Versions tab, with the verbs 101 would accept on it. */
data class VersionRowView(
    val version: Int,
    val status: PipelineVersionStatus,
    val createdAt: Instant,
    /** [createdAt] as "3 days ago" — the reading; the exact stamp rides on `title`. */
    val createdAgo: String,
    val actor: String,
    /**
     * How much this version is USED, already worded.
     *
     * The two explorers count different things — a pipeline version's uses are its
     * executions, a template version's are the pipelines pinning it — and the number is
     * meaningless without its unit. Wording it at the one place that knows which explorer
     * asked is what keeps "7 runs" and "2 pipelines" out of a template's `th:text`.
     */
    val usageLabel: String,
    /** The sticky pointer (D60) points here — "current" in the row, and never the same as "latest". */
    val isCurrent: Boolean,
    /** A DRAFT: `POST /{id}/release` would take it. */
    val canRelease: Boolean,
    /** A RELEASED version that nothing pins: `POST /{id}/versions/{v}/discard` would take it. */
    val canDiscard: Boolean,
    /** A DRAFT: `DELETE /{id}/versions/{v}` would purge it (irreversibly). */
    val canPurge: Boolean,
    /** A DISCARDED version: `POST /{id}/versions/{v}/restore` would bring it back. */
    val canRestore: Boolean,
) {
    companion object {
        // Eight named fields of ONE row. A parameter object here would be this data class
        // again, one indirection away, and the factory exists precisely so the four lifecycle
        // flags are derived in a single place rather than at each call site.
        @Suppress("LongParameterList")
        fun of(
            version: Int,
            status: PipelineVersionStatus,
            createdAt: Instant,
            actor: String,
            now: Instant,
            usage: Int,
            usageUnit: String,
            isCurrent: Boolean,
        ): VersionRowView =
            VersionRowView(
                version = version,
                status = status,
                createdAt = createdAt,
                createdAgo = RelativeTime.since(createdAt, now),
                actor = actor,
                usageLabel = "$usage $usageUnit" + if (usage == 1) "" else "s",
                isCurrent = isCurrent,
                canRelease = status == PipelineVersionStatus.DRAFT,
                canDiscard = status == PipelineVersionStatus.RELEASED,
                canPurge = status == PipelineVersionStatus.DRAFT,
                canRestore = status == PipelineVersionStatus.DISCARDED,
            )
    }
}

/** One datasource a pipeline's working body reads or writes, with the dialect it speaks. */
data class DatasourceRowView(
    val name: String,
    val dialect: Dialect,
)

/** One template version a pipeline's working body pins — `id@version`, linked into §4.6. */
data class TemplatePinView(
    val id: String,
    val version: Int,
) {
    val label: String get() = "$id@$version"
}

/**
 * The Usage tab's answer: **what the server would refuse a discard over**.
 *
 * Deliberately the same evidence 101's refusals name — `PipelineRepository`'s live-parent pin
 * query (the one `PipelineService.refuseIfPinned` runs) and the published-endpoints registry —
 * so the list the user reads before pressing Discard is the list the server will decide on.
 * Nothing here re-implements a rule; it asks the same questions earlier.
 */
data class UsageView(
    val endpoints: List<EndpointUse>,
    val parents: List<ParentUse>,
) {
    val total: Int get() = endpoints.size + parents.size

    /** A published endpoint serving this pipeline (`GET /api/x{path}`). */
    data class EndpointUse(
        val path: String,
        val enabled: Boolean,
        val description: String,
    )

    /** A live pipeline version whose node pins a version of this pipeline. */
    data class ParentUse(
        val pipelineId: UUID,
        val pipelineName: String,
        val pipelineVersion: Int,
        val nodeId: String,
        val pinnedVersion: Int,
    )
}
