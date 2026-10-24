package co.datapipelines.pipeline.release

import java.util.UUID

/**
 * One exact version in a release closure (record §1.1): the workspace, the family, the family's PIN KEY for the entity —
 * the string a parent's pin names it by (a template's id, a pipeline's name; names are never renamed, versioning §2) —
 * and the version number. Two versions of one entity are two identities; a diamond reaching one identity twice is ONE
 * node. Never a credential: it is server-side only, and the wire carries [ReleasePlan] `node_id`s instead.
 */
data class ReleaseNodeIdentity(
    val workspaceId: UUID,
    val family: ReleaseFamily,
    val entityId: String,
    val version: Int,
) : Comparable<ReleaseNodeIdentity> {
    /** The deterministic family/entity/version order the coordinator takes locks in (record §4.1 step 5). */
    override fun compareTo(other: ReleaseNodeIdentity): Int = ORDER.compare(this, other)

    private companion object {
        val ORDER: Comparator<ReleaseNodeIdentity> =
            compareBy<ReleaseNodeIdentity>({ it.workspaceId }, { it.family.ordinal }, { it.entityId }, { it.version })
    }
}
