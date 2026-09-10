package co.datapipelines.datasources

import co.datapipelines.typesystem.DatapipelinesException

/**
 * Phase B of the dp-lake round (089 §B, the 2026-09-07 lake-datasource design record §4): turns
 * a LAKE datasource's [LakeRegisteredTable] rows into the connect-time SQL that makes every
 * registered table a **view**, appended to the adapter's own `connectionInit` statements when a
 * pool is built (`DefaultDatasourceRegistry`'s pool factory — the seam).
 *
 * ## Why views, and why per connection
 *
 * A pooled connection on `jdbc:duckdb:` / `jdbc:duckdb::memory:` is its OWN in-memory DuckDB
 * instance (verified 2026-09-07 against duckdb_jdbc 1.5.5.1: objects created on one connection
 * are invisible to a second, while both are open). HikariCP runs `connectionInitSql` on every
 * new physical connection, so each connection builds its own catalog/schema/view set — which is
 * also why nothing here can leak across datasources. A template then reads
 * `SELECT … FROM nyc.mobility.hvfhv_zone_day`, or the bare `FROM hvfhv_zone_day` under the
 * search-path rule below, and the engine's partition pruning applies to the underlying
 * `read_parquet` scan unchanged.
 *
 * ## The namespace mapping (NamespaceShape.CATALOG_AND_SCHEMA)
 *
 * DuckDB's object space is exactly three levels — `catalog.schema.object`; its parser refuses a
 * deeper `CREATE SCHEMA` outright ("too many dots", verified as above). So:
 *
 * - **two segments** `["nyc", "mobility"]` — the head becomes a read-write in-memory CATALOG
 *   (`ATTACH IF NOT EXISTS ':memory:' AS "nyc"`, the one place a writable catalog is needed,
 *   since a view must live somewhere) and the rest a schema inside it.
 * - **one segment** `["nyc"]` — a schema in the connection's default catalog; no ATTACH.
 * - **three or more** — REFUSED with `datasource.validation.lake_namespace_invalid`: the engine
 *   cannot name the place, and silently flattening the segments would alias two different
 *   registry namespaces onto one schema. (The registry grammar admits 1–9 segments; deeper than
 *   two is registered-but-unmappable today, and failing the pool build loudly is the honest
 *   answer until the mapping has one.)
 *
 * ## The search-path rule (088's demo content reads BARE table names)
 *
 * When the datasource's registered tables span EXACTLY ONE distinct namespace, the last
 * statement is `SET search_path = '<head>.<rest>'` (the catalog.schema spelling — verified to
 * resolve bare table names on duckdb_jdbc 1.5.5.1; the bare-schema spelling resolves too, but
 * the catalog-qualified form cannot drift into a same-named schema elsewhere). With ZERO or
 * MULTIPLE distinct namespaces nothing is set: there is no defensible default, and fully
 * qualified names are required. This rule is the contract later docs (datasources.md §8C, the
 * SKILL) pick up.
 *
 * ## The location boundary
 *
 * Rows arrive validated from the registry — and are re-refused HERE anyway, because this is the
 * SQL-emission boundary: a location is interpolated into a single-quoted string literal, so the
 * refusal is total (no quote, backslash, whitespace or control character; `s3://` or `file://`
 * only), mirroring `LakeTableValidator.locationOf` (application module) predicate-for-predicate.
 * A row that fails it stops the pool build with `datasource.validation.lake_location_invalid`
 * rather than emitting bad SQL.
 *
 * `hive_partitioning = true` is emitted UNCONDITIONALLY for Parquet: it is harmless on a
 * non-partitioned layout (verified as above — a plain file reads normally, the clause only makes
 * the engine interpret `key=value` path segments when they exist), and making it conditional on
 * `partition_column` would let a registered-but-wrong partition column silently disable pruning
 * for a table that IS partitioned.
 */
object LakeViewStatements {
    /**
     * The statements for [tables], in execution order: the Iceberg extension loads when the
     * registry demands them, ATTACHes, schema creations, views, then the search-path rule.
     * Empty for zero tables — a tableless LAKE datasource gets no extra statements and no
     * ATTACH. Identifiers are quoted through [adapter]'s [DialectAdapter.quoteIdentifier].
     *
     * [duckdbExtensionDirectory] is the deployment's bundled extension directory (089 §D,
     * configuration.md §3.25) — the same value the pool factory forwards to
     * `DialectAdapters.forDialect`; it decides the Iceberg loads' shape (see
     * [icebergExtensionStatements]).
     */
    fun forTables(
        tables: List<LakeRegisteredTable>,
        adapter: DialectAdapter,
        duckdbExtensionDirectory: String? = null,
    ): List<String> {
        if (tables.isEmpty()) return emptyList()
        tables.forEach { requireMappable(it) }
        val namespaces = tables.map { it.namespace }.distinct()
        return buildList {
            addAll(icebergExtensionStatements(tables, duckdbExtensionDirectory))
            namespaces
                .filter { it.size >= CATALOG_SEGMENTS }
                .map { it.first() }
                .distinct()
                .forEach { head -> add("ATTACH IF NOT EXISTS ':memory:' AS ${adapter.quoteIdentifier(head)}") }
            namespaces.forEach { namespace ->
                add("CREATE SCHEMA IF NOT EXISTS ${namespace.joinToString(".") { adapter.quoteIdentifier(it) }}")
            }
            tables.forEach { table -> add(viewStatement(table, adapter)) }
            if (namespaces.size == 1) {
                add("SET search_path = '${namespaces.single().joinToString(".")}'")
            }
        }
    }

    /**
     * 109 §A — the per-table-isolation twin of [forTables]: the same statements, but split into
     * a STRICT prelude (extension loads, ATTACHes, schema creations — shared infrastructure whose
     * failure is a datasource fault and must still fail the connect), one [LakeViewPlan.View] per
     * registered table (applied one at a time by the pool's view applier, each failure recorded
     * on the table's registry row and skipped), and the search-path postlude.
     *
     * The emission-boundary refusals ([requireMappable], the location grammar, an unknown format)
     * are CAPTURED per table here rather than thrown: an unmappable namespace or an unutterable
     * location becomes that table's recorded error — its view is never emitted — instead of
     * failing the pool build and taking every healthy table down with it. Two consequences fall
     * out of that:
     *
     * - Only an emission-healthy table shapes the prelude. A 3+-segment namespace's
     *   `CREATE SCHEMA` would be invalid SQL the engine refuses; keeping it in the shared
     *   statements would re-create the all-tables-down failure inside the strict half.
     * - The search-path rule reads ALL registered namespaces, healthy or not — the documented
     *   rule is a fact about the REGISTRY ("the tables span exactly one namespace"), and a
     *   broken table's namespace disappearing from the derivation would silently change what
     *   bare names mean.
     *
     * The Iceberg extension decision likewise reads all tables: a broken Iceberg table stays
     * Iceberg, and the operator's fix needs the extension loaded to succeed.
     */
    fun planForTables(
        tables: List<LakeRegisteredTable>,
        adapter: DialectAdapter,
        duckdbExtensionDirectory: String? = null,
    ): LakeViewPlan {
        if (tables.isEmpty()) return LakeViewPlan(emptyList(), emptyList(), emptyList())
        val views =
            tables.map { table ->
                try {
                    requireMappable(table)
                    LakeViewPlan.View(table, sql = viewStatement(table, adapter), emissionError = null)
                } catch (e: DatapipelinesException) {
                    LakeViewPlan.View(table, sql = null, emissionError = e.message ?: e.code)
                }
            }
        val healthyNamespaces = views.filter { it.sql != null }.map { it.table.namespace }.distinct()
        val prelude =
            buildList {
                addAll(icebergExtensionStatements(tables, duckdbExtensionDirectory))
                healthyNamespaces
                    .filter { it.size >= CATALOG_SEGMENTS }
                    .map { it.first() }
                    .distinct()
                    .forEach { head -> add("ATTACH IF NOT EXISTS ':memory:' AS ${adapter.quoteIdentifier(head)}") }
                healthyNamespaces.forEach { namespace ->
                    add("CREATE SCHEMA IF NOT EXISTS ${namespace.joinToString(".") { adapter.quoteIdentifier(it) }}")
                }
            }
        val namespaces = tables.map { it.namespace }.distinct()
        val postlude =
            if (namespaces.size == 1) {
                listOf("SET search_path = '${namespaces.single().joinToString(".")}'")
            } else {
                emptyList()
            }
        return LakeViewPlan(prelude, views, postlude)
    }

    /**
     * One table's view: `read_parquet` for `format=parquet`, `iceberg_scan` for
     * `format=iceberg`, anything else refused — the registry's CHECK makes the else unreachable,
     * and a corrupt row fails loudly rather than generating SQL from a guess.
     */
    private fun viewStatement(
        table: LakeRegisteredTable,
        adapter: DialectAdapter,
    ): String {
        val path = (table.namespace + table.name).joinToString(".") { adapter.quoteIdentifier(it) }
        val scan =
            when (table.format) {
                "parquet" -> {
                    "read_parquet('${table.location}', hive_partitioning = true)"
                }

                "iceberg" -> {
                    "iceberg_scan('${table.location}')"
                }

                else -> {
                    throw DatapipelinesException(
                        DatasourceErrorCodes.LAKE_FORMAT_INVALID,
                        "Lake table '${qualified(table)}' has format '${table.format.take(MAX_ECHOED)}', " +
                            "which is neither parquet nor iceberg — refusing to generate its view.",
                        mapOf("table" to qualified(table), "format" to table.format.take(MAX_ECHOED)),
                    )
                }
            }
        return "CREATE OR REPLACE VIEW $path AS SELECT * FROM $scan"
    }

    /**
     * The emission boundary's two refusals: a namespace the engine cannot map (see the class
     * KDoc) and a location that fails the total injection refusal. Both name the offending row.
     */
    private fun requireMappable(table: LakeRegisteredTable) {
        if (table.namespace.isEmpty() || table.namespace.size > MAX_MAPPABLE_SEGMENTS) {
            throw DatapipelinesException(
                DatasourceErrorCodes.LAKE_NAMESPACE_INVALID,
                "Lake table '${qualified(table)}' has a ${table.namespace.size}-segment namespace; the engine's " +
                    "catalog.schema.object space maps exactly one or two — refusing to generate its view.",
                mapOf("table" to qualified(table)),
            )
        }
        val location = table.location
        val refused =
            location.isEmpty() ||
                (!location.startsWith("s3://") && !location.startsWith("file://")) ||
                // The mirror of LakeTableValidator.locationOf's total refusal (application
                // module): a value that would need escaping IS an attack string.
                location.any { ch -> ch in "'\"\\" || ch <= ' ' || ch == '\u007F' }
        if (refused) {
            throw DatapipelinesException(
                DatasourceErrorCodes.LAKE_LOCATION_INVALID,
                "Lake table '${qualified(table)}' has a location that fails the registry's grammar " +
                    "(s3:// or file://; no quotes, backslashes, whitespace or control characters) — " +
                    "refusing to interpolate it into a view.",
                mapOf("table" to qualified(table)),
            )
        }
    }

    /**
     * The 089 §F fix for the gap phase 4 found live: `catalog.kind: s3` makes the ADAPTER load
     * `httpfs` + `aws` and nothing else, so a registered `format=iceberg` table's
     * `iceberg_scan` view failed the pool build at connect. The adapter cannot see the
     * registry — its statement list is keyed on the DECLARED catalog kind — but THIS seam can,
     * so the rule lives here: ANY registered Iceberg table, under ANY catalog kind, prepends
     * the extension the views need.
     *
     * The shapes honor §D's LOAD-only-vs-INSTALL mode exactly as the adapter's
     * `extensionStatements` does: a bundled [extensionDirectory] means bare `LOAD`s against
     * files the image shipped (`avro` before `iceberg`, which auto-loads it — the adapter's
     * BUNDLED_ICEBERG_EXTENSIONS order, so a forgotten bundle fails naming avro), and no
     * `INSTALL` ever runs; no directory means the explicit `INSTALL`+`LOAD` pair developer
     * machines rely on (`INSTALL iceberg` pulls `avro` in as a dependency over the network).
     * The `SET extension_directory` is emitted even though the adapter may already have — the
     * adapter emits it only under a declared `catalog.kind`, and a local lake (no kind) with a
     * bundled directory is a real deployment.
     *
     * Parquet-only registries emit NOTHING here: an extension nothing will call is surface for
     * nothing (the adapter's own rule), and a no-egress deployment must not pay an `INSTALL`
     * for a table shape it does not have.
     */
    private fun icebergExtensionStatements(
        tables: List<LakeRegisteredTable>,
        extensionDirectory: String?,
    ): List<String> {
        if (tables.none { it.format == "iceberg" }) return emptyList()
        return when (extensionDirectory) {
            null -> {
                listOf("INSTALL iceberg", "LOAD iceberg")
            }

            else -> {
                // The directory reaches a SQL string literal HERE, before the pool-build adapter
                // construction that would `require` it — so the emission boundary refuses a value
                // that would need escaping itself (the adapter init's grammar, mirrored).
                if (!isSafeExtensionDirectory(extensionDirectory)) {
                    throw DatapipelinesException(
                        DatasourceErrorCodes.PROPERTIES_INVALID,
                        "datapipelines.duckdb.extension-directory must be an absolute path with no quotes, " +
                            "backslashes, whitespace or control characters; '$extensionDirectory' is not — " +
                            "refusing to interpolate it into the Iceberg extension statements.",
                        mapOf("key" to "datapipelines.duckdb.extension-directory"),
                    )
                }
                listOf(
                    "SET extension_directory = '$extensionDirectory'",
                    "LOAD avro",
                    "LOAD iceberg",
                )
            }
        }
    }

    /** The adapter init's grammar for the same operator value, mirrored at this boundary. */
    private fun isSafeExtensionDirectory(value: String): Boolean =
        value.startsWith("/") && value.none { ch -> ch in "'\"\\" || ch <= ' ' || ch == '\u007F' }

    private fun qualified(table: LakeRegisteredTable): String = (table.namespace + table.name).joinToString(".")

    /** Two segments = catalog + schema, the engine's full depth — see the class KDoc. */
    private const val MAX_MAPPABLE_SEGMENTS = 2

    /** A namespace this deep carries a catalog head that needs the ATTACH. */
    private const val CATALOG_SEGMENTS = 2

    private const val MAX_ECHOED = 32
}

/**
 * 109 §A — one LAKE datasource's connect-time view creation, split by failure ISOLATION
 * ([LakeViewStatements.planForTables] is the generator): [prelude] statements must succeed or
 * the connect fails as before (shared infrastructure, never one table's fault); each [views]
 * entry is applied independently — its failure is recorded on the table's registry row
 * (`last_error`, V20) and skipped, so one broken table never takes the healthy ones down;
 * [postlude] (the search-path rule) runs last regardless.
 */
data class LakeViewPlan(
    /** Extension loads, ATTACHes, schema creations — strict, in order. */
    val prelude: List<String>,
    /** One entry per registered table, in registry order. */
    val views: List<View>,
    /** The search-path `SET` when the registry spans exactly one namespace, else empty. */
    val postlude: List<String>,
) {
    init {
        views.forEach { view ->
            require((view.sql == null) != (view.emissionError == null)) {
                "a lake view carries exactly one of a statement or the emission refusal that replaced it"
            }
        }
    }

    /**
     * One registered table's view: [sql] to apply, or [emissionError] when the SQL-emission
     * boundary refused the row (an unmappable namespace, an unutterable location, an unknown
     * format) — recorded as the table's error without touching the engine.
     */
    data class View(
        val table: LakeRegisteredTable,
        val sql: String?,
        val emissionError: String?,
    ) {
        /** The dotted qualified name — the recording key and the error-detail `table`. */
        val qualifiedName: String get() = (table.namespace + table.name).joinToString(".")
    }
}
