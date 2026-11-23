package co.datapipelines.application.endpoints

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistence for `endpoint_key_bindings` (metadata-db §4.14) — which API keys authorise which
 * routes. Since #1037 the table carries the two grant shapes of the method era
 * (published-mutation-apis §2): a GET binding is the tree-node grant it always was, and a
 * mutation grant (POST/PUT/DELETE) names exactly ONE route entity, by its `endpoint_id`.
 *
 * The table is tiny (one row per key per grant) and is read on the authorisation path of every
 * published-endpoint request, so [findByPrefixes] fetches the whole ancestor chain in ONE query
 * rather than walking it with a query per level: a five-segment path would otherwise be six
 * round trips before the request is even authorised, which is the cheapest thing a hostile caller
 * could ask the database to do.
 *
 * ## A row whose workspace is not its key's own decides nothing (#1111)
 *
 * A binding row records the workspace it was written in, and its key carries its own. Before #1111 the by-name bind
 * resolved the caller's key across every workspace they own, so a row could pair a key with ANOTHER workspace. Such a
 * row is preserved for an operator to inspect (docs/metadata-db.md §4.14, mismatched binding rows), but the
 * authorization reads — [findByPrefixes], [findMutationGrantsByKey] — and the promotion/tree read [findByWorkspace]
 * exclude it IN SQL (`VALID_KEY_WORKSPACE`): it neither decides nor shadows a node, and the nearest legitimate
 * ancestor decides again.
 */
class EndpointKeyBindingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * Every GET binding of [workspaceId] on any of [prefixes], in one query.
     *
     * GET-only by construction (#1037): the ancestor walk is a GET concept — a mutation grant
     * never shadows an inherited GET binding and is never inherited itself, so a walk that saw
     * mutation rows would be answering a question its rule does not define. The mutation shape
     * has its own reads ([findMutationGrantsByKey], [findByEndpoint]).
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
            "$SELECT_COLUMNS WHERE b.path_prefix IN (:prefixes) AND b.workspace_id = :workspaceId AND b.method = 'GET' " +
                "AND $VALID_KEY_WORKSPACE",
            mapOf("prefixes" to prefixes, "workspaceId" to workspaceId),
            MAPPER,
        )
    }

    /**
     * Every binding of ONE workspace — what its tree screen and its promotion batch may see.
     *
     * The same D11 rule as [findByPrefixes] (#199): the predicate is in the query, so a
     * neighbour's row bound at an equal node — legal, since only the exact `path_pattern` +
     * `method` is globally unique (V65) while path trees may overlap — never reaches a reader
     * that would carry its key name into another workspace's batch.
     *
     * There is deliberately no unscoped read of this table any more: the `findAll()` this
     * replaced was filtered after the fetch by two readers and not at all by a third (#199),
     * and a method that answers every workspace's rows is a read no caller of this repository
     * has a legitimate use for.
     */
    fun findByWorkspace(workspaceId: UUID): List<EndpointKeyBinding> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE b.workspace_id = :workspaceId AND $VALID_KEY_WORKSPACE",
            mapOf("workspaceId" to workspaceId),
            MAPPER,
        )

    /** The grants one key holds — the key detail view, and what a revoke's blast radius is. */
    fun findByKey(apiKeyId: String): List<EndpointKeyBinding> =
        jdbc.query("$SELECT_COLUMNS WHERE b.api_key_id = :keyId", mapOf("keyId" to apiKeyId), MAPPER)

    /**
     * The mutation grants one key holds in [workspaceId] — the (entity, method) pairs the serve
     * path tests a matched route's id against (#1037). A key holds a handful at most (one per
     * route entity it may call), so the whole list is cheaper than a query per candidate entity
     * and keeps the visibility rule in one place: the CALLER intersects with the registry, this
     * read answers only "what is this key granted".
     */
    fun findMutationGrantsByKey(
        apiKeyId: String,
        workspaceId: UUID,
    ): List<EndpointKeyBinding> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE b.api_key_id = :keyId AND b.workspace_id = :workspaceId AND b.method <> 'GET' " +
                "AND $VALID_KEY_WORKSPACE",
            mapOf("keyId" to apiKeyId, "workspaceId" to workspaceId),
            MAPPER,
        )

    /**
     * The bindings naming one route entity — the mutation entity's management view (the GET
     * entity's view stays the ancestor walk of [findByPrefixes], whose rows are the tree nodes
     * a listing has always shown).
     */
    fun findByEndpoint(endpointId: UUID): List<EndpointKeyBinding> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE b.endpoint_id = :endpointId",
            mapOf("endpointId" to endpointId),
            MAPPER,
        )

    /**
     * Records one grant — a GET binding at a tree node, or a mutation grant naming its entity.
     *
     * Idempotent by `ON CONFLICT DO NOTHING` on the full grant key `(path_prefix, api_key_id,
     * method)` (V65): recording the same grant twice is the same state, and a promotion batch
     * that re-pushes an unchanged binding must not fail on it. The [EndpointKeyBinding] shape
     * rule — GET rows carry no entity, mutation rows must — is the caller's to satisfy; the
     * table's CHECK is the backstop.
     */
    fun insert(binding: EndpointKeyBinding): Boolean =
        jdbc.update(
            """
            INSERT INTO endpoint_key_bindings (path_prefix, api_key_id, workspace_id, method, endpoint_id, created_by)
            VALUES (:prefix, :keyId, :workspaceId, :method, :endpointId, :createdBy)
            ON CONFLICT (path_prefix, api_key_id, method) DO NOTHING
            """.trimIndent(),
            mapOf(
                "prefix" to binding.pathPrefix,
                "keyId" to binding.apiKeyId,
                "workspaceId" to binding.workspaceId,
                "method" to binding.method.name,
                "endpointId" to binding.endpointId,
                "createdBy" to binding.createdBy,
            ),
        ) > 0

    /**
     * Sets the namespace-writes opt-in (#1111) of [apiKeyId]'s GET binding at [pathPrefix] in [workspaceId] in ONE
     * statement, and answers the stored state that statement produced — never a value read before a concurrent writer
     * moved it.
     *
     * `allow = true` creates the GET node binding already marked, or upgrades the existing one
     * (`INSERT … ON CONFLICT DO UPDATE`). The upgrade is confined to a row of the SAME workspace: a mismatched
     * historical row is never promoted, and the answer is then "not bound" — the row it would have marked decides
     * nothing. `allow = false` clears the marker on an existing row and creates nothing at an absent node (a downgrade
     * can never introduce a shadowing GET binding), so both forms of false are safe to repeat.
     */
    fun setNamespaceWrites(
        pathPrefix: String,
        apiKeyId: String,
        workspaceId: UUID,
        createdBy: UUID,
        allow: Boolean,
    ): NamespaceWritesState {
        val params =
            mapOf("prefix" to pathPrefix, "keyId" to apiKeyId, "workspaceId" to workspaceId, "createdBy" to createdBy)
        val sql = if (allow) UPGRADE_SQL else DOWNGRADE_SQL
        return jdbc.query(sql, params, STATE_MAPPER).firstOrNull()
            ?: NamespaceWritesState(allow = false, bound = false, created = false)
    }

    /** What [setNamespaceWrites] left stored: the marker, whether the key holds the GET binding, and whether it was created. */
    data class NamespaceWritesState(
        val allow: Boolean,
        val bound: Boolean,
        val created: Boolean,
    )

    /**
     * Unbinds one key from one grant — inside [workspaceId] only. Every caller already pins
     * the key to the active workspace before it gets here; the predicate makes that a
     * property of the statement rather than of the callers (D11, the #199 review).
     *
     * For a mutation grant the prefix+method pair IS the entity's address (the pair is unique
     * deployment-wide, V65, and a grant dies with its entity by cascade), so this needs no
     * `endpoint_id` term — unbinding answers "revoke this key's grant on THIS route", whatever
     * entity currently occupies it, and a recreated route is a NEW grant by construction.
     */
    fun delete(
        pathPrefix: String,
        apiKeyId: String,
        workspaceId: UUID,
        method: EndpointMethod = EndpointMethod.GET,
    ): Boolean =
        jdbc.update(
            "DELETE FROM endpoint_key_bindings " +
                "WHERE path_prefix = :prefix AND api_key_id = :keyId AND workspace_id = :workspaceId AND method = :method",
            mapOf("prefix" to pathPrefix, "keyId" to apiKeyId, "workspaceId" to workspaceId, "method" to method.name),
        ) > 0

    private companion object {
        val SELECT_COLUMNS =
            """
            SELECT b.path_prefix, b.api_key_id, b.workspace_id, b.method, b.endpoint_id, b.created_by, b.created_at,
                   b.allow_namespace_writes
              FROM endpoint_key_bindings b
            """.trimIndent()

        /** The row's workspace is its key's own (#1111): a mismatched historical row decides nothing (class KDoc). */
        const val VALID_KEY_WORKSPACE =
            "EXISTS (SELECT 1 FROM api_keys k WHERE k.id = b.api_key_id AND k.workspace_id = b.workspace_id)"

        /** Create-or-upgrade in one statement; `xmax = 0` is PostgreSQL's "this row was inserted, not updated". */
        val UPGRADE_SQL =
            """
            INSERT INTO endpoint_key_bindings
                   (path_prefix, api_key_id, workspace_id, method, endpoint_id, created_by, allow_namespace_writes)
            VALUES (:prefix, :keyId, :workspaceId, 'GET', NULL, :createdBy, TRUE)
            ON CONFLICT (path_prefix, api_key_id, method) DO UPDATE SET allow_namespace_writes = TRUE
             WHERE endpoint_key_bindings.workspace_id = EXCLUDED.workspace_id
            RETURNING allow_namespace_writes, (xmax = 0) AS created
            """.trimIndent()

        /** Clear on an existing row only: an absent node stays absent. */
        val DOWNGRADE_SQL =
            """
            UPDATE endpoint_key_bindings SET allow_namespace_writes = FALSE
             WHERE path_prefix = :prefix AND api_key_id = :keyId AND workspace_id = :workspaceId AND method = 'GET'
            RETURNING allow_namespace_writes, FALSE AS created
            """.trimIndent()

        val STATE_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                NamespaceWritesState(
                    allow = rs.getBoolean("allow_namespace_writes"),
                    bound = true,
                    created = rs.getBoolean("created"),
                )
            }

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                EndpointKeyBinding(
                    pathPrefix = rs.getString("path_prefix"),
                    apiKeyId = rs.getString("api_key_id"),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    // The CHECK constraints (V65) guarantee the method value names an enum
                    // constant and the shape rule holds — fail loudly rather than guess.
                    method = EndpointMethod.valueOf(rs.getString("method")),
                    endpointId = rs.getObject("endpoint_id", UUID::class.java),
                    allowNamespaceWrites = rs.getBoolean("allow_namespace_writes"),
                )
            }
    }
}
