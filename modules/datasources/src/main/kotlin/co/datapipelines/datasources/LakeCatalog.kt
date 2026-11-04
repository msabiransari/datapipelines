package co.datapipelines.datasources

/**
 * The dp-lake catalog, as the `datasources` module sees it (metadata-db §4.15, the 2026-09-07
 * lake-datasource design record §2, round 089 §B/§C): the registered tables of ONE LAKE-dialect
 * datasource, read behind a port because the registry's rows live in the metadata DB — a store
 * this module cannot reach (module-structure §4.2: `datasources` depends on `typesystem` alone).
 *
 * ONE port serves two readers, deliberately:
 *
 * - **Phase B** (`DefaultDatasourceRegistry`'s pool factory): the rows become the per-table
 *   `CREATE VIEW` statements [LakeViewStatements] generates into a new pool's
 *   `connectionInitSql`.
 * - **Phase C** ([SchemaIntrospector]'s LAKE branches): the rows ARE the schema/tables listing —
 *   a LAKE datasource's tables are exactly its registry rows, so JDBC metadata has nothing to
 *   add.
 *
 * The row carries exactly what both readers need and no more: the namespace segments, the table
 * name, the format as its wire string (`parquet` / `iceberg` — the value the view generator maps
 * to `read_parquet(...)` versus `iceberg_scan(...)`), and the location. Validation is NOT this
 * type's job: rows arrive validated from the registry, and [LakeViewStatements] re-refuses a bad
 * location at the SQL-emission boundary regardless (defense in depth, never trust a stored row).
 */
data class LakeRegisteredTable(
    /** 087's namespace segments, outermost first — `["nyc", "mobility"]`. One to nine segments. */
    val namespace: List<String>,
    val name: String,
    /** The metadata-db §4.15 wire value: `parquet` or `iceberg`. */
    val format: String,
    /** `s3://bucket/prefix[/glob]` (Iceberg: the current metadata FILE, not the table root — datasources.md §8C.7) or a `file://` path. */
    val location: String,
    /**
     * The hive-style partition column (metadata-db §4.15), null when unpartitioned. Carried
     * across the port for §7C: a lake table has no indexes — the partition column IS the access
     * structure the engine prunes on, and [SchemaIntrospector.tableStats] reports it as the
     * partition pseudo-index.
     */
    val partitionColumn: String? = null,
    /**
     * 109 §A (V22) — the connect-time view creation's last recorded failure, NULL when the
     * view last built cleanly. The pool factory's per-table view application reads this to
     * record TRANSITIONS only (an unchanged outcome writes nothing), and the executor reads
     * it to fail a node that references a broken table with `datasource.lake.table_unavailable`
     * instead of the engine's raw "table not found".
     */
    val lastError: String? = null,
    /**
     * #818 — true for a MANAGED row (`lake_tables.origin = 'managed'`). The pool factory plans such a
     * row from the catalog ([ManagedLakeCatalog]), never from [location]; an external row keeps
     * today's path even on the managed datasource.
     */
    val managed: Boolean = false,
    /** #818 — a managed table's deprecation notice, shown by introspection; null otherwise. */
    val notice: String? = null,
    /** #818 — why a managed table has no view ([ManagedLakeTable.unavailableReason]); null when it has one. */
    val unavailableReason: String? = null,
)

/**
 * The port the assembling layer implements over the metadata DB's `lake_tables` repository (the
 * [DatasourceReferences] precedent: `datasources` declares the question, `web` wires the answer).
 * The default [NONE] answers "no registered tables", which is also the truthful answer for every
 * non-LAKE datasource — both callers branch on the dialect before consulting it.
 */
fun interface LakeTableCatalog {
    /** The datasource's registered tables, in tree order (namespace, then name). */
    fun registeredTables(datasourceName: String): List<LakeRegisteredTable>

    companion object {
        /** No registry behind this module — tests, and any deployment without the wiring. */
        val NONE = LakeTableCatalog { emptyList() }
    }
}

/**
 * The write half of 109 §A's view-outcome recording — a SEPARATE port from [LakeTableCatalog]
 * on purpose: the catalog is a `fun interface` with lambda implementations in callers and test
 * fixtures this module cannot all see, so growing it would break them; a new seam keeps every
 * existing implementation source-compatible. The assembling layer implements it over
 * `LakeTableRepository.recordViewOutcome`.
 *
 * Callers (the pool factory's per-connection view application) invoke [record] on TRANSITIONS
 * only — error text changed, or success after an error — never per connection, so the hot
 * path stays write-free. [error] is the bounded engine/emission message; `null` is the healthy
 * outcome and CLEARS the row's recorded error.
 */
fun interface LakeViewOutcomeRecorder {
    /** Records [error] (null = healthy) as the outcome for the (datasource, namespace, table) triple. */
    fun record(
        datasourceName: String,
        namespace: List<String>,
        table: String,
        error: String?,
    )

    companion object {
        /** Records nothing — tests, and any deployment without the wiring. */
        val NONE = LakeViewOutcomeRecorder { _, _, _, _ -> }
    }
}

/**
 * 109 §A — one registered lake table whose connect-time view creation last FAILED, as the
 * executor's pre-execution check needs it: a node whose SQL references this table fails with
 * `datasource.lake.table_unavailable` (details `table` + `last_error`) instead of the engine's
 * raw "table not found".
 */
data class LakeBrokenTable(
    val namespace: List<String>,
    val name: String,
    /** The recorded engine/emission error — non-null by construction of the read. */
    val lastError: String,
) {
    /** The dotted qualified name — `nyc.mobility.hvfhv_zone_day`. */
    val qualifiedName: String get() = (namespace + name).joinToString(".")
}

/**
 * #818 (dp-lake P5, design record §10 "One snapshot per generation") — one MANAGED table as the
 * metadata catalog sees it: its `lake_tables` registry row (`origin = 'managed'`) joined to the
 * Iceberg catalog's `iceberg_tables` row and its lifecycle rows. The managed datasource's views are
 * planned from [metadataLocation], the row the catalog's compare-and-swap writes, and NEVER from
 * the registry row's `location` column, a display projection that can lag a commit.
 */
data class ManagedLakeTable(
    /** `[<lake name>, <namespace>]`, exactly the registry row's spelling (lake.md §5 "Registry namespace"). */
    val namespace: List<String>,
    val name: String,
    /** The registry row's format; always `iceberg` for a managed row (798's upsert writes nothing else). */
    val format: String,
    val partitionColumn: String? = null,
    /** The registry row's recorded view failure (V22), carried for the view-outcome recorder's transitions. */
    val lastError: String? = null,
    /** `iceberg_tables.metadata_location`, or null when the catalog has no row: the table is unavailable. */
    val metadataLocation: String?,
    /**
     * The effective lifecycle status (`active`, `deprecated`, `archived`, `purged`): the stricter of the table's and its
     * namespace's; null = no lifecycle row.
     */
    val status: String?,
    /** The deprecation notice introspection shows, or null when neither the table nor its namespace is deprecated. */
    val deprecationMessage: String? = null,
    /** True when the server runs with `datapipelines.lake.enabled=false`: every managed table is unavailable (record §6). */
    val lakeDisabled: Boolean = false,
) {
    /** The dotted qualified name — `lake.sales.orders`. */
    val qualifiedName: String get() = (namespace + name).joinToString(".")

    /**
     * Why the planner omits this table's view, or null when it gets one. A deprecated table keeps its
     * view (record §10 "Unavailable tables"); an archived or purged one, a table the catalog does not
     * know, one with no lifecycle row and every table of a disabled lake do not.
     */
    val unavailableReason: String?
        get() =
            when {
                lakeDisabled -> REASON_LAKE_DISABLED
                metadataLocation == null -> REASON_NO_CATALOG_ROW
                status == null -> REASON_NO_LIFECYCLE_ROW
                status in UNAVAILABLE_STATUSES -> "status $status"
                else -> null
            }

    /**
     * What the freshness tick compares (RULING 4 item 2): the facts the generation's views were
     * built from. [lastError] is NOT part of it, because the generation's own view failure writes
     * that column and must not make the next tick see a stale generation; [deprecationMessage] is
     * not either, because introspection reads it from the catalog directly, never from a generation.
     */
    val generationKey: GenerationKey
        get() = GenerationKey(qualifiedName, format, metadataLocation, unavailableReason)

    /** One table's contribution to [generationKey]: what a view reads and whether it exists. */
    data class GenerationKey(
        val qualifiedName: String,
        val format: String,
        val metadataLocation: String?,
        val unavailableReason: String?,
    )

    companion object {
        const val REASON_LAKE_DISABLED = "lake.disabled"
        const val REASON_NO_CATALOG_ROW = "no catalog row"
        const val REASON_NO_LIFECYCLE_ROW = "no lifecycle row"
        private val UNAVAILABLE_STATUSES = setOf("archived", "purged")
    }
}

/**
 * #818 — the managed-read port: the managed datasource's tables, read from the CATALOG (record §10).
 * A separate port from [LakeTableCatalog], for the reason [LakeViewOutcomeRecorder] is one: that
 * port is a `fun interface` with lambda implementations this module cannot all see.
 *
 * [managedTables] THROWS when the read fails. It never answers an empty list for a failure: the pool
 * factory builds no generation from an unverified read (record §10: "the live one serves until its
 * bound and the datasource then reports `lake.unavailable`"), and the freshness tick skips its
 * comparison. [NONE] answers "no managed tables", which is the truthful answer for a deployment
 * without the wiring.
 */
fun interface ManagedLakeCatalog {
    /** The managed tables of the lake named [lakeName], in tree order (namespace, then name). */
    fun managedTables(lakeName: String): List<ManagedLakeTable>

    companion object {
        /** No catalog behind this module — tests, and any deployment without the wiring. */
        val NONE = ManagedLakeCatalog { emptyList() }
    }
}

/**
 * #818 — one managed table a node cannot read (record §10 "Unavailable tables"): the executor's
 * CONNECT-time check refuses a node that names it with `lake.table.unavailable`, rather than the
 * engine's raw unknown-relation error, because the planner gave the table no view.
 */
data class LakeUnavailableTable(
    val namespace: List<String>,
    val name: String,
    /** [ManagedLakeTable.unavailableReason], never null here. */
    val reason: String,
) {
    /** The dotted qualified name — `lake.sales.orders`. */
    val qualifiedName: String get() = (namespace + name).joinToString(".")
}
