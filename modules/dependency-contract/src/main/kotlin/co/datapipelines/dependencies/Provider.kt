package co.datapipelines.dependencies

import java.util.UUID

/**
 * One family's read of the dependency graph (record §7.1). [B] is the family's canonical loaded body/model — whatever
 * [outgoing] extracts references from, so extraction never issues a second mutable read.
 *
 * - [resolveMany] answers EXACTLY one [Outcome] per requested key: MISSING and UNSUPPORTED are values, never exceptions.
 *   An unexpected persistence failure propagates as an exception — it is never turned into an empty graph. The walk
 *   calls it with at most [GraphLimits.resolveBatchSize] requests of this family at a time.
 * - [outgoing] is deterministic over the already-loaded [Outcome.Resolved.body]: same body, same references, same order.
 */
interface DependencyProvider<B : Any> {
    fun resolveMany(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, Outcome<B>>

    fun outgoing(resolved: Outcome.Resolved<B>): List<Reference>
}

/** One request's answer from a [DependencyProvider] (§7.1). */
sealed interface Outcome<out B : Any> {
    /** The request resolved to [identity]; [facts] are what the node shows, [body] what [DependencyProvider.outgoing] reads. */
    data class Resolved<out B : Any>(
        val identity: ResolvedIdentity,
        val facts: NodeFacts,
        val body: B,
    ) : Outcome<B>

    /** Absent, or not visible to the context's authority — the two are indistinguishable here (§12). */
    data object Missing : Outcome<Nothing>

    /** The family cannot answer this request shape (e.g. a selector it does not support). */
    data object Unsupported : Outcome<Nothing>
}

/** The §5.1/§5.2 facts a resolved node shows. */
data class NodeFacts(
    val name: String?,
    val versionInfo: VersionInfo?,
    val lifecycle: Lifecycle?,
    val displayName: String? = null,
    val subtype: String? = null,
    val operational: Operational? = null,
    val currentVersion: Int? = null,
    val bodyHash: String? = null,
    val generation: Long? = null,
    val observations: List<Observation> = emptyList(),
)

/**
 * One outgoing reference occurrence (§6, §7.1): the typed [request], the §6 [relationship], the field locator in the
 * dependent's body, the constraints, and the name the reference spells ([spelledName] — what an unresolved target shows,
 * because the dependent's author wrote it). Never HTML, SQL, a URL or a client-provided policy.
 */
data class Reference(
    val request: ObjectRequest,
    val relationship: Relationship,
    val sourcePath: String?,
    val constraints: List<Constraint> = emptyList(),
    val spelledName: String? = null,
)

/**
 * What a walk tells every provider (§7.1): the workspace, the metadata snapshot it reads in, its purpose, the
 * SERVER-BUILT authority and the cumulative [Deadline]. Deliberately no client flag (never `ignorePermissions`): the
 * core receives authorized outcomes, not a backdoor repository.
 */
data class ResolveContext(
    val workspaceId: UUID,
    val snapshot: MetadataSnapshot,
    val purpose: Purpose,
    val authority: Authority,
    val deadline: Deadline,
)

/** The metadata consistency a walk reads in (§7.2 step 2, §7.3). */
enum class MetadataSnapshot {
    /** One explicitly read-only REPEATABLE READ transaction for every frontier — release planning. */
    SINGLE_REPEATABLE_READ,

    /** Observational reads; never promoted into a mutation plan without a fresh planning read. */
    READ_COMMITTED,
}

/** Why the walk runs (§7.1). */
enum class Purpose {
    INTERACTIVE_READ,
    RELEASE_PLANNING,
    LIFECYCLE_GUARD,
}

/** The server-built authority a walk reads under (§7.1) — derived from the authenticated request, never from its body. */
sealed interface Authority {
    /** An authenticated member acting through a session or a key. */
    data class Member(
        val userId: UUID,
    ) : Authority

    /** The server itself (a native lifecycle guard with no acting member). */
    data object System : Authority
}
