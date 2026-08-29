package co.datapipelines.datasources

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A persisted `datasources` row (metadata-db §4.10). Carries the **encrypted** password bytes
 * — [DatasourceRegistry] decrypts them only at pool build (§7.4), and no read path ever
 * surfaces them. `properties_json` is materialized back into [DatasourceProperties].
 *
 * `LongParameterList` is suppressed because the `datasources` table has 15 columns and this
 * object is its 1:1 row projection. Grouping them into sub-objects to satisfy the threshold
 * would put a shape in the code that does not exist in the schema, and the `RowMapper` would
 * then have to translate twice. The rule targets wide *behavioural* constructors; a table row
 * is the documented exception.
 */
@Suppress("LongParameterList")
class DatasourceRow(
    val name: String,
    val displayName: String,
    val description: String?,
    val dialect: Dialect,
    val jdbcUrl: String,
    val username: String,
    val passwordEncrypted: ByteArray,
    val properties: DatasourceProperties,
    val queryTimeoutSeconds: Int?,
    val introspectionIncludeSchemas: List<String>,
    val isReadonly: Boolean,
    /** The bound workspace (V4), or null = global (D9). */
    val workspaceId: UUID?,
    /** The bound workspace's name, joined at read time; null exactly when [workspaceId] is null. */
    val workspaceName: String?,
    val isDeleted: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
) {
    /** Projects to a [Datasource]; [password] is the decrypted plaintext, or null for reads. */
    fun toDatasource(password: String? = null): Datasource =
        Datasource(
            name = name,
            displayName = displayName,
            description = description,
            dialect = dialect,
            jdbcUrl = jdbcUrl,
            username = username,
            password = password,
            queryTimeoutSeconds = queryTimeoutSeconds,
            properties = properties,
            introspectionIncludeSchemas = introspectionIncludeSchemas,
            isReadonly = isReadonly,
            workspaceId = workspaceId,
            workspaceName = workspaceName,
        )
}

/**
 * `NamedParameterJdbcTemplate` access to `datasources` (metadata-db §4.10 / §6). No JPA, no
 * Hibernate (module-structure §8.1): every query is explicit, parameterized SQL — the
 * credential + metadata surface is fully parameterized, so there is no string-built SQL to
 * inject into. Schema creation belongs to `app`'s Flyway alone; nothing here alters a table.
 *
 * `updated_at` is application-maintained (metadata-db §2): every UPDATE sets it in its own SET
 * clause. `duplicate_name` is the primary-key violation translated to the catalog code — the
 * database is the only atomic authority on name uniqueness (same pattern as `PipelineRepository`).
 */
class DatasourceRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper = DEFAULT_MAPPER,
) {
    /** The live (non-deleted) row for [name], or null. */
    fun findByName(name: String): DatasourceRow? =
        jdbc.query("$SELECT_COLUMNS WHERE d.name = :name AND d.is_deleted = FALSE", mapOf("name" to name), mapper()).singleOrNull()

    /** Whether a live datasource exists under [name]. */
    fun exists(name: String): Boolean =
        jdbc
            .queryForObject(
                "SELECT EXISTS(SELECT 1 FROM datasources WHERE name = :name AND is_deleted = FALSE)",
                mapOf("name" to name),
                Boolean::class.java,
            ) ?: false

    /**
     * Whether ANY row holds [name] — soft-deleted included. `name` is the primary key
     * (metadata-db §4.10), so a soft-deleted name is permanently taken; bootstrap registration
     * (datasources §8A) asks this rather than [exists] so it treats a soft-deleted row as
     * present and skips it, instead of taking the create branch into a PK violation.
     */
    fun existsIncludingDeleted(name: String): Boolean =
        jdbc
            .queryForObject(
                "SELECT EXISTS(SELECT 1 FROM datasources WHERE name = :name)",
                mapOf("name" to name),
                Boolean::class.java,
            ) ?: false

    /** Every live datasource, name order; optionally narrowed to one [dialect]. No workspace filter — see [findAllVisible]. */
    fun findAll(dialect: Dialect? = null): List<DatasourceRow> {
        val filter = if (dialect == null) "" else " AND d.dialect = :dialect"
        return jdbc.query(
            "$SELECT_COLUMNS WHERE d.is_deleted = FALSE$filter ORDER BY d.name",
            mapOf("dialect" to dialect?.wire),
            mapper(),
        )
    }

    /**
     * Every datasource VISIBLE to [workspaceId] (workspaces design §5.3): the workspace's
     * bound rows plus every global one (`workspace_id IS NULL`), name order. The predicate
     * lives in the SQL — never a controller-side post-filter — so paging totals count
     * exactly what the principal can see (a post-filter leaks via paging counts).
     */
    fun findAllVisible(
        workspaceId: UUID,
        dialect: Dialect? = null,
    ): List<DatasourceRow> {
        val dialectFilter = if (dialect == null) "" else " AND d.dialect = :dialect"
        return jdbc.query(
            """
            $SELECT_COLUMNS
             WHERE d.is_deleted = FALSE AND (d.workspace_id IS NULL OR d.workspace_id = :workspaceId)$dialectFilter
             ORDER BY d.name
            """.trimIndent(),
            MapSqlParameterSource().addValue("workspaceId", workspaceId).addValue("dialect", dialect?.wire),
            mapper(),
        )
    }

    /**
     * The live row for [name] when VISIBLE to [workspaceId] (its bound rows + global), else
     * null — by-name GET of another workspace's datasource behaves as not-found (design
     * §5.3, the no-oracle rule). [findByName] stays unfiltered for the name-keyed internal
     * paths (save, pool build, D10 live read).
     */
    fun findVisibleByName(
        name: String,
        workspaceId: UUID,
    ): DatasourceRow? =
        jdbc
            .query(
                """
                $SELECT_COLUMNS
                 WHERE d.is_deleted = FALSE AND d.name = :name AND (d.workspace_id IS NULL OR d.workspace_id = :workspaceId)
                """.trimIndent(),
                MapSqlParameterSource().addValue("name", name).addValue("workspaceId", workspaceId),
                mapper(),
            ).singleOrNull()

    /** Inserts a new datasource, returning the stored row. Maps a PK collision to duplicate_name. */
    fun create(
        datasource: Datasource,
        passwordEncrypted: ByteArray,
        createdBy: UUID,
    ): DatasourceRow =
        mappingDuplicateName(datasource.name) {
            jdbc.query(INSERT_SQL, insertParams(datasource, passwordEncrypted, createdBy), mapper()).single()
        }

    /**
     * Updates a live datasource in place, returning the stored row, or null when no live row has
     * this name. `name` is never updated (immutable, §11.1). When [passwordEncrypted] is null the
     * existing credential is kept (PUT with no password); otherwise it is replaced. `is_readonly`
     * and `workspace_id` update to the entity's values — the D8-gated flag writes cross the
     * registry save boundary, which is what makes a flip reach the pool (see INSERT_SQL's note).
     */
    fun update(
        datasource: Datasource,
        passwordEncrypted: ByteArray?,
    ): DatasourceRow? {
        val params =
            MapSqlParameterSource()
                .addValue("name", datasource.name)
                .addValue("displayName", datasource.displayName)
                .addValue("description", datasource.description)
                .addValue("dialect", datasource.dialect.wire)
                .addValue("jdbcUrl", datasource.jdbcUrl)
                .addValue("username", datasource.username)
                .addValue("propertiesJson", propertiesJson(datasource.properties))
                .addValue("queryTimeoutSeconds", datasource.queryTimeoutSeconds)
                .addValue("introspectionIncludeSchemas", includeSchemasJson(datasource))
                .addValue("isReadonly", datasource.isReadonly)
                .addValue("workspaceId", datasource.workspaceId)
                .addValue("passwordEncrypted", passwordEncrypted)
        val sql = if (passwordEncrypted == null) UPDATE_KEEP_PASSWORD_SQL else UPDATE_WITH_PASSWORD_SQL
        return jdbc.query(sql, params, mapper()).singleOrNull()
    }

    /** Soft-deletes [name]; false when nothing live existed. The row (and its name) survive. */
    fun softDelete(name: String): Boolean =
        jdbc.update(
            "UPDATE datasources SET is_deleted = TRUE, updated_at = NOW() WHERE name = :name AND is_deleted = FALSE",
            mapOf("name" to name),
        ) > 0

    private fun insertParams(
        datasource: Datasource,
        passwordEncrypted: ByteArray,
        createdBy: UUID,
    ) = MapSqlParameterSource()
        .addValue("name", datasource.name)
        .addValue("displayName", datasource.displayName)
        .addValue("description", datasource.description)
        .addValue("dialect", datasource.dialect.wire)
        .addValue("jdbcUrl", datasource.jdbcUrl)
        .addValue("username", datasource.username)
        .addValue("passwordEncrypted", passwordEncrypted)
        .addValue("propertiesJson", propertiesJson(datasource.properties))
        .addValue("queryTimeoutSeconds", datasource.queryTimeoutSeconds)
        .addValue("introspectionIncludeSchemas", includeSchemasJson(datasource))
        .addValue("isReadonly", datasource.isReadonly)
        .addValue("workspaceId", datasource.workspaceId)
        .addValue("createdBy", createdBy)

    /**
     * The §5 `properties` object, verbatim — and **`{}` when both namespaces are empty**
     * (metadata-db §4.10 gives the column that default). Writing `{"hikari":{},"jdbc":{}}` for a
     * datasource that set no properties would make the stored document differ from both the
     * request body and the column default, so a `properties_json = '{}'` query would miss rows
     * that have no properties. Absent namespaces round-trip back through
     * [DatasourceProperties.fromRaw] as empty maps, so nothing is lost.
     */
    private fun propertiesJson(properties: DatasourceProperties): String {
        val namespaces =
            buildMap<String, Map<String, Any?>> {
                if (properties.hikari.isNotEmpty()) put("hikari", properties.hikari)
                if (properties.jdbc.isNotEmpty()) put("jdbc", properties.jdbc)
            }
        return objectMapper.writeValueAsString(namespaces)
    }

    private fun mapper(): RowMapper<DatasourceRow> =
        RowMapper { rs: ResultSet, _: Int ->
            DatasourceRow(
                name = rs.getString("name"),
                displayName = rs.getString("display_name"),
                description = rs.getString("description"),
                dialect = Dialect.fromWire(rs.getString("dialect")),
                jdbcUrl = rs.getString("jdbc_url"),
                username = rs.getString("username"),
                passwordEncrypted = rs.getBytes("password_encrypted"),
                properties = readProperties(rs.getString("properties_json")),
                queryTimeoutSeconds = rs.getObject("query_timeout_seconds") as? Int,
                introspectionIncludeSchemas = readIncludeSchemas(rs.getString("introspection_include_schemas_json")),
                isReadonly = rs.getBoolean("is_readonly"),
                workspaceId = rs.getObject("workspace_id", UUID::class.java),
                workspaceName = rs.getString("workspace_name"),
                isDeleted = rs.getBoolean("is_deleted"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java),
            )
        }

    /**
     * The §7A allowlist as stored: the `[]` column default (metadata-db §4.10) reads as the
     * empty list — absent and empty are the same behavior. Entries arrive already
     * normalized from the registry's save boundary; the save-time validator has already
     * rejected pattern entries.
     */
    private fun includeSchemasJson(datasource: Datasource): String = objectMapper.writeValueAsString(datasource.introspectionIncludeSchemas)

    private fun readIncludeSchemas(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        // The read-boundary normalization (§3.3): restore and manual JSONB edits write rows
        // without crossing the registry's save boundary, and an unnormalized entry silently
        // exempts nothing — normalize here so no row can sit inert.
        return Datasource.normalizeIncludeSchemas(objectMapper.readValue(json, List::class.java).filterIsInstance<String>())
    }

    private fun readProperties(json: String?): DatasourceProperties {
        if (json.isNullOrBlank()) return DatasourceProperties()
        val raw: Map<String, Any?> = objectMapper.readValue(json)
        return DatasourceProperties.fromRaw(raw)
    }

    private fun <T> mappingDuplicateName(
        name: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: DuplicateKeyException) {
            if (e.mostSpecificCause.message?.contains(NAME_CONSTRAINT) != true) throw e
            throw DatapipelinesException(
                code = DatasourceErrorCodes.DUPLICATE_NAME,
                message = "A datasource named '${name.truncateForError()}' already exists.",
                details = mapOf("name" to name.truncateForError()),
                cause = e,
            )
        }

    private companion object {
        val DEFAULT_MAPPER: ObjectMapper = jacksonObjectMapper()

        /** The implicit index behind the `name` primary key (metadata-db §4.10). */
        const val NAME_CONSTRAINT = "datasources_pkey"

        const val COLUMNS =
            "d.name, d.display_name, d.description, d.dialect, d.jdbc_url, d.username, d.password_encrypted, " +
                "d.properties_json, d.query_timeout_seconds, d.introspection_include_schemas_json, " +
                "d.is_readonly, d.workspace_id, w.name AS workspace_name, d.is_deleted, d.created_at, d.updated_at, d.created_by"

        /** Every read joins `workspaces` for the additive `workspace` name (LEFT — global rows have NULL). */
        const val SELECT_COLUMNS =
            "SELECT $COLUMNS FROM datasources d LEFT JOIN workspaces w ON w.id = d.workspace_id"

        /**
         * `is_readonly` and `workspace_id` are written on INSERT and UPDATE both — the
         * surfaces slice's flag writes (workspaces design §6/D8) cross the registry's save
         * boundary, which evicts the pool on every update so a `readonly` flip takes effect
         * at the next pool build. The D8 gates (who may flip what) live at the web surface;
         * this module persists what it is handed. Bootstrap registration names neither
         * global (NULL) nor readonly=true specially — its values flow through unchanged.
         *
         * INSERT is a data-modifying CTE (the `WorkspaceRepository.create` precedent): the
         * RETURNING row is re-read through the workspace join so the stored row carries the
         * additive `workspace_name` exactly like every other read.
         */
        val INSERT_SQL =
            """
            WITH inserted AS (
                INSERT INTO datasources
                    (name, display_name, description, dialect, jdbc_url, username, password_encrypted,
                     properties_json, query_timeout_seconds, introspection_include_schemas_json,
                     is_readonly, workspace_id, created_by)
                VALUES
                    (:name, :displayName, :description, :dialect, :jdbcUrl, :username, :passwordEncrypted,
                     CAST(:propertiesJson AS jsonb), :queryTimeoutSeconds,
                     CAST(:introspectionIncludeSchemas AS jsonb), :isReadonly, :workspaceId, :createdBy)
                RETURNING name, display_name, description, dialect, jdbc_url, username, password_encrypted,
                    properties_json, query_timeout_seconds, introspection_include_schemas_json,
                    is_readonly, workspace_id, is_deleted, created_at, updated_at, created_by
            )
            SELECT d.name, d.display_name, d.description, d.dialect, d.jdbc_url, d.username, d.password_encrypted,
                   d.properties_json, d.query_timeout_seconds, d.introspection_include_schemas_json,
                   d.is_readonly, d.workspace_id, w.name AS workspace_name, d.is_deleted, d.created_at, d.updated_at, d.created_by
              FROM inserted d LEFT JOIN workspaces w ON w.id = d.workspace_id
            """.trimIndent()

        val UPDATE_WITH_PASSWORD_SQL = updateSql(includePassword = true)
        val UPDATE_KEEP_PASSWORD_SQL = updateSql(includePassword = false)

        /**
         * The UPDATE is a data-modifying CTE for the same reason as [INSERT_SQL]: Postgres
         * `RETURNING` cannot join, so the CTE's row is re-read through the workspace join and
         * the stored row carries `workspace_name` exactly like every other read. (Postgres
         * also rejects `UPDATE datasources d` aliases — the alias lives on the CTE.)
         */
        private fun updateSql(includePassword: Boolean): String {
            val passwordClause = if (includePassword) "password_encrypted = :passwordEncrypted,\n                       " else ""
            return """
                WITH updated AS (
                    UPDATE datasources
                       SET display_name = :displayName,
                           description = :description,
                           dialect = :dialect,
                           jdbc_url = :jdbcUrl,
                           username = :username,
                           ${passwordClause}properties_json = CAST(:propertiesJson AS jsonb),
                           query_timeout_seconds = :queryTimeoutSeconds,
                           introspection_include_schemas_json = CAST(:introspectionIncludeSchemas AS jsonb),
                           is_readonly = :isReadonly,
                           workspace_id = :workspaceId,
                           updated_at = NOW()
                     WHERE name = :name AND is_deleted = FALSE
                    RETURNING name, display_name, description, dialect, jdbc_url, username, password_encrypted,
                        properties_json, query_timeout_seconds, introspection_include_schemas_json,
                        is_readonly, workspace_id, is_deleted, created_at, updated_at, created_by
                )
                SELECT d.name, d.display_name, d.description, d.dialect, d.jdbc_url, d.username, d.password_encrypted,
                       d.properties_json, d.query_timeout_seconds, d.introspection_include_schemas_json,
                       d.is_readonly, d.workspace_id, w.name AS workspace_name, d.is_deleted, d.created_at, d.updated_at, d.created_by
                  FROM updated d LEFT JOIN workspaces w ON w.id = d.workspace_id
                """.trimIndent()
        }
    }
}
