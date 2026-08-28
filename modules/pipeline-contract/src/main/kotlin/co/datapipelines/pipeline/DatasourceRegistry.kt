package co.datapipelines.pipeline

import co.datapipelines.typesystem.Dialect

/**
 * The pipeline validator's read-only view of the environment's datasource registry
 * (pipeline-contract §12.5).
 *
 * The registry itself lives in the `datasources` module, which sits **beside** this one in
 * the §4.2 layering table — `pipeline-contract` may depend on `typesystem` and nothing else.
 * So the dependency is inverted: this module declares the facts it needs and the
 * `datasources` module (or the wiring in `app`) supplies an implementation.
 *
 * A registry lookup is also what makes §11.4's env-specific scan tolerable: `pg-prod` is a
 * *name* and legal, and the scan skips any `source` / `output.datasource` value the registry
 * resolves ("the check applies to values that are not references into the datasource
 * registry").
 */
fun interface DatasourceRegistry {
    /**
     * Everything the validator needs to know about the datasource registered under [name], or
     * **null when no such datasource is registered in this environment**.
     *
     * One method returning one resolved value, not `exists` + `dialectOf` + `readonlyOf`:
     * separate lookups are separate ways for the answers to disagree. The readonly fact rides
     * the same lookup as the dialect (workspaces design 2026-08-16 §6 — a write-shaped use of
     * a readonly datasource is `pipeline.validation.datasource_readonly`).
     *
     * The reserved literal `"tempdb"` is never passed here — it is not a datasource (§4.8),
     * and a registry that answers for it would let a pipeline shadow the staging database.
     */
    fun describe(name: String): DatasourceFacts?

    /** [describe] narrowed to the dialect — the existence question every `dialectOf` caller asked. */
    fun dialectOf(name: String): Dialect? = describe(name)?.dialect

    companion object {
        /** A registry with nothing in it — every name is unknown. */
        val EMPTY = DatasourceRegistry { null }
    }
}

/**
 * The two registry facts a pipeline save validates against: the datasource's [dialect]
 * (§12.6's template dialect check) and its [readonly] flag (workspaces design §6 — a
 * readonly datasource forbids the three write-shaped uses at save time).
 */
data class DatasourceFacts(
    val dialect: Dialect,
    val readonly: Boolean = false,
)
