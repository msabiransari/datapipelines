package co.datapipelines.application.datasources

import java.time.Instant
import java.util.UUID

/**
 * The dp-lake catalog's row (metadata-db §4.15, the 2026-09-07 lake-datasource design record
 * §2, round 089 §A): one Parquet or Iceberg table a LAKE-dialect datasource serves.
 *
 * A LAKE datasource's tables are exactly these rows — the engine cannot LIST a bucket, so this
 * registry is the catalog introspection and (in a later phase) per-table `CREATE VIEW`
 * generation read. Rows are written by [LakeTableRegistryService] alone.
 */
data class LakeTable(
    val id: UUID,
    /** The datasource NAME — `datasources`' primary key is its name (metadata-db §4.10). */
    val datasourceId: String,
    /** 087's namespace segments, outermost first — `["nyc", "mobility"]`. One to nine segments. */
    val namespace: List<String>,
    val name: String,
    val format: LakeTableFormat,
    /** `s3://bucket/prefix[/glob]` (Iceberg: the current metadata FILE, not the table root — datasources.md §8C.7) or a `file://` path. */
    val location: String,
    /** Null when the table is not partitioned. */
    val partitionColumn: String?,
    val registeredBy: UUID,
    val registeredAt: Instant,
    /**
     * 109 §A / V20 — the connect-time view creation's last recorded failure (the engine's or
     * the SQL-emission boundary's message, bounded), NULL when the view last built cleanly.
     * A table whose `lastError` is set has NO view on any newly built connection: a node
     * referencing it fails `datasource.lake.table_unavailable`, and the detail page and the
     * lake-tables listing show this text. Cleared — with [lastErrorAt] — by the next
     * successful view creation (the pool rebuild after the operator fixes the location).
     */
    val lastError: String? = null,
    /** When [lastError] was newly recorded (recording is transition-only — "broken since"). */
    val lastErrorAt: Instant? = null,
) {
    /** The dotted shorthand of the fully-qualified name — `nyc.mobility.hvfhv_zone_day`. */
    val qualifiedName: String get() = (namespace + name).joinToString(".")
}

/**
 * What a table IS physically (metadata-db §4.15's CHECK, enums.md §5's closed-set convention):
 * the value the view-creation phase maps to `read_parquet(...)` versus `iceberg_scan(...)`.
 * The wire form is lowercase, matching 088's `manifest.json` `tables[]` block verbatim.
 */
enum class LakeTableFormat(
    val wire: String,
) {
    PARQUET("parquet"),
    ICEBERG("iceberg"),
    ;

    companion object {
        fun fromWireOrNull(wire: String): LakeTableFormat? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A registration's input, pre-persistence — the validated triple plus the two optional-ish
 * fields. Produced by [LakeTableValidator] from a REST/MCP body or a manifest entry; nothing
 * unvalidated reaches [LakeTableRepository].
 */
data class LakeTableRegistration(
    val namespace: List<String>,
    val name: String,
    val format: LakeTableFormat,
    val location: String,
    val partitionColumn: String?,
)

/**
 * The shared wire projection for a registered lake table — the ONE shape REST and MCP emit, so
 * the two surfaces cannot drift (the §7A `toWireMap` precedent in `modules/datasources`).
 * `registered_by` stays off the wire deliberately: no datasource response exposes a user id
 * (the §3.2 shape carries none), and the audit trail is where actors are read.
 */
fun LakeTable.toWireMap(): Map<String, Any?> =
    linkedMapOf(
        "namespace" to namespace,
        "name" to name,
        "qualified_name" to qualifiedName,
        "format" to format.wire,
        "location" to location,
        "partition_column" to partitionColumn,
        "registered_at" to registeredAt.toString(),
        // 109 §A: the connect-time view failure, when the table is broken — both surfaces
        // (REST listing, MCP) show the same recorded reason the detail page's badge does.
        "last_error" to lastError,
        "last_error_at" to lastErrorAt?.toString(),
    )
