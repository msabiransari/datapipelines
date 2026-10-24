package co.datapipelines.pipeline.release

import java.util.UUID

/**
 * An in-memory release graph for the pure-unit tests: versions keyed by exact identity, every read counted, so a test
 * asserts memoisation (one read per identity) as well as the walk's answer.
 */
class ReleaseGraphFixture(
    val workspaceId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000005a7"),
) {
    private val versions = LinkedHashMap<ReleaseNodeIdentity, ResolvedNode>()
    val reads = HashMap<ReleaseNodeIdentity, Int>()

    fun id(
        key: String,
        version: Int = 1,
        family: ReleaseFamily = ReleaseFamily.TEMPLATE,
    ): ReleaseNodeIdentity = ReleaseNodeIdentity(workspaceId, family, key, version)

    /** Declares [identity] with [pins] (`target` to `path`); body hash and pointer default to stable functions of the key. */
    fun node(
        identity: ReleaseNodeIdentity,
        status: ReleaseNodeStatus = ReleaseNodeStatus.DRAFT,
        vararg pins: Pair<ReleaseNodeIdentity, String>,
        bodyHash: String = "hash-${identity.entityId}-${identity.version}",
        currentVersion: Int? = null,
        name: String = "acme/${identity.entityId}",
    ): ReleaseNodeIdentity {
        versions[identity] =
            ResolvedNode(
                identity = identity,
                name = name,
                status = status,
                bodyHash = bodyHash,
                currentVersion = currentVersion,
                pins = pins.map { (target, path) -> ReleasePin(target, "acme/${target.entityId}", path) },
            )
        return identity
    }

    /** A chain of [length] DRAFT templates below the declared [top]: `top → c1 → … → c_length`. */
    fun chain(
        top: ReleaseNodeIdentity,
        length: Int,
        prefix: String = "c",
    ): List<ReleaseNodeIdentity> {
        val links = (1..length).map { id("$prefix$it") }
        links.indices.reversed().forEach { i ->
            node(links[i], ReleaseNodeStatus.DRAFT, *listOfNotNull(links.getOrNull(i + 1)?.let { it to "template" }).toTypedArray())
        }
        if (links.isNotEmpty()) pin(top, links.first(), "template")
        return links
    }

    /** Adds one pin occurrence to the declared [parent]. */
    fun pin(
        parent: ReleaseNodeIdentity,
        target: ReleaseNodeIdentity,
        path: String,
    ) {
        val existing = versions.getValue(parent)
        versions[parent] = existing.copy(pins = existing.pins + ReleasePin(target, "acme/${target.entityId}", path))
    }

    fun source(): DependencySource =
        DependencySource { identity ->
            reads.merge(identity, 1, Int::plus)
            versions[identity]
        }

    fun graph(bounds: ReleaseBounds = ReleaseBounds()): ReleaseGraph =
        ReleaseGraph(ReleaseFamily.entries.associateWith { source() }, bounds)
}
