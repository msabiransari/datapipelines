package co.datapipelines.web.release

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseFlip
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseParticipant
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.pipeline.release.ReleasedDependency
import java.util.UUID

/**
 * A family that can be the ROOT of a coordinated release (#786 C.2; record §4.1, §8 "an API draft version is a
 * releasable root"): its participant steps plus the two things only a root needs — the exact identity a request's entity
 * id names, and the root's own strict audit row. The pipeline root keeps its native path (compatibility alias, check
 * gates, warnings) as the coordinator's first caller; every other root is served by [ReleaseCoordinator.releaseRoot]
 * through this one contract, so a new root family adds an implementation, never a coordinator branch.
 */
interface ReleaseRoot : ReleaseParticipant {
    /** The root's exact release identity — its working draft — or the family's native refusal (no entity, no draft). */
    fun rootIdentity(
        workspaceId: UUID,
        entityId: UUID,
    ): ReleaseNodeIdentity

    /** The root's strict audit row: its event kind and its details (the coordinator adds scope, release id, dependencies). */
    fun auditRow(
        principal: AuthenticatedPrincipal,
        entityId: UUID,
        flip: ReleaseFlip,
    ): Pair<String, Map<String, Any?>>
}

/** A release as a surface asks for it, for any registered root family (#786 C.2: family + id + expected hash). */
data class RootReleaseRequest(
    val family: ReleaseFamily,
    val workspaceId: UUID,
    val entityId: UUID,
    /** The root draft's `If-Match` body hash. */
    val expectedHash: String,
    val actor: UUID,
    /** The plan fields (record §6), null when the request carried none — then only the root flips. */
    val plan: ReleasePlanRequest? = null,
    /**
     * The acting principal when there is no request to read it from (a boot-time act, the demo seeder); null reads the
     * request's. Either way it is judged by the same matrix and named by the same audit rows.
     */
    val principal: AuthenticatedPrincipal? = null,
)

/** What a root release flipped: the root's own flip and every dependency, in release order. */
data class RootReleased(
    val root: ReleaseFlip,
    val dependenciesReleased: List<ReleasedDependency>,
)

/** A release plan read for a dialog (#786 C.3): the walked closure and the tree hash the release posts back. */
data class RootPreview(
    val closure: ReleaseClosure,
    val treeHash: String,
)
