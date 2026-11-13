package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.Constraint
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.IncomingQuery
import co.datapipelines.dependencies.IncomingResult
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.dependencies.NodeFacts
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.dependencies.VersionInfo
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import java.util.UUID

/**
 * One stored version as a native artifact provider read it (#873 G2, record §7.1): the exact identity it answers, the
 * entity's surrogate id, the name it shows, its lifecycle, body hash and the entity's current pointer, and the family's
 * loaded [body] — what [ArtifactReleaseProvider.outgoing] extracts references from, so extraction issues no second read.
 */
data class StoredVersion<B : Any>(
    val identity: ReleaseNodeIdentity,
    val entityUuid: UUID,
    val name: String,
    val status: PipelineVersionStatus,
    val bodyHash: String,
    val currentVersion: Int?,
    val body: B,
    /** The family's operational fact (record §11.1; #873 R1: a visualization's `evidence_ready`), null for none. */
    val operational: Operational? = null,
)

/**
 * A provider whose reads run under a [StatementCeiling] — what the wiring guard reads ([ReleaseDependencyFamilies.ceilingOf]).
 */
internal interface CeilingBound {
    val ceiling: StatementCeiling
}

/**
 * The G2 native provider of one releasable artifact family (#873; record §7.1, review R1-8): [resolveMany] answers one
 * [Outcome] per request from ONE batch statement per workspace in the call — the walk calls it with at most
 * `resolveBatchSize` requests, so a frontier of n requests costs ⌈n / batch⌉ statements, never one per node — each
 * statement bounded by the context's live `statementBound()` through [ceiling] (#786 R3). An absent version, or one of
 * another workspace, is [Outcome.Missing]; a request that is not an exact version of THIS family is
 * [Outcome.Unsupported]; a persistence failure propagates. [outgoing] is deterministic over the loaded body, whatever its
 * status: the walk never expands a DISCARDED node (record §7.2 step 6), and a restore recheck reads a DISCARDED root's
 * references directly (brief §D.0).
 *
 * The identity and reference shapes are the release adapter's (`ReleaseDependencyFamilies.request`): family key = the
 * `ReleaseFamily` wire word, lookup key = the family's pin key (a template's id, every other artifact's name), selector
 * `EXACT_VERSION` — so `ReleaseClosureAdapter` maps every node back to a `ReleaseNodeIdentity`, and the source paths are
 * the release form the `tree_hash` covers (`imports[0]`, `transform.template`; Graph.kt: the JSON pointer is G4's).
 */
abstract class ArtifactReleaseProvider<B : Any>(
    val family: ReleaseFamily,
    final override val ceiling: StatementCeiling,
    /** #877 §B — the family's inverse arms ([IncomingArmCatalog]); none answers every `findIncoming` Unsupported. */
    private val incoming: IncomingArms? = null,
) : DependencyProvider<StoredVersion<B>>,
    CeilingBound {
    init {
        require(incoming == null || incoming.target == ReleaseDependencyFamilies.keyOf(family)) {
            "the ${family.wire} provider's inverse arms target ${incoming?.target}"
        }
    }

    final override val incomingFrom: Set<FamilyKey> get() = incoming?.incomingFrom.orEmpty()

    final override fun findIncoming(
        query: IncomingQuery,
        context: ResolveContext,
    ): IncomingResult = incoming?.find(query, context) ?: IncomingResult.Unsupported

    /** The stored versions [keys] (pin key, version) name in [workspaceId], in ONE statement; absent keys are absent. */
    protected abstract fun read(
        workspaceId: UUID,
        keys: List<Pair<String, Int>>,
    ): List<StoredVersion<B>>

    /** The outgoing references of [stored]'s body, in body order (record §6: one per occurrence). */
    abstract fun references(stored: StoredVersion<B>): List<Reference>

    final override fun resolveMany(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, Outcome<StoredVersion<B>>> {
        val identities = requests.associateWith { request -> ReleaseDependencyFamilies.identityOf(request)?.takeIf { it.family == family } }
        val found = HashMap<ReleaseNodeIdentity, StoredVersion<B>>()
        identities.values
            .filterNotNull()
            .distinct()
            .groupBy { it.workspaceId }
            .forEach { (workspaceId, wanted) ->
                val keys = wanted.map { it.entityId to it.version }
                ReleaseDependencyFamilies.bounded(family, ceiling, context) { read(workspaceId, keys) }.forEach { found[it.identity] = it }
            }
        return requests.associateWith { request ->
            val identity = identities[request]
            when {
                identity == null -> Outcome.Unsupported
                else -> found[identity]?.let(::resolved) ?: Outcome.Missing
            }
        }
    }

    final override fun outgoing(resolved: Outcome.Resolved<StoredVersion<B>>): List<Reference> = references(resolved.body)

    private fun resolved(stored: StoredVersion<B>): Outcome.Resolved<StoredVersion<B>> =
        Outcome.Resolved(
            identity =
                ResolvedIdentity(
                    family = ReleaseDependencyFamilies.keyOf(family),
                    ownerScope = OwnerScope.Workspace(stored.identity.workspaceId),
                    stableEntityId = stored.entityUuid.toString(),
                    artifactVersion = stored.identity.version,
                ),
            facts =
                NodeFacts(
                    name = stored.name,
                    versionInfo = VersionInfo.Artifact(stored.identity.version),
                    lifecycle = lifecycleOf(stored.status),
                    currentVersion = stored.currentVersion,
                    bodyHash = stored.bodyHash,
                    operational = stored.operational,
                ),
            body = stored,
        )

    /**
     * One reference from this family to [target] at [path] — the §6 row between the two families; [alias] is the
     * occurrence's declared alias, provenance only (#873 R2: a template import's), null where the family declares none.
     */
    protected fun reference(
        target: ReleaseNodeIdentity,
        path: String,
        constraints: List<Constraint> = emptyList(),
        alias: String? = null,
    ): Reference =
        Reference(
            request = ReleaseDependencyFamilies.request(target),
            relationship =
                checkNotNull(
                    Relationship.between(ReleaseDependencyFamilies.keyOf(family), ReleaseDependencyFamilies.keyOf(target.family)),
                ) {
                    "a ${family.wire} reference to a ${target.family.wire} is not a §6 row"
                },
            sourcePath = path,
            constraints = constraints,
            spelledName = target.entityId,
            alias = alias,
        )

    /** This family's descriptor: `EXACT_VERSION` roots, the artifact release pair, G1's extractor version. */
    fun descriptor(): FamilyDescriptor<StoredVersion<B>> =
        FamilyDescriptor(
            key = ReleaseDependencyFamilies.keyOf(family),
            rootSelectors = setOf(SelectorKind.EXACT_VERSION),
            capabilities = ReleaseDependencyFamilies.CAPABILITIES,
            extractorSchemaVersion = ReleaseDependencyFamilies.EXTRACTOR_SCHEMA_VERSION,
            provider = this,
            safeSummary = FamilyDescriptor.NO_SAFE_SUMMARY,
        )

    companion object {
        fun lifecycleOf(status: PipelineVersionStatus): Lifecycle =
            when (status) {
                PipelineVersionStatus.DRAFT -> Lifecycle.DRAFT
                PipelineVersionStatus.RELEASED -> Lifecycle.RELEASED
                PipelineVersionStatus.DISCARDED -> Lifecycle.DISCARDED
            }
    }
}
