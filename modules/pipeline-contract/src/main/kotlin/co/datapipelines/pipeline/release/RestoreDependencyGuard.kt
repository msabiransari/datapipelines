package co.datapipelines.pipeline.release

import co.datapipelines.typesystem.DatapipelinesException

/**
 * A RESTORE's transitive metadata-only recheck (#873 §D.0; record §24 "Restore checks outgoing pins transitively", §25.2
 * row G2, §25.4 D15). A restore lands a DISCARDED version RELEASED without a release's consent, and a DISCARDED version
 * protects nothing, so anything its body pins — and anything THAT pins — may have been discarded, purged or never
 * released since. The domain modules (`pipeline-contract`, `parameters`, `visualization`, `templates`) call this port;
 * `application` implements it over the family registry and `web` binds it, so the dependency direction stays acyclic.
 *
 * [recheck] reads the stored body of the DISCARDED root through its family's provider (the root-only
 * exception: a DISCARDED node is terminal in every walk, so the walk never expands it), and walks that body's outgoing
 * references as the roots of ONE lifecycle-guard walk in the caller's transaction, under the guard the restore already
 * holds. Metadata only: no customer probe, no evidence re-run, no datasource, type or dialect revalidation, no release.
 * Every reachable node that is not RELEASED is one [RestoreReach], in walk order; a DISCARDED or MISSING descendant stays
 * terminal. A cycle or a walk bound answers the root family's `dependency_cycle` / `limit_exceeded`; an expired deadline
 * its `metadata_unavailable`.
 */
interface RestoreDependencyGuard {
    /** Every not-RELEASED node the stored body of the DISCARDED version [root] reaches; empty when the root is absent. */
    fun recheck(root: ReleaseNodeIdentity): List<RestoreReach>

    /**
     * The same walk from [pins] — the stored body's pins as the caller extracted them with its family's own extraction
     * (a pipeline: `PipelineDependencySource.pinsOf`, the function its provider uses), for a family whose provider emits
     * nothing for a DISCARDED version.
     */
    fun recheck(
        root: ReleaseNodeIdentity,
        pins: List<ReleasePin>,
    ): List<RestoreReach>

    companion object {
        /** No transitive recheck: the native DIRECT checks alone (a directly constructed service in a unit test). */
        val DIRECT_ONLY =
            object : RestoreDependencyGuard {
                override fun recheck(root: ReleaseNodeIdentity): List<RestoreReach> = emptyList()

                override fun recheck(
                    root: ReleaseNodeIdentity,
                    pins: List<ReleasePin>,
                ): List<RestoreReach> = emptyList()
            }
    }
}

/**
 * One not-RELEASED node a restored body reaches: its identity and name, its [status] (DRAFT, DISCARDED or MISSING), the
 * restored body's own field the reach starts from ([directPath]), the dot-joined [path] down to it, and the RELEASED or
 * DRAFT nodes [via] which it is reached — empty for the body's own pin, the direct pin first otherwise.
 */
data class RestoreReach(
    val identity: ReleaseNodeIdentity,
    val name: String,
    val status: ReleaseNodeStatus,
    val directPath: String,
    val path: String,
    val via: List<ReleaseNodeIdentity>,
) {
    /** Reached through at least one intermediate node (record §25.2 row G2). */
    val transitive: Boolean get() = via.isNotEmpty()

    /** Missing, or DISCARDED: the native 400 of every family. */
    val dangling: Boolean get() = status == ReleaseNodeStatus.MISSING || status == ReleaseNodeStatus.DISCARDED

    /** `via` as the refusal details spell it: each intermediate's family word, key and version, the direct pin first. */
    fun viaDetails(): List<Map<String, Any?>> =
        via.map { linkedMapOf("family" to it.family.wire, "name" to it.entityId, "version" to it.version) }
}

/** The shared mapping of [RestoreReach]es onto the restore refusals every family answers alike. */
object RestoreRechecks {
    /**
     * The 409 `<root family>.release.dependency_not_released` for the DRAFT [reaches], in the shared restore-blockers
     * shape ([ReleaseRefusals.restoreDependencyNotReleased], `reason: not_released`), each transitive blocker carrying
     * `via` — or null when none is a DRAFT. [extra] are the family's own DIRECT draft blockers, listed first.
     */
    fun notReleased(
        root: ReleaseNodeIdentity,
        rootName: String,
        reaches: List<RestoreReach>,
        maxBlockers: Int,
        extra: List<RestoreBlocker> = emptyList(),
    ): DatapipelinesException? {
        val drafts = reaches.filter { it.status == ReleaseNodeStatus.DRAFT }
        if (drafts.isEmpty() && extra.isEmpty()) return null
        val blockers = extra + drafts.map { RestoreBlocker(it.identity, it.name, it.status, it.path) }
        val refusal = ReleaseRefusals.restoreDependencyNotReleased(root, rootName, blockers, maxBlockers)
        val viaOf = drafts.filter { it.transitive }.groupBy { it.identity }.mapValues { (_, hits) -> hits.first().viaDetails() }
        if (viaOf.isEmpty()) return refusal
        val details = LinkedHashMap(refusal.details)

        @Suppress("UNCHECKED_CAST") // restoreDetails builds a list of maps
        val listed = refusal.details["blockers"] as List<Map<String, Any?>>
        details["blockers"] =
            listed.map { blocker ->
                val hit =
                    blockers.firstOrNull {
                        it.identity.family.wire == blocker["family"] && it.name == blocker["name"] &&
                            it.identity.version == blocker["version"]
                    }
                val via = hit?.let { viaOf[it.identity] }
                if (via == null) blocker else LinkedHashMap(blocker).apply { put("via", via) }
            }
        return DatapipelinesException(refusal.code, refusal.message.orEmpty(), details, refusal.cause)
    }
}
