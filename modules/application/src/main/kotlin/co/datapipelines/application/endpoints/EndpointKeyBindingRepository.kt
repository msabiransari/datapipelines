package co.datapipelines.application.endpoints

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistence for `endpoint_key_bindings` (metadata-db §4.14) — which API keys authorise which
 * node of the endpoint tree.
 *
 * The table is tiny (one row per key per bound node) and is read on the authorisation path of
 * every request under `/api/x`, so [findByPrefixes] fetches the whole ancestor chain in ONE
 * query rather than walking it with a query per level: a five-segment path would otherwise be six
 * round trips before the request is even authorised, which is the cheapest thing a hostile caller
 * could ask the database to do.
 */
class EndpointKeyBindingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * Every binding on any of [prefixes], in one query.
     *
     * The caller ([EndpointAuthorizer]) supplies the ancestor chain and decides which node wins;
     * this repository deliberately does not, because "the most specific node carrying ANY
     * binding decides" is a rule about the tree, not about storage, and it is unit-tested
     * without a database.
     */
    fun findByPrefixes(prefixes: Collection<String>): List<EndpointKeyBinding> {
        if (prefixes.isEmpty()) return emptyList()
        return jdbc.query(
            "$SELECT_COLUMNS WHERE path_prefix IN (:prefixes)",
            mapOf("prefixes" to prefixes),
            MAPPER,
        )
    }

    /** Every binding on the deployment — the tree screen renders inheritance from this. */
    fun findAll(): List<EndpointKeyBinding> = jdbc.query(SELECT_COLUMNS, MAPPER)

    /** The nodes one key binds — the key detail view, and what a revoke's blast radius is. */
    fun findByKey(apiKeyId: String): List<EndpointKeyBinding> =
        jdbc.query("$SELECT_COLUMNS WHERE api_key_id = :keyId", mapOf("keyId" to apiKeyId), MAPPER)

    /**
     * Binds [apiKeyId] at [pathPrefix].
     *
     * Idempotent by `ON CONFLICT DO NOTHING`: binding a key twice at one node is the same state,
     * and a promotion batch that re-pushes an unchanged binding must not fail on it.
     */
    fun insert(binding: EndpointKeyBinding): Boolean =
        jdbc.update(
            """
            INSERT INTO endpoint_key_bindings (path_prefix, api_key_id, workspace_id, created_by)
            VALUES (:prefix, :keyId, :workspaceId, :createdBy)
            ON CONFLICT (path_prefix, api_key_id) DO NOTHING
            """.trimIndent(),
            mapOf(
                "prefix" to binding.pathPrefix,
                "keyId" to binding.apiKeyId,
                "workspaceId" to binding.workspaceId,
                "createdBy" to binding.createdBy,
            ),
        ) > 0

    /** Unbinds one key from one node. */
    fun delete(
        pathPrefix: String,
        apiKeyId: String,
    ): Boolean =
        jdbc.update(
            "DELETE FROM endpoint_key_bindings WHERE path_prefix = :prefix AND api_key_id = :keyId",
            mapOf("prefix" to pathPrefix, "keyId" to apiKeyId),
        ) > 0

    private companion object {
        val SELECT_COLUMNS =
            """
            SELECT path_prefix, api_key_id, workspace_id, created_by, created_at
              FROM endpoint_key_bindings
            """.trimIndent()

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                EndpointKeyBinding(
                    pathPrefix = rs.getString("path_prefix"),
                    apiKeyId = rs.getString("api_key_id"),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }
    }
}
