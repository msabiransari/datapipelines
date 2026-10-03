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
     * The write surface chip (V20, 102): "Muhammad · via MCP" — `session` renders nothing.
     * A DRAFT row shows its LAST WRITE's surface (updated_via); a locked row shows the
     * surface that created it.
     */
    val via: String,
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
    /**
     * #349 — the row is the version the workspace page is VIEWING. The explorer's detail pane
     * has no viewed version (it shows the working one), so its rows leave this false; the
     * workspace's Versions tab and header selector mark the viewed row with it.
     */
    val isViewed: Boolean = false,
    /** A DRAFT: `POST /{id}/release` would take it. */
    val canRelease: Boolean,
    /** A RELEASED version that nothing pins: `POST /{id}/versions/{v}/discard` would take it. */
    val canDiscard: Boolean,
    /** A DRAFT: `DELETE /{id}/versions/{v}` would purge it (irreversibly). */
    val canPurge: Boolean,
    /** A DISCARDED version: `POST /{id}/versions/{v}/restore` would bring it back. */
    val canRestore: Boolean,
    /** A RELEASED version that is not current: the Switch dialog would take it (§3.4). */
    val canSwitch: Boolean,
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
            via: String = co.datapipelines.pipeline.WriteSurface.SESSION.wire,
            isViewed: Boolean = false,
        ): VersionRowView =
            VersionRowView(
                version = version,
                status = status,
                createdAt = createdAt,
                createdAgo = RelativeTime.since(createdAt, now),
                actor = actor,
                via = via,
                usageLabel = "$usage $usageUnit" + if (usage == 1) "" else "s",
                isCurrent = isCurrent,
                isViewed = isViewed,
                canRelease = status == PipelineVersionStatus.DRAFT,
                canDiscard = status == PipelineVersionStatus.RELEASED,
                canPurge = status == PipelineVersionStatus.DRAFT,
                canRestore = status == PipelineVersionStatus.DISCARDED,
                // Switching to the version that is ALREADY current is a legal no-op (§3.5),
                // but the row that owns the pointer does not offer the lever to itself.
                canSwitch = status == PipelineVersionStatus.RELEASED && !isCurrent,
            )
    }
}

/** One datasource a pipeline's working body reads or writes, with the dialect it speaks. */
data class DatasourceRowView(
    val name: String,
    val dialect: Dialect,
)

/**
 * #349 — the workspace Overview's record-level and registry-resolved facts, handed to the
 * page controller so the workspace JSON can carry them once. Everything here is either
 * version-independent (the created actor and surface, the last visible run) or pre-resolved
 * for every admitted version (the datasource dialect map), so an in-page version switch
 * updates the pane without a second server round trip.
 */
data class WorkspaceTabFacts(
    val createdBy: String,
    /** The FIRST version's write surface (V20's chip) — `session` renders nothing. */
    val createdVia: String?,
    /** The pipeline's last visible run, in the explorer Overview's own shape, or null. */
    val lastRun: WorkspaceLastRunView?,
    /** datasource name → dialect wire name, across every admitted version's body. */
    val datasourceDialects: Map<String, String>,
)

/** One last-visible-run line for the workspace Overview — the explorer's dd, as data. */
data class WorkspaceLastRunView(
    val executionId: UUID,
    val status: String,
    val durationMs: Long?,
    val rowCount: Int?,
    /** "4 mins ago" — the reading; [at] is the exact stamp for the hover. */
    val ago: String,
    val at: String,
    val by: String,
)

/**
 * The Usage tab's answer: **what the server would refuse a discard over**.
 *
 * Deliberately the same evidence 101's refusals name — `PipelineRepository`'s live-parent pin
 * query (the one `PipelineService.refuseIfPinned` runs), the published-endpoints registry and,
 * since #259, the schedules whose target names the pipeline (the scheduler's by-target read,
 * lensed) — so the list the user reads before pressing Discard is the list the server will
 * decide on. Nothing here re-implements a rule; it asks the same questions earlier.
 */
data class UsageView(
    val endpoints: List<EndpointUse>,
    val parents: List<ParentUse>,
    val schedules: List<ScheduleUse> = emptyList(),
    /** #320 — the dashboards whose sources pin a version of this pipeline: refusal evidence, like [parents]. */
    val dashboards: List<DashboardUse> = emptyList(),
) {
    val total: Int get() = endpoints.size + parents.size + schedules.size + dashboards.size

    /** A published endpoint serving this pipeline (`GET /api{path}`). */
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

    /** A live dashboard version whose source pins [pinnedVersion] of this pipeline (#320, `pipeline.version.pinned`). */
    data class DashboardUse(
        val name: String,
        val version: Int,
        val status: String,
        val pinnedVersion: Int,
    )

    /** A live schedule whose `target_ref` names this pipeline (#259) — name, id and state only. */
    data class ScheduleUse(
        val id: UUID,
        val name: String,
        val state: String,
    )
}
