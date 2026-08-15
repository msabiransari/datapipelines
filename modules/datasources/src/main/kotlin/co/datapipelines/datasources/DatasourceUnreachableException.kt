package co.datapipelines.datasources

/**
 * The datasource could not be reached for a live-metadata read (datasources.md §7A) — its pool
 * failed to build or a connection could not be leased/kept alive long enough to read
 * `DatabaseMetaData`.
 *
 * Raised ONLY by [SchemaIntrospector]'s lease boundary, which wraps **both** exception families
 * the registry's own probe KDoc names (see `DefaultDatasourceRegistry.probe`): a driver reports
 * connection failure as an `SQLException`, but pool construction failures arrive as
 * **RuntimeExceptions** — `HikariPool.PoolInitializationException` when the database is down at
 * first lease, a missing driver class, or a property a driver rejects at parse time. Catching
 * only `SQLException` at that boundary (the round-1 hardening miss) let the RuntimeException
 * family escape as a raw 500 / JSON-RPC -32603.
 *
 * ## Why module-local, not a catalogued [co.datapipelines.typesystem.DatapipelinesException]
 *
 * The catalogued code (`pipeline.execution.datasource_unreachable`, pipeline-contract §13.8)
 * lives in `pipeline-contract`, a sibling layer — `datasources` may depend on `typesystem` only
 * (module-structure.md §5.4), so this module cannot raise the code itself. This type carries
 * the failure across the module boundary; each surface (the REST controller, the MCP tools)
 * translates it to the §13.8 code. Two three-line catches are the accepted cost of the layering
 * (round-2 hardening decision) — a shared translation home would need a dependency this module
 * is forbidden to take.
 *
 * Not a bucket for internal bugs: a `RuntimeException` thrown by the metadata walk itself (not
 * the lease) propagates unchanged — "unreachable" means the caller's database, never a defect
 * in this module.
 */
class DatasourceUnreachableException(
    val datasourceName: String,
    cause: Throwable,
) : RuntimeException("Datasource '$datasourceName' could not be reached for schema introspection.", cause)
