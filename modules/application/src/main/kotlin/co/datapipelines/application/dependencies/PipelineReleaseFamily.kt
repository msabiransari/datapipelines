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
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
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
 * The pipeline family's arm of G1's adapter (#873 §F.1): [PipelineDependencySource]'s read — the record, the version's
 * detail and, unless the version is DISCARDED, its body, three statements in that order, each under the family's
 * [StatementCeiling] — with the body ALSO fed to [DatasourceEdges.ofPipeline], so the version's outgoing references are
 * its pins (template, child pipeline; the pin → reference mapping G1 pinned, [ReleaseDependencyFamilies.pinReferences])
 * followed by its datasource occurrences. The artifact references come first, so artifact discovery order — and every
 * `n<i>` the adapter assigns to an artifact node — is the order an artifact-only walk gives.
 *
 * A DISCARDED version's body is not read (it blocks the release whatever it names), so it has no outgoing reference,
 * exactly as it has no pin.
 *
 * The apply-time prerequisite check reads the datasource references of a batch of versions through
 * [PipelineReleaseProvider.datasourceReferences] instead (#873 R1): ONE bounded statement per batch
 * ([PipelineRepository.findLiveVersionBodiesByName]), never the three reads per version this arm's walk path keeps.
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
    override fun resolveMany(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, Outcome<PipelineReleaseNode>> = requests.associateWith { resolve(it, context) }

    private fun resolve(
        request: ObjectRequest,
        context: ResolveContext,
    ): Outcome<PipelineReleaseNode> {
        val identity = ReleaseDependencyFamilies.identityOf(request)
        if (identity == null || identity.family != ReleaseFamily.PIPELINE) return Outcome.Unsupported
        val read = ReleaseDependencyFamilies.bounded(ReleaseFamily.PIPELINE, ceiling, context) { read(identity) } ?: return Outcome.Missing
        return ReleaseDependencyFamilies.resolved(identity, read.node, body = read)
    }

    /** [PipelineDependencySource.resolve]'s three reads, keeping the body it parses for the datasource references. */
    private fun read(identity: ReleaseNodeIdentity): PipelineReleaseNode? {
        val record = pipelines.findByNameAnyStatus(identity.workspaceId, identity.entityId) ?: return null
        val detail = pipelines.findVersionDetail(identity.workspaceId, record.id, identity.version) ?: return null
        val status = PipelineDependencySource.statusOf(detail.status)
        val body =
            if (status == ReleaseNodeStatus.DISCARDED) {
                null
            } else {
                deserializer.readOrThrow(pipelines.findVersionBody(identity.workspaceId, record.id, identity.version) ?: return null)
            }
        val pins = body?.let { PipelineDependencySource.pinsOf(identity.workspaceId, it) }.orEmpty()
        val datasources = body?.let { DatasourceEdges.ofPipeline(identity.workspaceId, it) }.orEmpty()
        return PipelineReleaseNode(
            ResolvedNode(identity, record.name, status, detail.bodyHash, record.currentVersion, pins, record.id),
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
