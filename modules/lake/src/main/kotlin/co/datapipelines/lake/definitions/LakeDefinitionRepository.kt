package co.datapipelines.lake.definitions

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** A managed table's row (metadata-db §4.49) with its namespace's name. */
data class LakeManagedTable(
    val id: UUID,
    val namespaceId: UUID,
    val namespace: String,
    val name: String,
    val status: LakeTableStatus,
    val currentDefinitionVersion: Int?,
    val appliedVersionHere: Int?,
    val applyState: LakeApplyState?,
    val applyErrorJson: String?,
    val appliedAt: Instant?,
    val applyOperationId: UUID?,
    val createdBy: UUID,
)

/** One definition version (metadata-db §4.50). [bodyJson] was validated before it was stored. */
data class LakeDefinitionVersion(
    val tableId: UUID,
    val version: Int,
    val status: PipelineVersionStatus,
    val bodyJson: String,
    val bodyHash: String,
)

/**
 * The SQL of the definitions (V54). Every statement is parameterised; every state change is conditional on the state
 * it moves from (and, for the apply stamps, on the attempt's `apply_operation_id`), and answers how many rows it
 * changed so the caller can tell a lost race from success. Transactions are the caller's.
 */
@Suppress("TooManyFunctions") // one method per statement of the lifecycle and the apply state machine
class LakeDefinitionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun findTable(tableId: UUID): LakeManagedTable? =
        jdbc.query("$SELECT_TABLE WHERE t.id = :id", mapOf("id" to tableId), TABLE_MAPPER).singleOrNull()

    /**
     * Locks the managed row for a lifecycle transaction. All lifecycle mutations take this lock
     * before changing version rows, serializing their eligibility checks with apply claims/stamps.
     */
    fun findTableForUpdate(tableId: UUID): LakeManagedTable? =
        jdbc.query("$SELECT_TABLE WHERE t.id = :id FOR UPDATE OF t", mapOf("id" to tableId), TABLE_MAPPER).singleOrNull()

    /** The (namespace, table) lookup: the managed table of that name, or null. */
    fun findTable(
        namespace: String,
        table: String,
    ): LakeManagedTable? =
        jdbc
            .query(
                "$SELECT_TABLE WHERE n.name = :ns AND t.name = :table",
                mapOf("ns" to namespace, "table" to table),
                TABLE_MAPPER,
            ).singleOrNull()

    /** The namespace's id, creating its row first when it has none (`ON CONFLICT (name) DO NOTHING`). */
    fun namespaceId(
        namespace: String,
        actor: UUID,
    ): UUID {
        jdbc.update(
            "INSERT INTO lake_namespaces (id, name, created_by) VALUES (:id, :name, :actor) ON CONFLICT (name) DO NOTHING",
            mapOf("id" to UUID.randomUUID(), "name" to namespace, "actor" to actor),
        )
        return requireNotNull(
            jdbc.queryForObject("SELECT id FROM lake_namespaces WHERE name = :name", mapOf("name" to namespace), UUID::class.java),
        )
    }

    /** Inserts the table row; false when the namespace already has a table of that name. */
    fun insertTable(
        id: UUID,
        namespaceId: UUID,
        name: String,
        policyJson: String,
        actor: UUID,
    ): Boolean =
        try {
            jdbc.update(
                "INSERT INTO lake_tables_managed (id, namespace_id, name, policy_json, created_by) " +
                    "VALUES (:id, :ns, :name, CAST(:policy AS jsonb), :actor)",
                mapOf("id" to id, "ns" to namespaceId, "name" to name, "policy" to policyJson, "actor" to actor),
            )
            true
        } catch (_: DuplicateKeyException) {
            false
        }

    /** Inserts a DRAFT; null when the table already has one (the one-draft index refused it). */
    fun insertDraft(
        tableId: UUID,
        version: Int,
        bodyJson: String,
        actor: UUID,
        via: WriteSurface,
    ): LakeDefinitionVersion? =
        try {
            jdbc
                .query(
                    "INSERT INTO lake_table_definition_versions (table_id, version, body_json, body_hash, created_by, created_via, " +
                        "updated_by, updated_at, updated_via) VALUES (:table, :version, CAST(:body AS jsonb), $HASH_EXPR, :actor, " +
                        ":via, :actor, NOW(), :via) RETURNING $VERSION_COLUMNS",
                    mapOf("table" to tableId, "version" to version, "body" to bodyJson, "actor" to actor, "via" to via.wire),
                    VERSION_MAPPER,
                ).single()
        } catch (_: DuplicateKeyException) {
            null
        }

    fun findVersion(
        tableId: UUID,
        version: Int,
    ): LakeDefinitionVersion? =
        jdbc
            .query(
                "SELECT $VERSION_COLUMNS FROM lake_table_definition_versions WHERE table_id = :table AND version = :version",
                mapOf("table" to tableId, "version" to version),
                VERSION_MAPPER,
            ).singleOrNull()

    /**
     * The table's first version EVER released: the lowest version whose `released_at` is set, whether it is still
     * RELEASED or was DISCARDED since (a discard keeps `released_at`). Null when the table has never been released.
     * Its body fixes the sort columns (lake.md §1, the lock).
     */
    fun findFirstRelease(tableId: UUID): LakeDefinitionVersion? =
        jdbc
            .query(
                "SELECT $VERSION_COLUMNS FROM lake_table_definition_versions WHERE table_id = :table AND released_at IS NOT NULL " +
                    "ORDER BY version LIMIT 1",
                mapOf("table" to tableId),
                VERSION_MAPPER,
            ).singleOrNull()

    fun findDraft(tableId: UUID): LakeDefinitionVersion? =
        jdbc
            .query(
                "SELECT $VERSION_COLUMNS FROM lake_table_definition_versions WHERE table_id = :table AND status = 'DRAFT'",
                mapOf("table" to tableId),
                VERSION_MAPPER,
            ).singleOrNull()

    fun maxVersion(tableId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(version), 0) FROM lake_table_definition_versions WHERE table_id = :table",
            mapOf("table" to tableId),
            Int::class.java,
        ) ?: 0

    /** Overwrites the DRAFT at [expectedHash]; null when the draft is gone or its hash moved on (a lost race). */
    fun overwriteDraft(
        tableId: UUID,
        bodyJson: String,
        expectedHash: String,
        actor: UUID,
        via: WriteSurface,
    ): LakeDefinitionVersion? =
        jdbc
            .query(
                "UPDATE lake_table_definition_versions SET body_json = CAST(:body AS jsonb), body_hash = $HASH_EXPR, " +
                    "updated_by = :actor, updated_at = NOW(), updated_via = :via " +
                    "WHERE table_id = :table AND status = 'DRAFT' AND body_hash = :expected RETURNING $VERSION_COLUMNS",
                mapOf("table" to tableId, "body" to bodyJson, "expected" to expectedHash, "actor" to actor, "via" to via.wire),
                VERSION_MAPPER,
            ).singleOrNull()

    /** DRAFT → RELEASED, at [expectedHash] when one is given. Answers the rows changed (0 = not the draft any more). */
    fun releaseDraft(
        tableId: UUID,
        version: Int,
        expectedHash: String?,
        actor: UUID,
    ): Int =
        jdbc.update(
            "UPDATE lake_table_definition_versions SET status = 'RELEASED', released_at = NOW(), released_by = :actor " +
                "WHERE table_id = :table AND version = :version AND status = 'DRAFT' " +
                "AND (CAST(:expected AS text) IS NULL OR body_hash = :expected)",
            mapOf("table" to tableId, "version" to version, "expected" to expectedHash, "actor" to actor),
        )

    /**
     * Points the table at a newly released [version] and opens its apply: `pending`, unless an attempt is in flight
     * (`applying` stays, and that attempt's stamp turns the row back to `pending` because the pointer moved).
     */
    fun pointAtRelease(
        tableId: UUID,
        version: Int,
    ): Int =
        jdbc.update(
            "UPDATE lake_tables_managed SET current_definition_version = :version, " +
                "apply_state = CASE WHEN apply_state = 'applying' THEN 'applying' ELSE 'pending' END, apply_error_json = NULL " +
                "WHERE id = :id AND (current_definition_version IS NULL OR current_definition_version < :version)",
            mapOf("id" to tableId, "version" to version),
        )

    /** RELEASED → DISCARDED. Answers the rows changed. */
    fun discardRelease(
        tableId: UUID,
        version: Int,
        actor: UUID,
    ): Int =
        jdbc.update(
            "UPDATE lake_table_definition_versions SET status = 'DISCARDED', discarded_at = NOW(), discarded_by = :actor " +
                "WHERE table_id = :table AND version = :version AND status = 'RELEASED'",
            mapOf("table" to tableId, "version" to version, "actor" to actor),
        )

    /**
     * After a discard: the pointer becomes the highest RELEASED version (NULL when none is left). When it moves, the
     * apply state follows: NULL without a pointer, `applied` when the environment already holds it, else `pending`.
     * A row with an attempt in flight is left alone (the discard refuses that case first).
     */
    fun recomputeCurrent(tableId: UUID): Int =
        jdbc.update(
            """
            UPDATE lake_tables_managed t
               SET current_definition_version = r.v,
                   apply_state = CASE WHEN r.v IS NOT DISTINCT FROM t.current_definition_version THEN t.apply_state
                                      WHEN r.v IS NULL THEN NULL
                                      WHEN r.v = t.applied_version_here THEN 'applied'
                                      ELSE 'pending' END,
                   apply_error_json = CASE WHEN r.v IS NOT DISTINCT FROM t.current_definition_version THEN t.apply_error_json
                                           ELSE NULL END
              FROM (SELECT MAX(version) AS v FROM lake_table_definition_versions
                     WHERE table_id = :id AND status = 'RELEASED') r
             WHERE t.id = :id AND t.apply_state IS DISTINCT FROM 'applying'
            """.trimIndent(),
            mapOf("id" to tableId),
        )

    /** Deletes the DRAFT, at [expectedHash] when one is given. Answers the rows deleted. */
    fun deleteDraft(
        tableId: UUID,
        expectedHash: String?,
    ): Int =
        jdbc.update(
            "DELETE FROM lake_table_definition_versions WHERE table_id = :table AND status = 'DRAFT' " +
                "AND (CAST(:expected AS text) IS NULL OR body_hash = :expected)",
            mapOf("table" to tableId, "expected" to expectedHash),
        )

    /** Deletes the table row when no version of it is left (a never-released table's purge-draft). */
    fun deleteTableIfVersionless(tableId: UUID): Int =
        jdbc.update(
            "DELETE FROM lake_tables_managed t WHERE t.id = :id AND NOT EXISTS " +
                "(SELECT 1 FROM lake_table_definition_versions v WHERE v.table_id = t.id)",
            mapOf("id" to tableId),
        )

    /** Deletes the namespace row when no managed table is left in it. */
    fun deleteNamespaceIfEmpty(namespaceId: UUID): Int =
        jdbc.update(
            "DELETE FROM lake_namespaces n WHERE n.id = :id " +
                "AND NOT EXISTS (SELECT 1 FROM lake_tables_managed t WHERE t.namespace_id = n.id)",
            mapOf("id" to namespaceId),
        )

    companion object {
        /** The house body hash (versioning §3.3, `ArtifactSql.HASH_EXPR`): sha256 over the stored jsonb's text. */
        const val HASH_EXPR = "encode(sha256(convert_to(CAST(:body AS jsonb)::text, 'UTF8')), 'hex')"

        private const val VERSION_COLUMNS = "table_id, version, status, body_json::text AS body_json, body_hash"

        private const val SELECT_TABLE =
            "SELECT t.id, t.namespace_id, n.name AS namespace, t.name, t.status, t.current_definition_version, " +
                "t.applied_version_here, t.apply_state, t.apply_error_json::text AS apply_error_json, t.applied_at, " +
                "t.apply_operation_id, t.created_by FROM lake_tables_managed t JOIN lake_namespaces n ON n.id = t.namespace_id"

        private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

        private val TABLE_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                LakeManagedTable(
                    id = rs.getObject("id", UUID::class.java),
                    namespaceId = rs.getObject("namespace_id", UUID::class.java),
                    namespace = rs.getString("namespace"),
                    name = rs.getString("name"),
                    status = LakeTableStatus.fromWire(rs.getString("status")),
                    currentDefinitionVersion = rs.intOrNull("current_definition_version"),
                    appliedVersionHere = rs.intOrNull("applied_version_here"),
                    applyState = rs.getString("apply_state")?.let(LakeApplyState::fromWire),
                    applyErrorJson = rs.getString("apply_error_json"),
                    appliedAt = rs.getTimestamp("applied_at")?.toInstant(),
                    applyOperationId = rs.getObject("apply_operation_id", UUID::class.java),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                )
            }

        private val VERSION_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                LakeDefinitionVersion(
                    tableId = rs.getObject("table_id", UUID::class.java),
                    version = rs.getInt("version"),
                    status = PipelineVersionStatus.valueOf(rs.getString("status")),
                    bodyJson = rs.getString("body_json"),
                    bodyHash = rs.getString("body_hash"),
                )
            }
    }
}
