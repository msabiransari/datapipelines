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

    /**
     * 109 §B — a DECLARED dialect property carries an empty, whitespace-only or null value.
     * Refused at register/update (and so at bootstrap, which saves through the same validator):
     * an empty string is never a configuration, and `catalog.ref: ""` used to be stored as
     * exactly that. The field names the key.
     */
    const val PROPERTY_EMPTY = "datasource.validation.property_empty"

    /** `query_timeout_seconds`, when present, is not an integer ≥ 1. */
    const val QUERY_TIMEOUT_INVALID = "datasource.validation.query_timeout_invalid"

    /**
     * Create with a name that is already **taken by any row**, live or soft-deleted (§9).
     *
     * Uniqueness is global, not "among live rows": `name` is the table's primary key and a soft
     * delete keeps the row (metadata-db §4.10), so a deleted datasource's name is never released.
     * That is deliberate — pipelines reference datasources by name, and reusing a retired name
     * would silently repoint every pipeline that still names it at a different database.
     *
     * The namespace also stays global ACROSS WORKSPACES (workspaces design §3): datasource
     * `name` is the PK, the GCM AAD anchor and the pool-registry key, so a collision between
     * two workspaces is this same code — by design, not an oversight.
     */
    const val DUPLICATE_NAME = "datasource.validation.duplicate_name"

    /**
     * A D8 refusal (workspaces design §8): a non-admin attempted the `global` flag (create,
     * flip, or mutating a global datasource), a non-admin attempted to flip `readonly` on a
     * GLOBAL datasource, or the caller bound the datasource to a workspace they are not in
     * — including every member CUD when `member-datasources-enabled` is off.
     */
    const val WORKSPACE_FORBIDDEN = "datasource.validation.workspace_forbidden"

    /** Delete blocked because one or more non-deleted pipelines reference this datasource (§6.2). */
    const val IN_USE = "datasource.in_use"

    /**
     * Datasource name unknown on a read/mutate/test path (pipeline-contract §13.8, added
     * 2026-08-11 with v1.3). Raised by the read/test surfaces (web/mcp) and by this module's
     * [SchemaIntrospector] — mirrored here so the §13.8 drift guard stays complete.
     */
    const val NOT_FOUND = "datasource.not_found"

    /** The JDBC driver class for `dialect` is not on the classpath (§10.3) — a packaging state. */
    const val DRIVER_NOT_LOADED = "datasource.driver_not_loaded"

    /**
     * A customer-database connection was requested while a **metadata** transaction was open on
     * the calling thread (056 §E.2, dag-executor §16).
     *
     * Refused rather than allowed, because both outcomes of allowing it are bad: the metadata
     * transaction — and its locks — would stay open for the duration of arbitrary customer SQL,
     * and a rollback of the metadata transaction could not undo the customer-side effect anyway.
     * Orchestration runs OUTSIDE the transaction; status writes are short, separate transactions.
     *
     * It is a server-fault code (500) on purpose: nothing a caller sent can produce it. Reaching
     * it means a service method annotated `@Transactional` grew a datasource lease, which is a
     * defect the guard turns into a loud, catalogued refusal instead of a silent lock-holder.
     */
    const val LEASE_IN_TRANSACTION = "datasource.lease_in_transaction"

    /** A lake-table operation was attempted on a datasource whose dialect is not `LAKE` (089 §A). */
    const val LAKE_DIALECT_REQUIRED = "datasource.validation.lake_dialect_required"

    /** A lake table's `namespace` fails the segment grammar (the pipeline/template §4.1 segment, minus `.`). */
    const val LAKE_NAMESPACE_INVALID = "datasource.validation.lake_namespace_invalid"

    /** A lake table's `name` fails the segment grammar. */
    const val LAKE_NAME_INVALID = "datasource.validation.lake_name_invalid"

    /** A lake table's `format` is not `parquet` or `iceberg` (metadata-db §4.15's closed set). */
    const val LAKE_FORMAT_INVALID = "datasource.validation.lake_format_invalid"

    /** A lake table's `location` fails the scheme allowlist or the total injection refusal. */
    const val LAKE_LOCATION_INVALID = "datasource.validation.lake_location_invalid"

    /** An import manifest URL outside the datasource's own endpoint/bucket (the SSRF boundary). */
    const val LAKE_MANIFEST_URL_FORBIDDEN = "datasource.validation.lake_manifest_url_forbidden"

    /** The (datasource, namespace, name) triple is already registered — 409. */
    const val LAKE_TABLE_DUPLICATE = "datasource.lake_table_duplicate"

    /** Unregister named a lake table that is not registered — 404. */
    const val LAKE_TABLE_NOT_FOUND = "datasource.lake_table_not_found"

    /**
     * A pipeline node referenced a registered lake table whose connect-time view creation is
     * recorded as failed (`lake_tables.last_error`, V20, 109 §A) — its view is skipped on every
     * connection, so the engine could only answer "table not found". 502; `details` carry
     * `table` and the recorded `last_error`. Raised by the executor (dag) at the CONNECT phase;
     * mirrored here so the §13.8 drift guard stays complete.
     */
    const val LAKE_TABLE_UNAVAILABLE = "datasource.lake.table_unavailable"

    /**
     * A lake-table registration/import named a table the pre-flight could not read (109 §A) —
     * refused BEFORE storing, with the bounded engine error as the message. 400: the fix is the
     * caller's (the location, the format, the file).
     */
    const val LAKE_TABLE_UNREADABLE = "datasource.validation.lake_table_unreadable"
}
