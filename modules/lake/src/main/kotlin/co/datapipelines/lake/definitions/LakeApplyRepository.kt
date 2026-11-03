package co.datapipelines.lake.definitions

import com.fasterxml.jackson.databind.json.JsonMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The apply state machine's SQL (dp-lake design record §7, §8b): the conditional stamps of one attempt, the revision
 * log read that settles an interrupted one, and the two projections an applied stamp rebuilds. Every stamp is
 * conditional on the attempt's `apply_operation_id` and answers how many rows it changed. Transactions are the caller's.
 */
@Suppress("TooManyFunctions") // one method per transition of the four apply states, plus the two projections
class LakeApplyRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * `pending | failed | applying → applying` for attempt [operationId], only while [version] is the table's current
     * definition. Taking over an `applying` row is admitted because the caller holds the table lease: the attempt that
     * owned it has lost its own. Answers the rows changed (0 = the pointer moved on, or the table is gone).
     */
    fun markApplying(
        tableId: UUID,
        version: Int,
        operationId: UUID,
    ): Int =
        jdbc.update(
            "UPDATE lake_tables_managed SET apply_state = 'applying', apply_operation_id = :op, apply_error_json = NULL " +
                "WHERE id = :id AND current_definition_version = :version AND apply_state IN ('pending', 'failed', 'applying')",
            mapOf("id" to tableId, "version" to version, "op" to operationId),
        )

    /**
     * The applied stamp of attempt [operationId]: `applied_version_here = version`, and `applied` while [version] is
     * still current (`pending` when a newer release landed meanwhile). Conditional on the row still being this
     * attempt's: a stale attempt changes nothing. Answers the rows changed.
     */
    fun finishApplied(
        tableId: UUID,
        version: Int,
        operationId: UUID,
    ): Int =
        jdbc.update(
            "UPDATE lake_tables_managed SET apply_state = CASE WHEN current_definition_version = :version THEN 'applied' " +
                "ELSE 'pending' END, applied_version_here = :version, applied_at = NOW(), apply_error_json = NULL " +
                "WHERE id = :id AND apply_state = 'applying' AND apply_operation_id = :op",
            mapOf("id" to tableId, "version" to version, "op" to operationId),
        )

    /**
     * The definite negative verdict of attempt [operationId]: `failed` with [errorJson] while [version] is still
     * current, `pending` otherwise (the newer release deserves its own attempt). Conditional like [finishApplied].
     */
    fun finishFailed(
        tableId: UUID,
        version: Int,
        operationId: UUID,
        errorJson: String,
    ): Int =
        jdbc.update(
            "UPDATE lake_tables_managed SET apply_state = CASE WHEN current_definition_version = :version THEN 'failed' " +
                "ELSE 'pending' END, apply_error_json = CASE WHEN current_definition_version = :version " +
                "THEN CAST(:error AS jsonb) END WHERE id = :id AND apply_state = 'applying' AND apply_operation_id = :op",
            mapOf("id" to tableId, "version" to version, "op" to operationId, "error" to errorJson),
        )

    /** Attempt [operationId] changed nothing and reached no verdict: back to `pending`. Conditional like [finishApplied]. */
    fun finishPending(
        tableId: UUID,
        operationId: UUID,
    ): Int =
        jdbc.update(
            "UPDATE lake_tables_managed SET apply_state = 'pending', apply_error_json = NULL " +
                "WHERE id = :id AND apply_state = 'applying' AND apply_operation_id = :op",
            mapOf("id" to tableId, "op" to operationId),
        )

    /** Did operation [operationId] commit a revision of `<namespace>.<table>`? (the revision log, §8b). */
    fun revisionOf(
        namespace: String,
        table: String,
        operationId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM lake_table_revisions WHERE namespace = :ns AND table_name = :table AND operation_id = :op)",
            mapOf("ns" to namespace, "table" to table, "op" to operationId),
            Boolean::class.java,
        ) == true

    /** Does the Iceberg catalog [catalog] know [namespace] (a properties row or a table in it)? */
    fun icebergNamespaceExists(
        catalog: String,
        namespace: String,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM iceberg_namespace_properties WHERE catalog_name = :catalog AND namespace = :ns) " +
                "OR EXISTS (SELECT 1 FROM iceberg_tables WHERE catalog_name = :catalog AND table_namespace = :ns)",
            mapOf("catalog" to catalog, "ns" to namespace),
            Boolean::class.java,
        ) == true

    /**
     * Rebuilds the semantics projection (metadata-db §4.51) of [tableId] from [columns], the applied version's
     * columns in schema order: every row of the table is replaced.
     */
    fun rebuildSemantics(
        tableId: UUID,
        version: Int,
        columns: List<LakeSemanticsColumn>,
    ) {
        jdbc.update("DELETE FROM lake_column_semantics WHERE table_id = :id", mapOf("id" to tableId))
        if (columns.isEmpty()) return
        jdbc.batchUpdate(
            "INSERT INTO lake_column_semantics (table_id, field_id, ordinal, name, logical_type, iceberg_type, required, doc, " +
                "tags_json, applied_version) VALUES (:id, :field, :ordinal, :name, :logical, :iceberg, :required, :doc, " +
                "CAST(:tags AS jsonb), :version)",
            columns
                .mapIndexed { ordinal, c ->
                    mapOf(
                        "id" to tableId,
                        "field" to c.fieldId,
                        "ordinal" to ordinal,
                        "name" to c.name,
                        "logical" to c.logicalType,
                        "iceberg" to c.icebergType,
                        "required" to c.required,
                        "doc" to c.doc,
                        "tags" to c.tagsJson,
                        "version" to version,
                    )
                }.toTypedArray(),
        )
    }

    /**
     * Upserts the managed `lake_tables` registry row of `<namespace>.<table>` from its CURRENT revision (§6, §8b):
     * namespace `[<lake>, <ns>]`, `format = iceberg`, `origin = managed`, `location` = the revision's metadata
     * location, `revision_seq` = its `seq`. Written only when a live datasource named [lake] exists (the managed
     * datasource row is created at boot by P5/P8). Monotone: an existing managed row moves only to a higher
     * sequence, and an EXTERNAL row of the same name is never touched. Answers the rows written.
     */
    fun upsertRegistryRow(
        lake: String,
        namespace: String,
        table: String,
        registeredBy: UUID,
    ): Int =
        jdbc.update(
            """
            INSERT INTO lake_tables (datasource_id, namespace, name, format, location, registered_by, origin, revision_seq)
            SELECT d.name, ARRAY[CAST(:lake AS text), CAST(:ns AS text)], :table, 'iceberg', r.metadata_location, :by,
                   'managed', r.seq
              FROM datasources d
              JOIN lake_table_revisions r ON r.namespace = :ns AND r.table_name = :table
                                         AND r.superseded_at IS NULL AND r.dropped_at IS NULL
             WHERE d.name = :lake AND NOT d.is_deleted
            ON CONFLICT ON CONSTRAINT uq_lake_tables_datasource_namespace_name DO UPDATE
               SET location = EXCLUDED.location, revision_seq = EXCLUDED.revision_seq
             WHERE lake_tables.origin = 'managed'
               AND (lake_tables.revision_seq IS NULL OR lake_tables.revision_seq < EXCLUDED.revision_seq)
            """.trimIndent(),
            mapOf("lake" to lake, "ns" to namespace, "table" to table, "by" to registeredBy),
        )
}

/** One row of the semantics projection (metadata-db §4.51), computed from the applied definition body. */
data class LakeSemanticsColumn(
    val fieldId: Int,
    val name: String,
    val logicalType: String,
    val icebergType: String,
    val required: Boolean,
    val doc: String?,
    val tagsJson: String,
) {
    companion object {
        private val json = JsonMapper.builder().build()

        /** The projection rows of [body], in field order. */
        fun of(body: LakeDefinitionBody): List<LakeSemanticsColumn> =
            body.fields.map {
                LakeSemanticsColumn(
                    fieldId = it.id,
                    name = it.name,
                    logicalType = it.typeLabel,
                    icebergType = it.icebergType.toString(),
                    required = it.required,
                    doc = it.doc,
                    tagsJson = json.writeValueAsString(it.tags),
                )
            }
    }
}
