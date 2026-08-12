package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persistence for `pipelines` and `pipeline_versions` (metadata-db §4.4/§4.5/§6).
 *
 * `NamedParameterJdbcTemplate` exclusively — no JPA, no Hibernate, no Exposed
 * (module-structure §8.1): every query is explicit SQL, so there is no generated SQL to
 * reverse-engineer and no lazy-loading trap. The repository lives in the module that owns
 * the entity (§3.1 persistence-ownership rule 1); the `DataSource` bean is app-level and
 * schema creation belongs to `app`'s Flyway alone (rule 2) — nothing here creates or alters
 * a table.
 *
 * ## Immutable per version
 *
 * A pipeline is immutable per version (§2): editing does not update a body, it appends a new
 * `pipeline_versions` row and bumps `pipelines.current_version`. Both writes must land
 * together — "a `pipelines` row with `current_version = 1` and no matching
 * `pipeline_versions` row is a pipeline that cannot be executed or read, and nothing in the
 * schema forbids it (the FK points the other way)".
 *
 * [create] and [update] each do it in **one statement** using data-modifying CTEs, which
 * makes them atomic without an enclosing transaction. That is deliberate: `@Transactional`
 * belongs on the service layer (metadata-db §6.3 and the project's own Kotlin rules), and a
 * repository that quietly depends on a caller remembering to open one is a repository with a
 * corruption path.
 *
 * ## `updated_at`
 *
 * metadata-db §2: there are no triggers in this schema; every `UPDATE` sets
 * `updated_at = NOW()` in its own `SET` clause, and "an UPDATE that forgets `updated_at` is a
 * bug in the repository method". Both UPDATE statements below set it.
 */
@Repository
class PipelineRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** The pipeline's metadata row, or null when it does not exist or is soft-deleted. */
    fun findById(id: UUID): PipelineRecord? =
        jdbc.query("$SELECT_COLUMNS WHERE id = :id AND is_deleted = FALSE", mapOf("id" to id), MAPPER).singleOrNull()

    /** As [findById], by machine name — the identifier MCP and cross-pipeline references use (§3.2). */
    fun findByName(name: String): PipelineRecord? =
        jdbc
            .query("$SELECT_COLUMNS WHERE name = :name AND is_deleted = FALSE", mapOf("name" to name), MAPPER)
            .singleOrNull()

    /** Every live pipeline, newest first; optionally narrowed to one owner (§14 `GET /pipelines`). */
    fun findAll(
        ownerId: UUID? = null,
        limit: Int? = null,
        offset: Int = 0,
    ): List<PipelineRecord> {
        val ownerFilter = if (ownerId == null) "" else " AND owner_id = :ownerId"
        val limitClause = if (limit != null) " LIMIT :limit OFFSET :offset" else ""
        val params = mutableMapOf<String, Any?>()
        if (ownerId != null) params["ownerId"] = ownerId
        if (limit != null) {
            params["limit"] = limit
            params["offset"] = offset
        }
        return jdbc.query(
            "$SELECT_COLUMNS WHERE is_deleted = FALSE$ownerFilter ORDER BY created_at DESC$limitClause",
            params,
            MAPPER,
        )
    }

    /** Live pipelines whose current body references [datasourceName] in any node; newest first. */
    fun findAllByDatasource(
        datasourceName: String,
        ownerId: UUID? = null,
        limit: Int? = null,
        offset: Int = 0,
    ): List<PipelineRecord> {
        val ownerFilter = if (ownerId == null) "" else " AND p.owner_id = :ownerId"
        val limitClause = if (limit != null) " LIMIT :limit OFFSET :offset" else ""
        val params = mutableMapOf<String, Any?>()
        params["datasourceName"] = datasourceName
        if (ownerId != null) params["ownerId"] = ownerId
        if (limit != null) {
            params["limit"] = limit
            params["offset"] = offset
        }
        return jdbc.query(
            """
            SELECT p.*
              FROM pipelines p
              JOIN pipeline_versions v ON v.pipeline_id = p.id AND v.version = p.current_version
             WHERE p.is_deleted = FALSE$ownerFilter
               AND (v.body_json @> jsonb_build_object('nodes', jsonb_build_array(jsonb_build_object('source', :datasourceName)))
                    OR v.body_json @> jsonb_build_object('nodes', jsonb_build_array(jsonb_build_object('output', jsonb_build_object('datasource', :datasourceName)))))
             ORDER BY p.created_at DESC
             $limitClause
            """.trimIndent(),
            params,
            MAPPER,
        )
    }

    /** Count of live pipelines. */
    fun countAll(): Int =
        checkNotNull(
            jdbc.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pipelines WHERE is_deleted = FALSE",
                Int::class.java,
            ),
        )

    /**
     * The stored body JSON of one version, or null when that version does not exist.
     *
     * Read as `body_json::TEXT` and deserialized by Jackson (metadata-db §6.2): binding a
     * `JsonNode` or a `PGobject` directly would move serialization into the driver and make
     * the code Postgres-specific for no gain.
     */
    fun findVersionBody(
        pipelineId: UUID,
        version: Int,
    ): String? =
        jdbc
            .query(
                """
                SELECT body_json::TEXT AS body_json
                  FROM pipeline_versions
                 WHERE pipeline_id = :pipelineId AND version = :version
                """.trimIndent(),
                mapOf("pipelineId" to pipelineId, "version" to version),
            ) { rs, _ -> rs.getString("body_json") }
            .singleOrNull()

    /** The body of the pipeline's current version — `GET /pipelines/{id}` (§14). */
    fun findLatestBody(pipelineId: UUID): String? = findById(pipelineId)?.let { findVersionBody(it.id, it.currentVersion) }

    /** Version metadata, newest first — `GET /pipelines/{id}/versions` (§14). */
    fun listVersions(pipelineId: UUID): List<PipelineVersionRecord> =
        jdbc.query(
            """
            SELECT pipeline_id, version, created_at, created_by
              FROM pipeline_versions
             WHERE pipeline_id = :pipelineId
             ORDER BY version DESC
            """.trimIndent(),
            mapOf("pipelineId" to pipelineId),
        ) { rs, _ ->
            PipelineVersionRecord(
                pipelineId = rs.getObject("pipeline_id", UUID::class.java),
                version = rs.getInt("version"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java),
            )
        }

    /**
     * Inserts the pipeline and its version 1 in one statement, returning the row the database
     * actually stored.
     *
     * The final `SELECT` reads back server-generated `created_at` / `updated_at` rather than
     * asserting what they should be — a hand-built return value is how a default, trigger or
     * CHECK becomes invisible to the caller (metadata-db §6.1's "why this shape").
     */
    fun create(
        pipeline: NewPipeline,
        bodyJson: String,
        createdBy: UUID,
    ): PipelineRecord =
        mappingDuplicateName(pipeline.name) {
            jdbc
                .query(
                    INSERT_PIPELINE_SQL,
                    mapOf(
                        "id" to pipeline.id,
                        "name" to pipeline.name,
                        "displayName" to pipeline.displayName,
                        "description" to pipeline.description,
                        "ownerId" to pipeline.ownerId,
                        "bodyJson" to bodyJson,
                        "createdBy" to createdBy,
                    ),
                    MAPPER,
                ).single()
        }

    /**
     * Appends a new version and bumps `current_version`, in one statement.
     *
     * Returns null when no live pipeline has this id — the caller decides whether that is a
     * 404 (`pipeline.execution.not_found`) or something else; the repository does not raise
     * catalog errors for control flow.
     */
    fun update(
        id: UUID,
        pipeline: Pipeline,
        bodyJson: String,
        updatedBy: UUID,
    ): PipelineRecord? =
        mappingDuplicateName(pipeline.name) {
            jdbc
                .query(
                    UPDATE_PIPELINE_SQL,
                    mapOf(
                        "id" to id,
                        "name" to pipeline.name,
                        "displayName" to pipeline.displayName,
                        "description" to pipeline.description,
                        "bodyJson" to bodyJson,
                        "updatedBy" to updatedBy,
                    ),
                    MAPPER,
                ).singleOrNull()
        }

    /**
     * Soft-deletes the pipeline (§14: "Soft delete"). Returns false when nothing was live to
     * delete.
     *
     * The row stays, so its name stays taken — metadata-db §4.4 makes that explicit and
     * deliberate: execution history references the name, so reusing it would re-point old
     * records at a different pipeline.
     */
    fun softDelete(id: UUID): Boolean =
        jdbc.update(
            "UPDATE pipelines SET is_deleted = TRUE, updated_at = NOW() WHERE id = :id AND is_deleted = FALSE",
            mapOf("id" to id),
        ) > 0

    /**
     * Translates a `pipelines.name` UNIQUE violation into §12.1's
     * `pipeline.validation.duplicate_name` (HTTP 409).
     *
     * ## Why the constraint, and not a read-then-write pre-check
     *
     * A `SELECT … WHERE name = ?` before the insert is a check two concurrent creates both
     * pass, after which one of them still violates the constraint and surfaces a raw
     * `DataIntegrityViolationException` — a 500 for what is a 409. The database is the only
     * thing that can answer this question atomically, so the constraint stays the authority and
     * this is its translation into the error catalog.
     *
     * ## Why the constraint name is matched
     *
     * These statements can violate more than one unique constraint: `pipelines_pkey` when an
     * import re-uses an id (§11.3), and `pipeline_versions_pkey` on a concurrent update of the
     * same pipeline. Mapping every `DuplicateKeyException` to `duplicate_name` would report
     * "that name is taken" for both, sending the caller to rename something that is not the
     * problem. `pipelines_name_key` is the implicit index metadata-db §4.4 names explicitly;
     * anything else is rethrown untouched.
     */
    private fun <T> mappingDuplicateName(
        name: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: DuplicateKeyException) {
            if (e.mostSpecificCause.message?.contains(NAME_CONSTRAINT) != true) throw e
            throw DatapipelinesException(
                code = PipelineErrorCodes.Validation.DUPLICATE_NAME,
                message = "A pipeline named '${name.truncateForError()}' already exists.",
                details = mapOf("name" to name.truncateForError()),
                cause = e,
            )
        }

    private companion object {
        /**
         * The implicit index behind `pipelines.name UNIQUE` (metadata-db §4.4).
         *
         * Note it is a plain UNIQUE constraint, **not** a partial index on `is_deleted = FALSE`:
         * a soft-deleted pipeline's name stays taken until the row is hard-deleted, which §4.4
         * states is deliberate because execution history references the name.
         */
        const val NAME_CONSTRAINT = "pipelines_name_key"

        const val COLUMNS =
            "id, name, display_name, description, owner_id, current_version, is_deleted, created_at, updated_at"

        const val SELECT_COLUMNS = "SELECT $COLUMNS FROM pipelines"

        val INSERT_PIPELINE_SQL =
            """
            WITH new_pipeline AS (
                INSERT INTO pipelines (id, name, display_name, description, owner_id, current_version)
                VALUES (:id, :name, :displayName, :description, :ownerId, 1)
                RETURNING $COLUMNS
            ), new_version AS (
                INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by)
                SELECT id, 1, CAST(:bodyJson AS jsonb), :createdBy FROM new_pipeline
                RETURNING pipeline_id
            )
            SELECT p.id, p.name, p.display_name, p.description, p.owner_id,
                   p.current_version, p.is_deleted, p.created_at, p.updated_at
              FROM new_pipeline p
              JOIN new_version v ON v.pipeline_id = p.id
            """.trimIndent()

        val UPDATE_PIPELINE_SQL =
            """
            WITH bumped AS (
                UPDATE pipelines
                   SET current_version = current_version + 1,
                       name = :name,
                       display_name = :displayName,
                       description = :description,
                       updated_at = NOW()
                 WHERE id = :id AND is_deleted = FALSE
                RETURNING $COLUMNS
            ), new_version AS (
                INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by)
                SELECT id, current_version, CAST(:bodyJson AS jsonb), :updatedBy FROM bumped
                RETURNING pipeline_id
            )
            SELECT p.id, p.name, p.display_name, p.description, p.owner_id,
                   p.current_version, p.is_deleted, p.created_at, p.updated_at
              FROM bumped p
              JOIN new_version v ON v.pipeline_id = p.id
            """.trimIndent()

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                PipelineRecord(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    displayName = rs.getString("display_name"),
                    description = rs.getString("description"),
                    ownerId = rs.getObject("owner_id", UUID::class.java),
                    currentVersion = rs.getInt("current_version"),
                    isDeleted = rs.getBoolean("is_deleted"),
                    // TIMESTAMPTZ → OffsetDateTime is exact regardless of the JVM zone;
                    // getTimestamp() without a Calendar reads it in the default zone
                    // (metadata-db §6.1 "timestamp reads").
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                )
            }
    }
}
