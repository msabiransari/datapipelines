package co.datapipelines.application.dependencies

import co.datapipelines.application.dependencies.prerequisites.DatasourceEdges
import co.datapipelines.application.dependencies.prerequisites.DatasourceReferenceReader
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionNode
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ResolvedNode

/**
 * A pipeline version as the pipeline arm resolves it (#873 §F.1): the release [node] G1's adapter has always produced —
 * the same identity, facts and pins, so `TreeHash` v1 and the release goldens are unchanged — and the version's
 * [datasources] references, which a [ResolvedNode] cannot carry (a `ReleasePin` names a `ReleaseNodeIdentity`, and a
 * datasource has no family version, record §8).
 */
data class PipelineReleaseNode(
    val node: ResolvedNode,
    val datasources: List<Reference>,
)

/**
 * The pipeline family's arm of G1's adapter (#873 §F.1), batched by #887: a `resolveMany` call reads the record, the
 * version's detail and, unless the version is DISCARDED, its body for every requested version in ONE bounded statement
 * per workspace ([PipelineRepository.findVersionNodesByName] through [BoundedGraphRead]) — the predicates of
 * [PipelineDependencySource]'s three per-node reads, which stay that source's (its parity oracle) — and builds the node
 * that source builds, with the body ALSO fed to [DatasourceEdges.ofPipeline], so the version's outgoing references are
 * its pins (template, child pipeline; the pin → reference mapping G1 pinned, [ReleaseDependencyFamilies.pinReferences])
 * followed by its datasource occurrences. The artifact references come first, so artifact discovery order — and every
 * `n<i>` the adapter assigns to an artifact node — is the order an artifact-only walk gives.
 *
 * A DISCARDED version's body is not read (it blocks the release whatever it names), so it has no outgoing reference,
 * exactly as it has no pin.
 *
 * The apply-time prerequisite check reads the datasource references of a batch of versions through
 * [PipelineReleaseProvider.datasourceReferences] instead (#873 R1): ONE bounded statement per batch
 * ([PipelineRepository.findLiveVersionBodiesByName]) over the DRAFT and RELEASED versions only.
 */
object PipelineReleaseFamily {
    fun descriptor(
        pipelines: PipelineRepository,
        ceiling: StatementCeiling,
        deserializer: PipelineDeserializer = PipelineDeserializer(),
    ): FamilyDescriptor<PipelineReleaseNode> =
        FamilyDescriptor(
            key = ReleaseDependencyFamilies.keyOf(ReleaseFamily.PIPELINE),
            rootSelectors = setOf(SelectorKind.EXACT_VERSION),
            capabilities = ReleaseDependencyFamilies.CAPABILITIES,
            extractorSchemaVersion = ReleaseDependencyFamilies.EXTRACTOR_SCHEMA_VERSION,
            provider = PipelineReleaseProvider(pipelines, deserializer, ceiling),
            safeSummary = FamilyDescriptor.NO_SAFE_SUMMARY,
        )
}

internal class PipelineReleaseProvider(
    private val pipelines: PipelineRepository,
    private val deserializer: PipelineDeserializer,
    override val ceiling: StatementCeiling,
) : DependencyProvider<PipelineReleaseNode>,
    CeilingBound,
    DatasourceReferenceReader {
    /**
     * #887 (record §7.1, §25.4 D12): one [Outcome] per request from ONE bounded statement per workspace in the call
     * ([PipelineRepository.findVersionNodesByName] through [BoundedGraphRead]: a batch above the contract's ceiling is
     * refused on entry, the statement runs under the context's live bound, a spent deadline refuses and a result read
     * after expiry is discarded). Each row is turned into the node [PipelineDependencySource.resolve] builds for the same
     * identity — the same facts and pins, so `TreeHash` v1 and the release goldens are unchanged — plus the version's
     * datasource references. An absent pipeline or version, or one of another workspace, is [Outcome.Missing]; a request
     * that is not an exact pipeline version is [Outcome.Unsupported].
     */
    override fun resolveMany(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, Outcome<PipelineReleaseNode>> {
        val identities = requests.associateWith(::pipelineIdentityOf)
        val found = HashMap<ReleaseNodeIdentity, PipelineReleaseNode>()
        identities.values
            .filterNotNull()
            .distinct()
            .groupBy { it.workspaceId }
            .forEach { (workspaceId, wanted) ->
                val keys = wanted.map { it.entityId to it.version }
                BoundedGraphRead
                    .run(KEY, requests, context, ceiling) { pipelines.findVersionNodesByName(workspaceId, keys) }
                    .forEach { row ->
                        val identity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.PIPELINE, row.name, row.version)
                        found[identity] = node(identity, row)
                    }
            }
        return requests.associateWith { request ->
            when (val identity = identities[request]) {
                null -> Outcome.Unsupported
                else -> found[identity]?.let { ReleaseDependencyFamilies.resolved(identity, it.node, body = it) } ?: Outcome.Missing
            }
        }
    }

    /**
     * The node [PipelineDependencySource.resolve] builds from its three reads, built from their one row: a DISCARDED
     * version carries no body, so no pin and no datasource reference.
     */
    private fun node(
        identity: ReleaseNodeIdentity,
        row: PipelineVersionNode,
    ): PipelineReleaseNode {
        val body = row.bodyJson?.let(deserializer::readOrThrow)
        val pins = body?.let { PipelineDependencySource.pinsOf(identity.workspaceId, it) }.orEmpty()
        val datasources = body?.let { DatasourceEdges.ofPipeline(identity.workspaceId, it) }.orEmpty()
        val status = PipelineDependencySource.statusOf(row.status)
        return PipelineReleaseNode(
            ResolvedNode(identity, row.name, status, row.bodyHash, row.currentVersion, pins, row.pipelineId),
            datasources,
        )
    }

    /**
     * #873 R1: the DATASOURCE references of the DRAFT and RELEASED pipeline versions [requests] name, from ONE bounded
     * statement per workspace in the call ([BoundedGraphRead]: a batch above the contract's ceiling is refused on entry,
     * the statement runs under the context's live bound, a spent deadline refuses). The same [DatasourceEdges.ofPipeline]
     * over the same stored body [outgoing] uses, so the check and the plan cannot disagree. A request that is not an
     * exact pipeline version, or names a version that is absent, DISCARDED or of another workspace, is absent.
     */
    override fun datasourceReferences(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, List<Reference>> {
        val identities = requests.associateWith(::pipelineIdentityOf)
        val found = HashMap<ReleaseNodeIdentity, List<Reference>>()
        identities.values
            .filterNotNull()
            .distinct()
            .groupBy { it.workspaceId }
            .forEach { (workspaceId, wanted) ->
                val keys = wanted.map { it.entityId to it.version }
                BoundedGraphRead
                    .run(KEY, requests, context, ceiling) { pipelines.findLiveVersionBodiesByName(workspaceId, keys) }
                    .forEach { row ->
                        val identity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.PIPELINE, row.name, row.version)
                        found[identity] = DatasourceEdges.ofPipeline(workspaceId, deserializer.readOrThrow(row.bodyJson))
                    }
            }
        return requests
            .mapNotNull { request -> identities[request]?.let(found::get)?.let { request to it } }
            .toMap()
    }

    override fun outgoing(resolved: Outcome.Resolved<PipelineReleaseNode>): List<Reference> =
        ReleaseDependencyFamilies.pinReferences(ReleaseFamily.PIPELINE, resolved.body.node.pins) + resolved.body.datasources

    private companion object {
        val KEY = ReleaseDependencyFamilies.keyOf(ReleaseFamily.PIPELINE)

        fun pipelineIdentityOf(request: ObjectRequest): ReleaseNodeIdentity? =
            ReleaseDependencyFamilies.identityOf(request)?.takeIf { it.family == ReleaseFamily.PIPELINE }
    }
}
