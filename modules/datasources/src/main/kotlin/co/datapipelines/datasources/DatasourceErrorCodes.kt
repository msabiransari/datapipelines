package co.datapipelines.datasources

/**
 * The datasource slice of the system-wide error-code catalog
 * ([pipeline-contract §13.8](../../../../../../../docs/pipeline-contract.md), authored by
 * [datasources.md §9–10](../../../../../../../docs/datasources.md)).
 *
 * ## Why these are re-declared here rather than imported
 *
 * pipeline-contract.md is the single authority for concrete codes and `PipelineErrorCodes`
 * transcribes it — but `datasources` may depend on `typesystem` **only**
 * (module-structure.md §5.4 / §4.2), and `PipelineErrorCodes` lives in `pipeline-contract`,
 * a sibling layer. So this module keeps its own copy of the codes it raises and
 * [DatasourceErrorCodesSpecDriftTest] reads `docs/pipeline-contract.md §13.8` and fails if the
 * two ever disagree — the same drift guard `PipelineErrorCodesSpecDriftTest` runs, applied
 * across the module boundary the layering forces.
 *
 * `pipeline.execution.datasource_unreachable` is deliberately **absent**: it is a pipeline
 * pre-execution code the executor raises, not a save-time datasource rule, and it lives in
 * the `pipeline.execution.*` domain owned by `PipelineErrorCodes.Execution`.
 */
object DatasourceErrorCodes {
    /** `name` fails `[a-z0-9_-]+`, length 1–63 — also raised for a `PUT` that changes `name` (§11.1). */
    const val NAME_INVALID = "datasource.validation.name_invalid"

    /** `dialect` is not a value of the Type System §5 dialect set. */
    const val DIALECT_INVALID = "datasource.validation.dialect_invalid"

    /** URL matched the dialect scheme but failed the adapter's parse / injection guard (§6.1). */
    const val JDBC_URL_MALFORMED = "datasource.validation.jdbc_url_malformed"

    /** URL does not begin with `jdbc:{sub-protocol}:` for the declared dialect. */
    const val JDBC_URL_SCHEME_INVALID = "datasource.validation.jdbc_url_scheme_invalid"

    /** `password` is required on create. */
    const val PASSWORD_MISSING = "datasource.validation.password_missing"

    /** The test pool build (§5.4) rejected a `hikari`/`jdbc` property; `details` names the key. */
    const val PROPERTIES_INVALID = "datasource.validation.properties_invalid"

    /** `query_timeout_seconds`, when present, is not an integer ≥ 1. */
    const val QUERY_TIMEOUT_INVALID = "datasource.validation.query_timeout_invalid"

    /**
     * Create with a name that is already **taken by any row**, live or soft-deleted (§9).
     *
     * Uniqueness is global, not "among live rows": `name` is the table's primary key and a soft
     * delete keeps the row (metadata-db §4.10), so a deleted datasource's name is never released.
     * That is deliberate — pipelines reference datasources by name, and reusing a retired name
     * would silently repoint every pipeline that still names it at a different database.
     */
    const val DUPLICATE_NAME = "datasource.validation.duplicate_name"

    /** Delete blocked because one or more non-deleted pipelines reference this datasource (§6.2). */
    const val IN_USE = "datasource.in_use"

    /** The JDBC driver class for `dialect` is not on the classpath (§10.3) — a packaging state. */
    const val DRIVER_NOT_LOADED = "datasource.driver_not_loaded"
}
