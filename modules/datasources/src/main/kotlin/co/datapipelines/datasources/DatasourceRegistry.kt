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
@Suppress("TooManyFunctions") // the registry IS the surface; +2 visibility reads (§5.3), +2 live reads (§5.7)
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

    /** Soft-deletes [name]; fails with `datasource.in_use` when pipelines reference it (§6.2). */
    fun delete(name: String): DeleteResult

    /** The lazily-built, thread-safe connection pool for [datasource] (§5.2). */
    fun poolFor(datasource: Datasource): ConnectionPool

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
 * Supplies the pipelines that reference a datasource, for the §6.2 in-use delete guard.
 *
 * The reference lives in `pipeline-contract`'s domain, which `datasources` cannot depend on, so
 * the check is inverted: `app` wires a real implementation backed by the pipeline repository;
 * the default [NONE] treats every datasource as unreferenced (safe for tests and for a
 * deployment with no pipelines yet).
 */
fun interface DatasourceReferences {
    /** The ids/names of non-deleted pipelines referencing [datasourceName]; empty when none. */
    fun pipelinesReferencing(datasourceName: String): List<String>

    companion object {
        val NONE = DatasourceReferences { emptyList() }
    }
}
