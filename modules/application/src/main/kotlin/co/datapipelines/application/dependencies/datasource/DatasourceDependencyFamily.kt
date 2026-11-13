package co.datapipelines.application.dependencies.datasource

import co.datapipelines.application.dependencies.BoundedGraphRead
import co.datapipelines.application.dependencies.CeilingBound
import co.datapipelines.application.dependencies.IncomingArms
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.datasources.DatasourceGraphFacts
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Capabilities
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.IncomingQuery
import co.datapipelines.dependencies.IncomingResult
import co.datapipelines.dependencies.NodeFacts
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.PromotionCapability
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.ReleaseCapability
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.Selector
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.dependencies.VersionInfo
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * The datasource family of the dependency graph (#874 G3; record §4 row `datasource`, §5.1, §5.3, §12, §13): an
 * unversioned configuration, `NONE`/`NONE` capabilities, looked up by its deployment-global flat NAME (the key G2's
 * pipeline and parameter-set extractors emit, §6 "Pipeline → datasource … Entity"), and an honest leaf — no outgoing
 * business-object edge.
 */
object DatasourceDependencyFamily {
    val KEY: FamilyKey = FamilyKey.DATASOURCE

    /** Record §4: a datasource is neither released nor promoted. */
    val CAPABILITIES = Capabilities(ReleaseCapability.NONE, PromotionCapability.NONE)

    /** The node projection's first version; part of the internal fingerprint (§5.3). */
    const val EXTRACTOR_SCHEMA_VERSION = 1

    /**
     * The descriptor over [datasources] — a repository built over [ceiling]'s own template (`PostgresStatementCeiling.jdbc`
     * in production), so its one statement per batch is bounded (#786 R3); `ReleaseDependencyFamilies.ceilingOf` reads it back
     * for the wiring guard. Root selector `ENTITY` only: a version on a
     * datasource request is not a shape this family answers.
     */
    fun descriptor(
        datasources: DatasourceRepository,
        ceiling: StatementCeiling,
        incoming: IncomingArms? = null,
    ): FamilyDescriptor<DatasourceGraphFacts> =
        FamilyDescriptor(
            key = KEY,
            rootSelectors = setOf(SelectorKind.ENTITY),
            capabilities = CAPABILITIES,
            extractorSchemaVersion = EXTRACTOR_SCHEMA_VERSION,
            provider = DatasourceDependencyProvider(datasources, ceiling, incoming),
            safeSummary = FamilyDescriptor.NO_SAFE_SUMMARY,
        )

    /**
     * The node's internal fingerprint input (§5.3): ONE 64-bit value folding the row's V59 incarnation and generation —
     * the first eight bytes of SHA-256 over `<incarnation>:<generation>` — so a recreated datasource (new incarnation)
     * and a changed one (new generation) both move the fingerprint, and nothing of the connection enters it. `NodeFacts`
     * has one `generation` slot, read only by the fingerprint and never put on the wire.
     */
    fun fingerprintGeneration(
        incarnationId: UUID,
        generation: Long,
    ): Long =
        ByteBuffer
            .wrap(MessageDigest.getInstance("SHA-256").digest("$incarnationId:$generation".toByteArray(Charsets.UTF_8)))
            .long
}

/**
 * Resolves datasource ENTITY requests by name in ONE bounded statement per call ([BoundedGraphRead]) and answers every
 * request exactly once: a returned row is a node, anything else — absent, soft-deleted, or not visible to the context —
 * is [Outcome.Missing] (§12: missing and inaccessible are one outcome); a non-ENTITY selector is [Outcome.Unsupported].
 * It performs NO connectivity test (§8: a graph read does not test connectivity).
 *
 * Visibility is the datasource's native one, by the context's server-built [Authority]:
 * - [Authority.Member] — the grant-scoped read in the request's lookup workspace
 *   ([DatasourceRepository.findVisibleGraphFacts]); every request of one walk carries the walk's workspace, so a call is
 *   one statement (a batch mixing workspaces would be one statement per workspace — never per key);
 * - [Authority.System] — the server's own deployment-wide read of the requested names only
 *   ([DatasourceRepository.findLiveGraphFacts]; the authority the super-admin inventory reads with, §12 "Super admin:
 *   Native READ"). A consumer that must see only what a workspace sees passes the member's authority.
 *
 * The node shows `name`, `display_name`, `subtype` (the dialect) and `operational.readonly`; its identity is the NAME at
 * deployment scope; its fingerprint input is [DatasourceDependencyFamily.fingerprintGeneration] of the stored row's
 * incarnation and generation, taken from the [DatasourceGraphFacts] the read returned — never from a request or a default.
 */
internal class DatasourceDependencyProvider(
    private val datasources: DatasourceRepository,
    override val ceiling: StatementCeiling,
    /** #877 §B — the datasource target's two inverse arms ([IncomingArmCatalog.datasource]); none = Unsupported. */
    private val incoming: IncomingArms? = null,
) : DependencyProvider<DatasourceGraphFacts>,
    CeilingBound {
    init {
        require(incoming == null || incoming.target == DatasourceDependencyFamily.KEY) {
            "the datasource provider's inverse arms target ${incoming?.target}"
        }
    }

    override val incomingFrom: Set<FamilyKey> get() = incoming?.incomingFrom.orEmpty()

    override fun findIncoming(
        query: IncomingQuery,
        context: ResolveContext,
    ): IncomingResult = incoming?.find(query, context) ?: IncomingResult.Unsupported

    override fun resolveMany(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, Outcome<DatasourceGraphFacts>> {
        val answerable = requests.filter { it.family == DatasourceDependencyFamily.KEY && it.selector == Selector.Entity }
        val found: (ObjectRequest) -> DatasourceGraphFacts? =
            if (answerable.isEmpty()) {
                { null }
            } else {
                BoundedGraphRead.run(DatasourceDependencyFamily.KEY, requests, context, ceiling) { read(answerable, context.authority) }
            }
        return requests.associateWith { request ->
            if (request !in answerable) Outcome.Unsupported else found(request)?.let(::resolved) ?: Outcome.Missing
        }
    }

    /** Reads the rows [requests] name under [authority] and answers each request's row, or null when none came back. */
    private fun read(
        requests: List<ObjectRequest>,
        authority: Authority,
    ): (ObjectRequest) -> DatasourceGraphFacts? =
        when (authority) {
            Authority.System -> {
                val byName = datasources.findLiveGraphFacts(requests.map { it.address.lookupKey }.distinct()).associateBy { it.name }
                ({ request -> byName[request.address.lookupKey] })
            }

            is Authority.Member -> {
                val byWorkspace =
                    requests.groupBy { it.address.lookupScope.workspaceId }.mapValues { (workspaceId, inWorkspace) ->
                        datasources
                            .findVisibleGraphFacts(inWorkspace.map { it.address.lookupKey }.distinct(), workspaceId)
                            .associateBy { it.name }
                    }
                ({ request -> byWorkspace[request.address.lookupScope.workspaceId]?.get(request.address.lookupKey) })
            }
        }

    private fun resolved(facts: DatasourceGraphFacts): Outcome.Resolved<DatasourceGraphFacts> =
        Outcome.Resolved(
            identity =
                ResolvedIdentity(
                    family = DatasourceDependencyFamily.KEY,
                    ownerScope = OwnerScope.Deployment,
                    stableEntityId = facts.name,
                    artifactVersion = null,
                ),
            facts =
                NodeFacts(
                    name = facts.name,
                    versionInfo = VersionInfo.Unversioned,
                    lifecycle = null,
                    displayName = facts.displayName,
                    subtype = facts.dialect.wire,
                    operational = Operational.Datasource(readonly = facts.isReadonly),
                    generation = DatasourceDependencyFamily.fingerprintGeneration(facts.incarnationId, facts.generation),
                ),
            body = facts,
        )

    /** An honest leaf (§13): a datasource has no outgoing business-object edge. */
    override fun outgoing(resolved: Outcome.Resolved<DatasourceGraphFacts>): List<Reference> = emptyList()
}
