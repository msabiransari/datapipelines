package co.datapipelines.dependencies

import java.time.Duration
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

    /**
     * The dependent families whose references INTO this family [findIncoming] answers (#877, record §14) — empty by
     * default: a provider that declares none answers [IncomingResult.Unsupported] for every query.
     */
    val incomingFrom: Set<FamilyKey> get() = emptySet()

    /**
     * One page of the stored references from [IncomingQuery.dependents] INTO [IncomingQuery.target] (record §14; see
     * [IncomingQuery] for the one-definition rule, the scopes and the unlensed contract). One statement per page.
     * The default answers [IncomingResult.Unsupported] — never an empty page.
     */
    fun findIncoming(
        query: IncomingQuery,
        context: ResolveContext,
    ): IncomingResult = IncomingResult.Unsupported
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
    /**
     * Provenance (#873 R2, record §6 template → template "Preserve alias/path as provenance", §23.3 item 1): the alias
     * the dependent's author declared for this occurrence (a template import's `alias`), null where the family declares
     * none. Carried verbatim onto the [Edge]; never hashed, never rendered, never interpolated into SQL, HTML or a message.
     */
    val alias: String? = null,
)

/**
 * What a walk tells every provider (§7.1): the workspace, the metadata snapshot it reads in, its purpose, the
 * SERVER-BUILT authority and the cumulative [Deadline] — and the [ReadIsolation] the walk fences a read it may forgive
 * with, chosen by whoever opened the [snapshot]'s transaction (never defaulted: an unfenced read over a transaction is
 * the #868 R1 defect). Deliberately no client flag (never `ignorePermissions`): the core receives authorized outcomes,
 * not a backdoor repository.
 *
 * #786 R3 (§25.4): a provider that issues statements bounds each one by [statementBound], read when the statement starts.
 * The per-statement limit travels on the [deadline] (`Deadline.withStatementLimit`), so the context's fields are unchanged.
 */
data class ResolveContext(
    val workspaceId: UUID,
    val snapshot: MetadataSnapshot,
    val purpose: Purpose,
    val authority: Authority,
    val deadline: Deadline,
    val readIsolation: ReadIsolation,
) {
    /** The bound of a statement starting now: the [deadline]'s `statementBound()` (§25.4), null when unbounded. */
    fun statementBound(): Duration? = deadline.statementBound()
}

/**
 * How the walk fences a read whose failure it may forgive (#868 R1): a SPECULATIVE read — requests the walk does not
 * need yet, carried on another read's provider call ([GraphLimits.resolveBatchSize]) — runs inside [isolate], so that
 * when it fails, the state every provider of the walk reads through is as it was before the read, and the walk's next
 * read sees exactly what a walk that never attempted it would see.
 *
 * A provider over a database transaction needs a real fence: a failed statement aborts a PostgreSQL transaction, and
 * every later statement in it fails (SQLSTATE 25P02) — catching the exception does not clear that state, and a
 * speculative failure would then decide the walk's outcome. The release planning walk fences with a savepoint in the
 * current transaction (`application/dependencies`, `SavepointReadIsolation`); a savepoint rollback keeps the
 * transaction's snapshot, so the walk still reads ONE snapshot (§7.3). [NONE] is only for providers whose failure
 * leaves no shared state behind (in-memory sources, tests).
 *
 * The contract: [isolate] returns what [read] returned, or rethrows the SAME exception [read] threw once the state is
 * restored. Anything else it throws — the fence could not be set up, or the state could not be restored — is not
 * [read]'s failure, and the walk propagates it, never forgives it. A needed read is never isolated: its failure is the
 * walk's outcome.
 */
interface ReadIsolation {
    fun <T> isolate(read: () -> T): T

    companion object {
        /** No fence: for providers whose failures leave nothing behind. */
        val NONE: ReadIsolation =
            object : ReadIsolation {
                override fun <T> isolate(read: () -> T): T = read()

                override fun toString(): String = "ReadIsolation.NONE"
            }
    }
}

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
