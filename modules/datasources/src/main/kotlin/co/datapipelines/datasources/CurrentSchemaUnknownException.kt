package co.datapipelines.datasources

/**
 * An unqualified **columns** read on a datasource whose connection reports **no current
 * schema** (datasources.md §7A) — typically a MySQL registration with a database-less URL
 * (`jdbc:mysql://host` with no `/db`, which JdbcUrlGuard permits): `getCatalog()` is blank,
 * so there is no default to scope the read to, and an unqualified read would merge the
 * columns of same-named tables (`db1.orders` with `db2.orders`) into one answer.
 *
 * Raised ONLY by [SchemaIntrospector]`s columns() when the caller passed no schema filter,
 * the dialect is not schemaless, and the connection's current schema (catalog or schema, per
 * routing) is unknown. The tables() listing is deliberately exempt: it cannot merge — every
 * row carries its own schema — so an unfiltered listing there spans schemas and works, and
 * the schemas() listing is how a caller recovers from this exception: list the schemas,
 * then pass one explicitly.
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
    cause: Throwable? = null,
) : RuntimeException(
        // Columns-scoped, like the KDoc above: the merge hazard is merging the COLUMNS of
        // same-named tables — agents and operators read the MESSAGE, not the KDoc (R5 F5).
        "Datasource '$datasourceName' reports no current schema; pass an explicit schema filter " +
            "(list them with the schemas operation) — an unqualified columns read would merge the columns " +
            "of same-named tables across schemas.",
        cause,
    )
