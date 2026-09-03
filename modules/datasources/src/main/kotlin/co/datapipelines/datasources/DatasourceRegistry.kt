package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.Dialect
import java.util.UUID

/**
 * The Datasource Registry (datasources.md §6.1): the environment's authority for resolving a
 * datasource name to its connection details, pools, and health.
 *
 * ## Relationship to `pipeline-contract`'s `DatasourceRegistry`
 *
 * `pipeline-contract` declares its own single-method `fun interface DatasourceRegistry {
 * describe(name): DatasourceFacts? }` for the pipeline validator — one resolved value carrying
 * BOTH facts the validator asks for (the dialect and the readonly flag), not `exists` +
 * `dialectOf` + `readonlyOf`, because separate lookups are separate ways for the answers to
 * disagree. It inverts the dependency deliberately (it may depend on `typesystem` only).
 * `datasources` likewise may depend on `typesystem` only, so it **cannot implement that
 * interface directly** without adding a forbidden dependency. Instead this rich registry
 * exposes the live reads that adapter needs, and the wiring in `web` adapts — see
 * `DomainConfiguration.contractDatasourceRegistry`, which resolves the facts live through
 * [getVisibleLive] / [getLive] and builds the `DatasourceFacts` pair. That is precisely what
 * pipeline-contract's own KDoc anticipates ("the datasources module — or the wiring in app —
 * supplies an implementation").
 */
@Suppress("TooManyFunctions") // the registry IS the surface; +2 visibility reads (§5.3), +2 live reads (§5.7), +1 bootstrap resync (§8A.3)
interface DatasourceRegistry {
    /** Every live datasource, optionally narrowed to one [dialect]. Passwords never included. */
    fun list(dialect: Dialect? = null): List<Datasource>

    /**
     * Every datasource VISIBLE to [workspaceId] (workspaces design §5.3): its bound rows
     * plus all global ones. The workspace predicate is pushed to the repository's SQL, so
     * a caller's paging totals count exactly the visible set. Defaults to the unfiltered
     * [list] so in-memory test fakes need no workspace model — the production
     * implementation overrides it, and the isolation E2E proves the real behavior.
     */
    fun listVisible(
        dialect: Dialect? = null,
        workspaceId: UUID,
    ): List<Datasource> = list(dialect)

    /** The live datasource under [name], or null when not registered or soft-deleted. */
    fun get(name: String): Datasource?

    /**
     * The live datasource under [name] when VISIBLE to [workspaceId] (bound-to-it or
     * global), else null — by-name access to another workspace's datasource behaves as
     * not-found (design §5.3). Same default-and-override contract as [listVisible].
     */
    fun getVisible(
        name: String,
        workspaceId: UUID,
    ): Datasource? = get(name)

    /**
     * The live registry entry for [name] **as of this call**, bypassing the §6.3 metadata
     * cache. Save-time validation reads this and [getVisibleLive] (workspaces design §6 layer
     * 1, 044 F4): a datasource flagged readonly — or un-flagged, or soft-deleted — by manual
     * SQL or a restore never crosses the save boundary that invalidates the cache, so a
     * cached read would validate the next save against a row that no longer exists. Null when
     * no live datasource has this name.
     *
     * **Abstract, deliberately (020 F6):** the 020 shape defaulted this to the cached [get] —
     * the exact behaviour a live read exists to bypass — so any implementation or wrapper that
     * forgot the override silently re-opened the flip window with every test green. A missing
     * override is now a compile error, not a silent hole.
     */
    fun getLive(name: String): Datasource?

    /**
     * The live registry entry for [name] when VISIBLE to [workspaceId] (design §5.3), read
     * **as of this call** past the §6.3 cache — the live twin of [getVisible], and save-time
     * validation's principal read (044 F4): validation and the executor's live backstop must
     * answer the same question from the same row, or a row-level flag flip opens a window
     * where saves validate against a flag that no longer exists — in the un-flip direction a
     * WRONG 400 refuses a valid save, with no layer covering it.
     *
     * Defaults to [getVisible] (same default-and-override contract as [listVisible]): correct
     * for in-memory fakes, where cached and live are the same map; the production
     * implementation overrides it with the direct repository read.
     */
    fun getVisibleLive(
        name: String,
        workspaceId: UUID,
    ): Datasource? = getVisible(name, workspaceId)

    /**
     * Whether the live registry entry for [name] is flagged readonly, **as of this call**,
     * past the §6.3 metadata cache — the executor's readonly backstop reads this (workspaces
     * design §6 layer 2a, D10; 020 F7). Three-valued, and the three values are the whole
     * contract (044's fail-closed semantics, datasources.md §5.7):
     *
     * - `true` — the live row is readonly: refuse the write (`pipeline.node.datasource_readonly`).
     * - `false` — the live row is writable: proceed.
     * - `null` — **no live row** (soft-deleted by manual SQL — the D10 channel, or unknown):
     *   refuse the write (`pipeline.node.datasource_not_found`). Null is never "no signal".
     *
     * A caller that cannot afford even this read (metadata DB down) sees the read throw, and
     * the backstop refuses then too — naming the metadata DB, not the target datasource.
     *
     * **Abstract, deliberately (020 F6):** a default of `get(name)?.isReadonly` would be the
     * cached read — the exact bypass this method exists to close — and a wrapper forgetting
     * the override would fail silently. One line per fake buys a compile error instead.
     */
    fun isReadonlyLive(name: String): Boolean?

    /** Whether a live datasource is registered under [name]. */
    fun exists(name: String): Boolean

    /**
     * Creates or updates a datasource (create when no live row exists), running the full §9
     * rule set plus the test pool build (§5.4) first and encrypting the password before it is
     * written. Throws [DatasourceValidationException] on validation failure. Returns the stored
     * datasource with [Datasource.password] `null` — the credential is never echoed back.
     */
    fun save(
        datasource: Datasource,
        actor: UUID,
    ): Datasource

    /** Runs the §9 validation (including the test pool build) without persisting. */
    fun validate(datasource: Datasource): ValidationResult

    /**
     * **Rule 3** of bootstrap registration (datasources.md §8A.3, 061/T84): reconcile an
     * EXISTING row's stored credential with the one the bootstrap file now carries, without
     * turning "never update" into "always update".
     *
     * Called only when the file's credential differs from the stored one. The decision is
     * made by CONNECTING, never by preferring one source over the other — which is what keeps
     * rule 1 (an operator's edit survives every restart) intact while closing the desync that
     * broke the demo on 2026-09-02:
     *
     * - stored credential authenticates → [CredentialResync.STORED_WORKS]; the row is left
     *   byte-untouched, because the operator's value is the working one.
     * - stored fails and the FILE credential authenticates →
     *   [CredentialResync.RESYNCED]; **only `password_encrypted` is written** — not the name,
     *   the display name, the URL, the properties or the readonly flag — and the pool is
     *   evicted so the next lease uses it.
     * - neither authenticates → [CredentialResync.BOTH_FAILED]; the row is left alone. There
     *   is nothing to resync to, and overwriting a broken credential with another broken one
     *   would destroy the operator's value for nothing.
     *
     * Every branch that probed records the outcome on the row (§8.1B), so the datasources
     * screen shows what happened without an execution having to fail first.
     *
     * The probe is the SAME one [testConnection] runs — this method exists so the comparison
     * and the credential write happen where the encryptor lives, not so a second probe can be
     * invented. It never throws for a connection failure (§8.1: failure is data) and never
     * fails startup: the app must boot so an operator can fix the row.
     *
     * **Defaulted, not abstract** — the [evictPool] precedent, not the [getLive] one: a
     * registry that holds no ciphertext answers [CredentialResync.NOT_APPLICABLE], which
     * cannot re-open a hole because bootstrap registration runs against the production
     * registry alone. Read-only test fakes in other modules keep compiling untouched.
     *
     * @param name the bootstrap entry's datasource name.
     * @param fileCredential the plaintext credential the bootstrap file resolved.
     * @return which branch ran, for the caller's startup log.
     */
    fun resyncBootstrapCredential(
        name: String,
        fileCredential: String,
    ): CredentialResync = CredentialResync.NOT_APPLICABLE

    /** Soft-deletes [name]; fails with `datasource.in_use` when pipelines reference it (§6.2). */
    fun delete(name: String): DeleteResult

    /** The lazily-built, thread-safe connection pool for [datasource] (§5.2). */
    fun poolFor(datasource: Datasource): ConnectionPool

    /**
     * Drains the cached pool for [name] (§5.7's cross-instance invalidation, 050/R1) — the next
     * [poolFor] rebuilds it from the row. Returns true when a pool existed and was closed.
     *
     * [DefaultDatasourceRegistry] calls this synchronously on every save/delete (the writer's own
     * instance); OTHER instances reach it through the Redis invalidation channel their subscriber
     * wires ([PoolInvalidationPublisher] carries the fan-out — `datasources` itself never talks
     * to Redis, module-structure §4.2).
     *
     * **Default is a deliberate no-op, unlike [getLive]'s abstract-by-design:** the 020 F6 rule
     * makes a live read abstract because a silent default would RE-OPEN a closed hole; here a
     * fake that has no pool map has nothing to drain, and the production implementation is the
     * only pool-holder. Read-only test fakes (mcp-server's, dag's) keep compiling untouched.
     */
    fun evictPool(name: String): Boolean = false

    /**
     * Live connectivity probe (§8.1), or **null when no live datasource has this name** — the
     * caller maps that to HTTP 404, while §8.1's "HTTP 200 always" applies only to a datasource
     * that exists (§6.1, v1.4). Failure to *connect* is data, never an exception.
     */
    fun testConnection(name: String): TestResult?

    /**
     * The probe for an ALREADY-GATED [datasource] (§5.3 surfaces, 025 C3): the visibility
     * decision was made on this snapshot; acting on it — rather than re-resolving the name
     * independently — is what closes the gate-then-re-resolve TOCTOU. Defaults to the
     * name-based probe: the credential re-read is by primary key (datasource names are
     * never reused), so the row probed is the row the gate accepted.
     */
    fun testConnection(datasource: Datasource): TestResult? = testConnection(datasource.name)

    /**
     * The dialect registered under [name], or null when not registered — the exact contract of
     * `pipeline-contract`'s `DatasourceRegistry.dialectOf`. The reserved literal `"tempdb"` is
     * never passed here (it is not a datasource).
     */
    fun dialectOf(name: String): Dialect? = get(name)?.dialect
}

/**
 * One pipeline node's reference to a datasource — the delete guard's evidence (061/T79,
 * the datasource twin of `pipeline-contract`'s `TemplatePin`).
 *
 * Enough to act on, not just to count: which pipeline, which node inside it, and **which
 * pipeline version's body carries the reference**. That last field is the whole point — the
 * defect this type exists to close was a delete guard that saw only each pipeline's
 * `current_version` and therefore could not see a reference living in a released v1 that a
 * later v2 dropped.
 *
 * [versionStatus] is the pipeline version's lifecycle status as its wire string (`DRAFT` /
 * `RELEASED` / `DISCARDED`); it is a String rather than the `pipeline-contract` enum because
 * `datasources` may depend on `typesystem` alone (module-structure §5.4) — the same layering
 * that makes [DatasourceReferences] an inverted port in the first place.
 */
data class DatasourceReference(
    val pipelineName: String,
    val pipelineVersion: Int,
    val versionStatus: String,
    val nodeId: String,
)

/**
 * Supplies the pipeline references that block a datasource delete, for the §6.2 in-use guard.
 *
 * The reference lives in `pipeline-contract`'s domain, which `datasources` cannot depend on, so
 * the check is inverted: `app` wires a real implementation backed by the pipeline repository;
 * the default [NONE] treats every datasource as unreferenced (safe for tests and for a
 * deployment with no pipelines yet).
 *
 * **The scan behind this port is the ANY-VERSION one** (061/T79, mirroring 040's two-scan
 * split for templates): every version ever stored of every live pipeline, DRAFT, RELEASED and
 * DISCARDED alike. Pipeline versions are immutable and executable by explicit version, so a
 * reference from a historical version is a live reference — deleting the datasource out from
 * under it fails that version's next execution at connect. The working-version scan
 * (`PipelineRepository.findAllByDatasource`) stays where it belongs: filtering the pipelines
 * LISTING, which answers "what am I looking at now", a different question with a different
 * right answer.
 */
fun interface DatasourceReferences {
    /**
     * Every node of every stored version of every non-deleted pipeline that references
     * [datasourceName]; empty when none. One entry per referencing node, not per pipeline —
     * [DeleteResult.referencingPipelines] distinct-ifies for the message.
     */
    fun referencesTo(datasourceName: String): List<DatasourceReference>

    companion object {
        val NONE = DatasourceReferences { emptyList() }
    }
}

/**
 * Announces "the row for [datasourceName] changed; every instance's pool for it is stale" — the
 * publish half of §5.7's cross-instance pool invalidation (050/R1, ARCH-AUDIT M3).
 *
 * `datasources` is a Redis-free library (module-structure §4.2 — only `dag` and `web` talk to
 * Redis), so the registry calls this port beside its synchronous local eviction on every
 * save/delete, and the assembling layer supplies the Redis pub/sub implementation. The default
 * [NONE] keeps the module testable and matches a single-instance deployment's needs: with no
 * peers, the synchronous local eviction is the whole mechanism.
 *
 * The call happens AFTER the row write returns (committed) — publishing before the row is
 * durable races a subscriber into rebuilding from the OLD row, which is the defect the channel
 * exists to close. A publish failure must never fail the save that triggered it (the
 * implementation degrades to the pre-050 behaviour: peers rebuild on their next restart).
 */
fun interface PoolInvalidationPublisher {
    /** Fan out "pool for [datasourceName] is stale" to every OTHER instance. */
    fun publish(datasourceName: String)

    companion object {
        val NONE = PoolInvalidationPublisher { }
    }
}
