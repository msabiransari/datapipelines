package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.Capabilities
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.dependencies.LookupScope
import co.datapipelines.dependencies.NodeFacts
import co.datapipelines.dependencies.ObjectAddress
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.PromotionCapability
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ReleaseCapability
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.Selector
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.dependencies.VersionInfo
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ResolvedNode
import java.time.Duration

/**
 * The release sources behind the neutral contract (#868 G1, record §8: "a release adapter maps the generic graph to the
 * existing `ReleasePlan` wire"): each [ReleaseFamily]'s [DependencySource] becomes a [FamilyDescriptor] with
 * `EXACT_VERSION` as its only root selector and the `ARTIFACT_RELEASE` / `VERSIONED_ARTIFACT` pair, and each
 * [ResolvedNode] pin an outgoing [Reference] carrying its §6 [Relationship] (`TEMPLATE`, `CHILD_PIPELINE`; `IMPORT` stays
 * unproduced while the template source returns no pins).
 *
 * Every source read runs under the family's [StatementCeiling] (#786 R3, record §25.4): production wires
 * [PostgresStatementCeiling] and builds each source's repositories over its template, so each metadata statement is
 * bounded by the context's `statementBound()` read immediately before that statement.
 */
object ReleaseDependencyFamilies {
    /** The release capability pair every adapted release family declares (record §4). */
    val CAPABILITIES = Capabilities(ReleaseCapability.ARTIFACT_RELEASE, PromotionCapability.VERSIONED_ARTIFACT)

    /**
     * The descriptor of one release family over its existing source, its reads bounded by [ceiling] (#786 R3). No default:
     * a production descriptor names its ceiling; [StatementCeiling.NONE] is for sources that issue no statement.
     */
    fun descriptor(
        family: ReleaseFamily,
        source: DependencySource,
        ceiling: StatementCeiling,
    ): FamilyDescriptor<ResolvedNode> =
        FamilyDescriptor(
            key = keyOf(family),
            rootSelectors = setOf(SelectorKind.EXACT_VERSION),
            capabilities = CAPABILITIES,
            extractorSchemaVersion = EXTRACTOR_SCHEMA_VERSION,
            provider = ReleaseSourceProvider(family, source, ceiling),
            safeSummary = FamilyDescriptor.NO_SAFE_SUMMARY,
        )

    /** A registry over [sources] (a directly built test's map); production assembles it from descriptor beans. */
    fun registry(
        sources: Map<ReleaseFamily, DependencySource>,
        ceiling: StatementCeiling = StatementCeiling.NONE,
    ): FamilyRegistry = FamilyRegistry.production(sources.map { (family, source) -> descriptor(family, source, ceiling) })

    /** The ceiling a release descriptor's reads run under; null for a descriptor this object did not build (the wiring guard's read). */
    fun ceilingOf(descriptor: FamilyDescriptor<*>): StatementCeiling? = (descriptor.provider as? ReleaseSourceProvider)?.ceiling

    fun keyOf(family: ReleaseFamily): FamilyKey = FamilyKey(family.wire)

    /** The release family of a registry key; null for a family release cannot flip (a registered non-release family). */
    fun familyOf(key: FamilyKey): ReleaseFamily? = ReleaseFamily.entries.firstOrNull { it.wire == key.wire }

    /** The exact request for a release identity: the family's pin key in the workspace, at its exact version. */
    fun request(identity: ReleaseNodeIdentity): ObjectRequest =
        ObjectRequest(
            ObjectAddress(keyOf(identity.family), LookupScope(identity.workspaceId), identity.entityId),
            Selector.ExactVersion(identity.version),
        )

    /** The release identity a request names; null for a request that is not an exact version of a release family. */
    fun identityOf(request: ObjectRequest): ReleaseNodeIdentity? {
        val family = familyOf(request.family) ?: return null
        val version = (request.selector as? Selector.ExactVersion)?.number ?: return null
        return ReleaseNodeIdentity(request.address.lookupScope.workspaceId, family, request.address.lookupKey, version)
    }

    /** The release pins are hashed by `TreeHash`, not by an extractor; version 1 is the adapter's first projection. */
    const val EXTRACTOR_SCHEMA_VERSION = 1
}

/**
 * One release family's [DependencySource] as a [DependencyProvider]: one source read per request (the source has no
 * batch read; G2's providers batch), an absent or invisible version answered [Outcome.Missing], and a source exception
 * propagated, never turned into a missing node.
 *
 * #786 R3 (record §25.4): each source read runs under [ceiling] with the context's LIVE `statementBound()` — the
 * supplier, not a value — so the ceiling bounds every statement the read issues by `min(statement limit, remaining
 * deadline)` measured when THAT statement starts (landing review r4: a value read once kept the first statement's budget
 * for the later ones). A bound that is already zero refuses before the read ([DeadlineExceeded]: a zero timeout would
 * mean "none"); a statement cancelled at its bound, or not issued because its bound was spent, is the same refusal, the
 * ceiling's exception the cause.
 */
internal class ReleaseSourceProvider(
    private val family: ReleaseFamily,
    private val source: DependencySource,
    internal val ceiling: StatementCeiling,
) : DependencyProvider<ResolvedNode> {
    override fun resolveMany(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, Outcome<ResolvedNode>> = requests.associateWith { resolve(it, context) }

    private fun resolve(
        request: ObjectRequest,
        context: ResolveContext,
    ): Outcome<ResolvedNode> {
        val identity = ReleaseDependencyFamilies.identityOf(request)
        if (identity == null || identity.family != family) return Outcome.Unsupported
        val node = bounded(context) { source.resolve(identity) } ?: return Outcome.Missing
        check(node.identity == identity) { "the ${family.wire} source answered ${node.identity} for $identity" }
        return Outcome.Resolved(
            identity =
                ResolvedIdentity(
                    family = ReleaseDependencyFamilies.keyOf(family),
                    ownerScope = OwnerScope.Workspace(identity.workspaceId),
                    stableEntityId = node.entityUuid.toString(),
                    artifactVersion = identity.version,
                ),
            facts =
                NodeFacts(
                    name = node.name,
                    versionInfo = VersionInfo.Artifact(identity.version),
                    lifecycle = lifecycleOf(node.status),
                    currentVersion = node.currentVersion,
                    bodyHash = node.bodyHash,
                ),
            body = node,
        )
    }

    private fun <T> bounded(
        context: ResolveContext,
        read: () -> T,
    ): T {
        val bound = context.statementBound() ?: return read()
        if (bound <= Duration.ZERO) throw DeadlineExceeded(ReleaseDependencyFamilies.keyOf(family))
        return try {
            ceiling.within(context::statementBound, read)
        } catch (reached: StatementBoundReached) {
            throw DeadlineExceeded(ReleaseDependencyFamilies.keyOf(family)).apply { initCause(reached) }
        }
    }

    override fun outgoing(resolved: Outcome.Resolved<ResolvedNode>): List<Reference> =
        resolved.body.pins.map { pin ->
            Reference(
                request = ReleaseDependencyFamilies.request(pin.target),
                relationship = relationship(pin.target.family),
                sourcePath = pin.path,
                spelledName = pin.targetName,
            )
        }

    private fun relationship(target: ReleaseFamily): Relationship =
        checkNotNull(Relationship.between(ReleaseDependencyFamilies.keyOf(family), ReleaseDependencyFamilies.keyOf(target))) {
            "a ${family.wire} pin to a ${target.wire} is not a §6 row"
        }

    private fun lifecycleOf(status: ReleaseNodeStatus): Lifecycle =
        when (status) {
            ReleaseNodeStatus.DRAFT -> Lifecycle.DRAFT
            ReleaseNodeStatus.RELEASED -> Lifecycle.RELEASED
            ReleaseNodeStatus.DISCARDED -> Lifecycle.DISCARDED
            ReleaseNodeStatus.MISSING -> error("a resolved node is never MISSING")
        }
}
