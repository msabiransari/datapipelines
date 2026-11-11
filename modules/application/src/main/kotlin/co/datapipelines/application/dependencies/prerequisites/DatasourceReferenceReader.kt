package co.datapipelines.application.dependencies.prerequisites

import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.ResolveContext

/**
 * A family provider that answers the apply-time [RegistryPrerequisiteCheck] from ONE bounded bulk read per call (#873 R1,
 * §F.3 c "one batch read per family"), for a family whose graph read is not already one statement per batch: the pipeline
 * arm, whose `resolveMany` keeps G1's three reads per version so the walk's facts and `TreeHash` v1 stay exactly G1's.
 *
 * [datasourceReferences] answers, for each of at most `resolveBatchSize` [requests], the version's outgoing DATASOURCE
 * references in body order — the SAME [DatasourceEdges] extraction the provider's `outgoing` returns for that version —
 * read in the [context]'s workspace, under its live statement bound and deadline. A request that is not a DRAFT or
 * RELEASED version of the family in that workspace is absent from the answer (it has no datasource reference to check,
 * as a MISSING or DISCARDED node has none on the walk's path).
 */
internal interface DatasourceReferenceReader {
    fun datasourceReferences(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, List<Reference>>
}
