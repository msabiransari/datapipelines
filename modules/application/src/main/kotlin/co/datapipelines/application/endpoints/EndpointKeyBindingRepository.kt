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
 * every published-endpoint request, so [findByPrefixes] fetches the whole ancestor chain in ONE
 * query rather than walking it with a query per level: a five-segment path would otherwise be six
 * round trips before the request is even authorised, which is the cheapest thing a hostile caller
 * could ask the database to do.
 */
class EndpointKeyBindingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * Every binding of [workspaceId] on any of [prefixes], in one query.
     *
     * The caller ([EndpointAuthorizer]) supplies the ancestor chain and decides which node wins;
     * this repository deliberately does not, because "the most specific node carrying ANY
     * binding decides" is a rule about the tree, not about storage, and it is unit-tested
     * without a database.
     *
     * The workspace predicate (#191) is the D11 rule — filter in the query, never after —
     * applied to bindings: a binding only ever decides for an endpoint of ITS OWN workspace,
     * so rows pinned elsewhere are not fetched on the serve path at all. [EndpointAuthorizer]
     * re-checks the surviving rows in memory as the fail-closed backstop.
     */
    fun findByPrefixes(
        prefixes: Collection<String>,
        workspaceId: UUID,
    ): List<EndpointKeyBinding> {
        if (prefixes.isEmpty()) return emptyList()
        return jdbc.query(
            "$SELECT_COLUMNS WHERE path_prefix IN (:prefixes) AND workspace_id = :workspaceId",
            mapOf("prefixes" to prefixes, "workspaceId" to workspaceId),
            MAPPER,
        )
    }

    /**
     * Every binding of ONE workspace — what its tree screen and its promotion batch may see.
     *
     * The same D11 rule as [findByPrefixes] (#199): the predicate is in the query, so a
     * neighbour's row bound at an equal node — legal, since only the exact `path_pattern` is
     * globally unique (V11) while path trees may overlap — never reaches a reader that would
     * carry its key name into another workspace's batch.
     *
     * There is deliberately no unscoped read of this table any more: the `findAll()` this
     * replaced was filtered after the fetch by two readers and not at all by a third (#199),
     * and a method that answers every workspace's rows is a read no caller of this repository
     * has a legitimate use for.
     */
    fun findByWorkspace(workspaceId: UUID): List<EndpointKeyBinding> =
        jdbc.query("$SELECT_COLUMNS WHERE workspace_id = :workspaceId", mapOf("workspaceId" to workspaceId), MAPPER)

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

    /**
     * Unbinds one key from one node — inside [workspaceId] only. Every caller already pins
     * the key to the active workspace before it gets here; the predicate makes that a
     * property of the statement rather than of the callers (D11, the #199 review).
     */
    fun delete(
        pathPrefix: String,
        apiKeyId: String,
        workspaceId: UUID,
    ): Boolean =
        jdbc.update(
            "DELETE FROM endpoint_key_bindings WHERE path_prefix = :prefix AND api_key_id = :keyId AND workspace_id = :workspaceId",
            mapOf("prefix" to pathPrefix, "keyId" to apiKeyId, "workspaceId" to workspaceId),
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
