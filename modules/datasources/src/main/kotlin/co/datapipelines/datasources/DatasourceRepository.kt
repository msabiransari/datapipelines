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
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A persisted `datasources` row (metadata-db §4.10). Carries the **encrypted** password bytes
 * — [DatasourceRegistry] decrypts them only at pool build (§7.4), and no read path ever
 * surfaces them. `properties_json` is materialized back into [DatasourceProperties].
 *
 * `LongParameterList` is suppressed because the `datasources` table has 14 columns and this
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
@Repository
class DatasourceRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper = DEFAULT_MAPPER,
) {
    /** The live (non-deleted) row for [name], or null. */
    fun findByName(name: String): DatasourceRow? =
        jdbc.query("$SELECT_COLUMNS WHERE name = :name AND is_deleted = FALSE", mapOf("name" to name), mapper()).singleOrNull()

    /** Whether a live datasource exists under [name]. */
    fun exists(name: String): Boolean =
        jdbc
            .queryForObject(
                "SELECT EXISTS(SELECT 1 FROM datasources WHERE name = :name AND is_deleted = FALSE)",
                mapOf("name" to name),
                Boolean::class.java,
            ) ?: false

    /** Every live datasource, name order; optionally narrowed to one [dialect]. */
    fun findAll(dialect: Dialect? = null): List<DatasourceRow> {
        val filter = if (dialect == null) "" else " AND dialect = :dialect"
        return jdbc.query(
            "$SELECT_COLUMNS WHERE is_deleted = FALSE$filter ORDER BY name",
            mapOf("dialect" to dialect?.wire),
            mapper(),
        )
    }

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
     * existing credential is kept (PUT with no password); otherwise it is replaced.
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
            "name, display_name, description, dialect, jdbc_url, username, password_encrypted, " +
                "properties_json, query_timeout_seconds, introspection_include_schemas_json, " +
                "is_deleted, created_at, updated_at, created_by"

        const val SELECT_COLUMNS = "SELECT $COLUMNS FROM datasources"

        val INSERT_SQL =
            """
            INSERT INTO datasources
                (name, display_name, description, dialect, jdbc_url, username, password_encrypted,
                 properties_json, query_timeout_seconds, introspection_include_schemas_json, created_by)
            VALUES
                (:name, :displayName, :description, :dialect, :jdbcUrl, :username, :passwordEncrypted,
                 CAST(:propertiesJson AS jsonb), :queryTimeoutSeconds,
                 CAST(:introspectionIncludeSchemas AS jsonb), :createdBy)
            RETURNING $COLUMNS
            """.trimIndent()

        val UPDATE_WITH_PASSWORD_SQL = updateSql(includePassword = true)
        val UPDATE_KEEP_PASSWORD_SQL = updateSql(includePassword = false)

        private fun updateSql(includePassword: Boolean): String {
            val passwordClause = if (includePassword) "password_encrypted = :passwordEncrypted,\n                   " else ""
            return """
                UPDATE datasources
                   SET display_name = :displayName,
                       description = :description,
                       dialect = :dialect,
                       jdbc_url = :jdbcUrl,
                       username = :username,
                       ${passwordClause}properties_json = CAST(:propertiesJson AS jsonb),
                       query_timeout_seconds = :queryTimeoutSeconds,
                       introspection_include_schemas_json = CAST(:introspectionIncludeSchemas AS jsonb),
                       updated_at = NOW()
                 WHERE name = :name AND is_deleted = FALSE
                RETURNING $COLUMNS
                """.trimIndent()
        }
    }
}
