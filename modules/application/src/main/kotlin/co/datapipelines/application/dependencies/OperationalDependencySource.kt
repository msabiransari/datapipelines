package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.Operational
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ResolvedNode

/**
 * A release [DependencySource] whose family carries an operational condition (#902; dependency-graph record §5.2,
 * §11.1 — the API family's `api_endpoint { enabled }`). [read] answers the node AND its family's operational fact FROM THE
 * SAME READ as the node, never a second statement: the adapter's statement count per request does not change. The fact is
 * shown, never hashed (§5.3): it stays off the [ResolvedNode], its body hash, the tree fingerprint and release consent.
 *
 * `ReleaseSourceProvider` reads through [read] when its source is one of these and through [resolve] otherwise, so the
 * release planner and every other family keep calling [resolve] unchanged.
 */
interface OperationalDependencySource : DependencySource {
    /** The node and its operational fact for [identity], or null when it does not resolve (the graph's MISSING). */
    fun read(identity: ReleaseNodeIdentity): SourceRead?

    override fun resolve(identity: ReleaseNodeIdentity): ResolvedNode? = read(identity)?.node
}

/** One [OperationalDependencySource.read]: the release [node] and the [operational] fact read with it (null = none shown). */
data class SourceRead(
    val node: ResolvedNode,
    val operational: Operational?,
)
