package co.datapipelines.pipeline.release

/**
 * One family's read of the release graph (record §4.2): "the exact pins of this identity, and its status, body hash and
 * current pointer", from the metadata the caller may see. One implementation per family — `web` assembles them; S1
 * ships the pipeline and template sources, S2–S5 the rest.
 *
 * Reads only. An absent identity and one the caller may not see both answer null — the graph records `MISSING`, and the
 * two are byte-equal on every surface (record §13).
 */
fun interface DependencySource {
    fun resolve(identity: ReleaseNodeIdentity): ResolvedNode?
}

/**
 * A version that resolved: what the plan shows of it and what the tree hash covers (record §2.1). [pins] are its
 * outgoing pin occurrences — one per field path, so a version pinned twice by one parent is one edge target with two
 * paths. A DISCARDED version resolves (existing pins still name it) but its pins are never walked: it cannot be released.
 */
data class ResolvedNode(
    val identity: ReleaseNodeIdentity,
    /** The display path the caller authored (a folder name, versioning §2) — shown, never hashed. */
    val name: String,
    val status: ReleaseNodeStatus,
    val bodyHash: String,
    /** The entity's `current_version` pointer, null when none (record §2.1 hashes it). */
    val currentVersion: Int?,
    val pins: List<ReleasePin>,
) {
    init {
        require(status != ReleaseNodeStatus.MISSING) { "a resolved node is never MISSING; answer null instead" }
    }
}

/**
 * One pin occurrence: the exact target and the field path in the parent's body (`nodes[2].template`), a bounded
 * locator the author wrote. [targetName] is the name the pin spells — what a MISSING blocker shows, because the
 * caller authored it and it reveals nothing they did not write.
 */
data class ReleasePin(
    val target: ReleaseNodeIdentity,
    val targetName: String,
    val path: String,
)
