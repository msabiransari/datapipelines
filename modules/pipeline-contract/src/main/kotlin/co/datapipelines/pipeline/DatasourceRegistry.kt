package co.datapipelines.pipeline

import co.datapipelines.typesystem.Dialect

/**
 * The pipeline validator's read-only view of the environment's datasource registry
 * (pipeline-contract §12.5).
 *
 * The registry itself lives in the `datasources` module, which sits **beside** this one in
 * the §4.2 layering table — `pipeline-contract` may depend on `typesystem` and nothing else.
 * So the dependency is inverted: this module declares the two facts it needs and the
 * `datasources` module (or the wiring in `app`) supplies an implementation.
 *
 * A registry lookup is also what makes §11.4's env-specific scan tolerable: `pg-prod` is a
 * *name* and legal, and the scan skips any `source` / `output.datasource` value the registry
 * resolves ("the check applies to values that are not references into the datasource
 * registry").
 */
fun interface DatasourceRegistry {
    /**
     * The dialect of the datasource registered under [name], or **null when no such
     * datasource is registered in this environment**.
     *
     * One method, not `exists` + `dialectOf`: every registered datasource has a dialect
     * (datasources §3), so a second method would only add a way for the two answers to
     * disagree.
     *
     * The reserved literal `"tempdb"` is never passed here — it is not a datasource (§4.8),
     * and a registry that answers for it would let a pipeline shadow the staging database.
     */
    fun dialectOf(name: String): Dialect?

    companion object {
        /** A registry with nothing in it — every name is unknown. */
        val EMPTY = DatasourceRegistry { null }
    }
}
