package co.datapipelines.application.dependencies.prerequisites

import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.ReleasePlanningLimits
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.pipeline.release.PrerequisiteCheck
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePrerequisite
import java.util.UUID

/**
 * The apply-time [PrerequisiteCheck] (#873 §F.3 c), run by the coordinator inside the guarded release transaction:
 *
 * 1. every DRAFT or RELEASED node of the re-walked closure whose family has a §6 datasource row (pipeline
 *    `DATASOURCE`, parameter set `PARAMETER_SOURCE_DATASOURCE` — read from [Relationship], never listed here) is read
 *    through its registry provider in ⌈n / `resolveBatchSize`⌉ calls per family, each ONE batch statement: a provider
 *    whose `resolveMany` is one statement per batch (the parameter set's) is read through it, and the pipeline arm —
 *    whose `resolveMany` keeps G1's three reads per version — through its [DatasourceReferenceReader] bulk read
 *    (#873 R1);
 * 2. the datasource names its outgoing references carry are the SAME extractor's ([DatasourceEdges]) the plan's walk used;
 * 3. each distinct name is resolved through the registry's datasource provider (G3's) in the caller's workspace as a
 *    MEMBER authority — never an unscoped lookup, never a connectivity test — so a name absent from the deployment and a
 *    name granted only to another workspace are one outcome, unavailable (record §12, §F.3 f).
 *
 * Every read runs in the caller's transaction (READ_COMMITTED, the guarded write transaction), fenced by
 * [speculativeReads], under a fresh planning [deadline] and its statement ceiling; an expired deadline is the release's
 * `metadata_unavailable` refusal, as the walk's is. A registry with no datasource provider answers every name unavailable
 * (fail closed, record §8).
 */
class RegistryPrerequisiteCheck(
    private val registry: FamilyRegistry,
    private val speculativeReads: ReadIsolation,
    private val deadline: ReleasePlanningDeadline,
    private val planning: ReleasePlanningLimits = ReleasePlanningLimits.DEFAULT,
) : PrerequisiteCheck {
    override fun check(
        closure: ReleaseClosure,
        actor: UUID,
    ): List<ReleasePrerequisite> {
        val context =
            ResolveContext(
                closure.root.workspaceId,
                MetadataSnapshot.READ_COMMITTED,
                Purpose.RELEASE_PLANNING,
                Authority.Member(actor),
                deadline.open().withStatementLimit(planning.statementTimeout),
                speculativeReads,
            )
        val prerequisites =
            try {
                val names = DatasourceEdges.namesOf(references(closure, context))
                if (names.isEmpty()) emptyList() else resolve(closure.root.workspaceId, names, context)
            } catch (expired: DeadlineExceeded) {
                throw ReleasePlanningWalk.metadataUnavailable(closure.root, expired)
            }
        // As the walk does (#786 R2): a read that ran past the cumulative deadline is not an answer.
        if (context.deadline.expired()) {
            throw ReleasePlanningWalk.metadataUnavailable(closure.root, DeadlineExceeded(FamilyKey.DATASOURCE))
        }
        return prerequisites
    }

    /** The datasource references of every bodied closure node of a family with a §6 datasource row, in closure order. */
    private fun references(
        closure: ReleaseClosure,
        context: ResolveContext,
    ): List<Reference> {
        val requests =
            closure.nodes
                .filter { it.status == ReleaseNodeStatus.DRAFT || it.status == ReleaseNodeStatus.RELEASED }
                .map { ReleaseDependencyFamilies.request(it.identity) }
                .filter { it.family in DATASOURCE_DEPENDENTS }
        val outgoing = requests.groupBy { it.family }.flatMap { (family, wanted) -> datasourceReferences(family, wanted, context) }.toMap()
        return requests.flatMap { outgoing[it].orEmpty() }
    }

    /**
     * [wanted]'s datasource references, per request, through [family]'s provider in batches — its bulk
     * [DatasourceReferenceReader] read when it has one (#873 R1), its `resolveMany` otherwise; none without a provider.
     */
    private fun datasourceReferences(
        family: FamilyKey,
        wanted: List<ObjectRequest>,
        context: ResolveContext,
    ): List<Pair<ObjectRequest, List<Reference>>> {
        val provider = providerOf(family) ?: return emptyList()
        val batches = wanted.chunked(planning.resolveBatchSize)
        if (provider is DatasourceReferenceReader) {
            return batches.flatMap { batch -> provider.datasourceReferences(batch, context).toList() }
        }
        return batches
            .flatMap { batch -> provider.resolveMany(batch, context).entries }
            .mapNotNull { (request, outcome) ->
                if (outcome is Outcome.Resolved) {
                    request to provider.outgoing(outcome).filter { it.request.family == FamilyKey.DATASOURCE }
                } else {
                    null
                }
            }
    }

    /** One prerequisite per name, in [names] order: available with its readonly flag, or unavailable with none. */
    private fun resolve(
        workspaceId: UUID,
        names: List<String>,
        context: ResolveContext,
    ): List<ReleasePrerequisite> {
        val requests = names.map { DatasourceEdges.request(workspaceId, it) }
        val provider = providerOf(FamilyKey.DATASOURCE)
        val outcomes =
            if (provider == null) {
                emptyMap()
            } else {
                requests
                    .chunked(planning.resolveBatchSize)
                    .flatMap { provider.resolveMany(it, context).entries }
                    .associate { it.toPair() }
            }
        return requests.map { request ->
            val readonly = ((outcomes[request] as? Outcome.Resolved<*>)?.facts?.operational as? Operational.Datasource)?.readonly
            ReleasePrerequisite(request.address.lookupKey, available = readonly != null, readonly = readonly)
        }
    }

    @Suppress("UNCHECKED_CAST") // a registry descriptor's provider reads its own body type; only its own outcomes go back
    private fun providerOf(family: FamilyKey): DependencyProvider<Any>? = registry[family]?.provider as DependencyProvider<Any>?

    private companion object {
        /** The families a §6 row leads from to a datasource: pipeline and parameter set today. */
        val DATASOURCE_DEPENDENTS: Set<FamilyKey> =
            Relationship.entries
                .filter { it.to == FamilyKey.DATASOURCE }
                .map { it.from }
                .toSet()
    }
}
