package co.datapipelines.application.dashboards

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** One `dashboard_key_bindings` row (metadata-db §4.36): a key bound at a FOLDER of the dashboard name space. */
data class DashboardKeyBinding(
    val namePrefix: String,
    val apiKeyId: String,
    val workspaceId: UUID,
    val createdBy: UUID,
    val createdAt: Instant,
)

/**
 * Persistence for `dashboard_key_bindings` (metadata-db §4.36, L5) — which `dashboard` keys
 * authorise which folder of the dashboard name space. `EndpointKeyBindingRepository`'s mould,
 * copied faithfully: the table is tiny, it is read on the runtime's lens path for every
 * `dashboard`-key request, and [findByPrefixes] fetches the whole ancestor chain in ONE query
 * rather than walking it with a query per level — the same "a hostile caller's cheapest request
 * is six round trips" reasoning the endpoint repository records.
 *
 * The caller ([DashboardKeyAuthorizer]) supplies the ancestor chain and decides which node wins;
 * this repository deliberately does not, because "the most specific folder carrying ANY binding
 * decides" is a rule about the name tree, not about storage, and it is unit-tested without a
 * database ([DashboardKeyAuthorizerTest]).
 *
 * The workspace predicate (#191's rule) is in the query — a binding only ever decides for a
 * dashboard of ITS OWN workspace, so rows pinned elsewhere are not fetched on the lens path at
 * all. The authorizer re-checks the surviving rows in memory as the fail-closed backstop.
 */
class DashboardKeyBindingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Every binding of [workspaceId] on any of [prefixes], in one query. */
    fun findByPrefixes(
        prefixes: Collection<String>,
        workspaceId: UUID,
    ): List<DashboardKeyBinding> {
        if (prefixes.isEmpty()) return emptyList()
        return jdbc.query(
            "$SELECT_COLUMNS WHERE name_prefix IN (:prefixes) AND workspace_id = :workspaceId",
            mapOf("prefixes" to prefixes, "workspaceId" to workspaceId),
            MAPPER,
        )
    }

    /** Every binding of ONE workspace — what its Keys-page folder picker may offer. */
    fun findByWorkspace(workspaceId: UUID): List<DashboardKeyBinding> =
        jdbc.query("$SELECT_COLUMNS WHERE workspace_id = :workspaceId", mapOf("workspaceId" to workspaceId), MAPPER)

    /** The folders one key binds — the key's row on the Keys page, and what a revoke's blast radius is. */
    fun findByKey(apiKeyId: String): List<DashboardKeyBinding> =
        jdbc.query("$SELECT_COLUMNS WHERE api_key_id = :keyId", mapOf("keyId" to apiKeyId), MAPPER)

    /**
     * Binds [apiKeyId] at [namePrefix]. Idempotent by `ON CONFLICT DO NOTHING`: binding a key
     * twice at one folder is the same state, and the Keys page's set-delta editor must not fail
     * on a lost race.
     */
    fun insert(binding: DashboardKeyBinding): Boolean =
        jdbc.update(
            """
            INSERT INTO dashboard_key_bindings (name_prefix, api_key_id, workspace_id, created_by)
            VALUES (:prefix, :keyId, :workspaceId, :createdBy)
            ON CONFLICT (name_prefix, api_key_id) DO NOTHING
            """.trimIndent(),
            mapOf(
                "prefix" to binding.namePrefix,
                "keyId" to binding.apiKeyId,
                "workspaceId" to binding.workspaceId,
                "createdBy" to binding.createdBy,
            ),
        ) > 0

    /**
     * Unbinds one key from one folder — inside [workspaceId] only. Every caller already pins
     * the key to the active workspace before it gets here; the predicate makes that a property
     * of the statement rather than of the callers (D11, the #199 review's rule).
     */
    fun delete(
        namePrefix: String,
        apiKeyId: String,
        workspaceId: UUID,
    ): Boolean =
        jdbc.update(
            "DELETE FROM dashboard_key_bindings WHERE name_prefix = :prefix AND api_key_id = :keyId AND workspace_id = :workspaceId",
            mapOf("prefix" to namePrefix, "keyId" to apiKeyId, "workspaceId" to workspaceId),
        ) > 0

    private companion object {
        val SELECT_COLUMNS =
            """
            SELECT name_prefix, api_key_id, workspace_id, created_by, created_at
              FROM dashboard_key_bindings
            """.trimIndent()

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                DashboardKeyBinding(
                    namePrefix = rs.getString("name_prefix"),
                    apiKeyId = rs.getString("api_key_id"),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }
    }
}
