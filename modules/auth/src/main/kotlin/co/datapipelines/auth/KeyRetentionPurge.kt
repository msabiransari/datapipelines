package co.datapipelines.auth

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.annotation.Transactional

/**
 * The keys purge (keys v2 A17/B5, #233): revocation is the end state of a key and of its
 * identity — the application never hard-deletes either — EXCEPT here, in the retention sweep's
 * last step, where a revoked key and its `service` identity are deleted **once nothing
 * references them any more**. The foreign keys make that safe, and this class refuses to
 * out-run them: the "nothing references" predicate is DERIVED from the live schema
 * (`information_schema`), not hard-coded, so a migration that adds a new FK referencing
 * `users` automatically blocks the purge without anyone remembering to extend a list.
 *
 * ## The predicate, precisely
 *
 * - a KEY goes when it is revoked, no `endpoint_key_bindings` row names it, AND its identity is
 *   unreferenced — the two are one unit (`api_keys.user_id`/`created_by` are FKs to the
 *   identity, so deleting a key whose identity must stay would be impossible anyway, and B5's
 *   falsification reads the pair: a revoked key with one live execution STAYS — the execution's
 *   `executed_by` names the identity — and one with none goes);
 * - an IDENTITY goes when it is a deactivated `service` row and NO column of ANY table
 *   references it: no key row, no execution, no audit row, no membership, no invitation —
 *   every FK the running schema declares, checked one NOT EXISTS per column.
 *
 * ## Never the constraints
 *
 * The purge disables nothing and defers nothing: it deletes only rows the declared FKs no
 * longer reference, inside one metadata transaction, so a purge that races a concurrent write
 * is simply serialized by the constraints it refuses to bypass.
 */
open class KeyRetentionPurge(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(KeyRetentionPurge::class.java)

    /** What one sweep purged — the counts an operator reads and the tests assert. */
    data class Result(
        val keysPurged: Int,
        val identitiesPurged: Int,
    )

    /**
     * Runs one purge. @return the [Result] with both counts — zero and zero when there was
     * nothing to purge, which is the common hourly answer.
     */
    @Transactional("metadataTransactionManager")
    open fun purgeOnce(): Result {
        val referencing = foreignKeysToUsers()
        val keysPurged = purgeKeys(referencing)
        val identitiesPurged = purgeIdentities(referencing)
        if (keysPurged > 0 || identitiesPurged > 0) {
            log.info("event=keys.purged keys={} identities={}", keysPurged, identitiesPurged)
        }
        return Result(keysPurged, identitiesPurged)
    }

    /**
     * Every `(table, column)` of the live schema whose FK references `users(id)` — the
     * predicate the whole purge is built from, re-read per run. Excludes the constraint's own
     * table when it is `users` (no self-reference exists, but the query stays honest).
     *
     * The PROJECTED side is the referencing one (`kcu.*`): `ccu` still SELECTS the referenced
     * side — `ccu.table_name = 'users' AND ccu.column_name = 'id'` is what makes the list "FKs
     * to users" — but the rows the purge needs name the tables and columns that HOLD the
     * reference. (233c, B5: the first draft projected `ccu.*`, so every row was
     * `('users','id')` and both NOT EXISTS predicates compared `users.id` to itself — nothing
     * was ever purged, silently.)
     */
    private fun foreignKeysToUsers(): List<Pair<String, String>> =
        jdbc
            .query(
                """
                SELECT kcu.table_name AS referencing_table, kcu.column_name AS referencing_column
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema
                  JOIN information_schema.constraint_column_usage ccu
                    ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema
                 WHERE tc.constraint_type = 'FOREIGN KEY'
                   AND tc.table_schema = 'public'
                   AND ccu.table_name = 'users' AND ccu.column_name = 'id'
                   AND kcu.table_name <> 'users'
                """.trimIndent(),
                emptyMap<String, Any?>(),
            ) { rs, _ -> rs.getString("referencing_table") to rs.getString("referencing_column") }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))

    /**
     * Revoked, unbound keys whose IDENTITY nothing references any more. The identity's
     * predicate is the FK list against [ApiKey.userId]; `created_by` names a person for every
     * v2 key, so it is deliberately not part of the key-side predicate — the identity-side
     * pass below still checks it.
     */
    private fun purgeKeys(referencing: List<Pair<String, String>>): Int {
        // The `api_keys` FKs are the key row's OWN reference to its identity — it goes WITH the
        // key, so it must not block the purge. An identity belongs to exactly one key (its
        // `provider_subject`), so no other key row can hold it back.
        val identityUnreferenced =
            referencing
                .filter { (table, _) -> table != "api_keys" }
                .joinToString("\n      AND ") { (table, column) ->
                    "NOT EXISTS (SELECT 1 FROM $table r WHERE r.$column = k.user_id)"
                }
        val sql =
            """
            DELETE FROM api_keys k
             WHERE k.is_revoked = TRUE
               AND NOT EXISTS (SELECT 1 FROM endpoint_key_bindings b WHERE b.api_key_id = k.id)
               AND $identityUnreferenced
            """.trimIndent()
        return jdbc.update(sql, emptyMap<String, Any?>())
    }

    /**
     * Deactivated `service` rows nothing references any more — no key (as its identity or,
     * defensively, as a `created_by`), and every live FK column's table clear. Revoked keys'
     * identities left behind by the key pass above (referenced somewhere) stay, exactly as A17
     * says: revocation is the end state, the purge only sweeps what nothing remembers.
     */
    private fun purgeIdentities(referencing: List<Pair<String, String>>): Int {
        val unreferenced =
            (referencing + ("api_keys" to "created_by"))
                .distinct()
                .joinToString("\n      AND ") { (table, column) ->
                    "NOT EXISTS (SELECT 1 FROM $table r WHERE r.$column = u.id)"
                }
        val sql =
            """
            DELETE FROM users u
             WHERE u.kind = 'service'
               AND u.is_active = FALSE
               AND $unreferenced
            """.trimIndent()
        return jdbc.update(sql, emptyMap<String, Any?>())
    }
}
