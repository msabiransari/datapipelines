package co.datapipelines.datasources

/**
 * A no-schema introspection read on a datasource whose connection reports **no current schema**
 * (datasources.md §7A) — typically a MySQL registration with a database-less URL
 * (`jdbc:mysql://host` with no `/db`, which JdbcUrlGuard permits): `getCatalog()` is blank, so
 * there is no default to scope the read to, and an unqualified read would span EVERY database
 * the server grants — merging `db1.orders` with `db2.orders` into one answer.
 *
 * Raised ONLY by [SchemaIntrospector]`s tables()/columns() when the caller passed no schema
 * filter, the dialect is not schemaless, and the connection's current schema (catalog or
 * schema, per routing) is unknown. The schemas() listing keeps its own unfiltered behavior —
 * it is how the caller recovers: list the schemas, then pass one explicitly.
 *
 * ## Why module-local, not a catalogued [co.datapipelines.typesystem.DatapipelinesException]
 *
 * Same layering as [DatasourceUnreachableException]: the closest catalogued invalid-argument
 * code (`pipeline.execution.parameter_required`, pipeline-contract §13.3 — "required parameter
 * missing") lives in `pipeline-contract`, a sibling layer `datasources` may not depend on, and
 * codes are reused, never re-invented. This type carries the failure across the module
 * boundary; each surface (the REST controller, the MCP tools) translates it to that code,
 * with a surface-appropriate message directing the caller to the schemas listing.
 *
 * The schemaless dialects never raise this: a dialect with no JDBC schema dimension at all
 * (SQLite) cannot have same-named tables in different schemas, so its unqualified read
 * cannot merge — the fallback is safe exactly and only there
 * (see [DialectAdapter.introspectionSchemaless]).
 */
class CurrentSchemaUnknownException(
    val datasourceName: String,
) : RuntimeException(
        "Datasource '$datasourceName' reports no current schema; pass an explicit schema filter " +
            "(list them with the schemas operation) — an unqualified read could merge same-named tables " +
            "across schemas.",
    )
