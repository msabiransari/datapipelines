package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The retention sweep's keys purge (keys v2 A17/B5, #233) against the REAL schema — the
 * [AuthRepositoriesIntegrationTest] shape: the shipped migrations off [SharedPostgres], the
 * [KeyRetentionPurge] bean's class over a plain [NamedParameterJdbcTemplate].
 *
 * What B5 asks for, and what each test holds the purge to:
 * - the "nothing references" predicate is the LIVE catalog's FK list — printed here from the
 *   test database, with the rows the story names asserted present, so a migration that adds
 *   or removes an FK is visible in this suite's output the day it lands;
 * - a revoked key whose identity is STILL referenced (an audit row about it — the trail the
 *   product really writes when a key does something) STAYS, key and identity together;
 * - a revoked key whose identity nothing references is purged WITH its `service` identity —
 *   the pair is one unit;
 * - a LIVE key is never touched, whoever references what.
 *
 * Falsified at birth (233c): restoring the first draft's `ccu.*` projection — which compared
 * `users.id` to itself and purged nothing — turns the purged-rows assertions red.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeyRetentionPurgeIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var purge: KeyRetentionPurge

    private val person = UUID.randomUUID()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        purge = KeyRetentionPurge(jdbc)
    }

    @BeforeEach
    fun setUp() {
        // The cleaning rule: this suite truncates the one root it seeds (users) and re-seeds
        // the workspace the key rows pin.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        DefaultWorkspaceFixture.ensure(jdbc)
        jdbc.jdbcTemplate.execute(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                " ('$person', 'purge-person@acme.test', 'Purge Person', 'test', 'purge-person', TRUE, FALSE)",
        )
    }

    @Test
    fun `the purge's predicate is the live catalog's FK list - printed`() {
        // The derivation the purge runs (KeyRetentionPurge.foreignKeysToUsers is private by
        // design — this is the same query, run for the record): the REFERENCING side projected,
        // the referenced side named in WHERE.
        val referencing =
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
        println("event=key_purge.catalog referencing=${referencing.joinToString(" ") { "${it.first}.${it.second}" }}")

        // The rows the B5 story and the purge's own predicate depend on — every column of
        // `api_keys` itself, and the execution trail.
        referencing.contains("api_keys" to "user_id") shouldBe true
        referencing.contains("api_keys" to "created_by") shouldBe true
        referencing.contains("pipeline_executions" to "executed_by") shouldBe true
    }

    @Test
    fun `a referenced revoked key stays, an unreferenced one is purged with its identity, a live key is never touched`() {
        val auditedIdentity = seedIdentity(active = false)
        val freeIdentity = seedIdentity(active = false)
        val liveIdentity = seedIdentity(active = true)
        val auditedKey = seedKey("dpk_PURGEREVO1", auditedIdentity, revoked = true)
        val freeKey = seedKey("dpk_PURGEFREE1", freeIdentity, revoked = true)
        val liveKey = seedKey("dpk_PURGELIVE1", liveIdentity, revoked = false)
        // The trail the product writes about a key's identity: one audit row is enough to hold
        // the identity — and with it the revoked key — exactly as A17's "once nothing
        // references them any more" requires.
        jdbc.jdbcTemplate.execute(
            "INSERT INTO audit_log (event, user_id) VALUES ('auth.api_key.revoked', '$auditedIdentity')",
        )

        val result = purge.purgeOnce()

        // The unreferenced pair goes; the audited pair and the live key stay.
        result shouldBe KeyRetentionPurge.Result(keysPurged = 1, identitiesPurged = 1)
        keyExists(freeKey) shouldBe false
        identityExists(freeIdentity) shouldBe false
        keyExists(auditedKey) shouldBe true
        identityExists(auditedIdentity) shouldBe true
        keyExists(liveKey) shouldBe true
        identityExists(liveIdentity) shouldBe true
    }

    @Test
    fun `an idempotent second sweep purges nothing - and then releases what an audit row held once it is gone`() {
        val heldIdentity = seedIdentity(active = false)
        val heldKey = seedKey("dpk_PURGEHELD1", heldIdentity, revoked = true)
        jdbc.jdbcTemplate.execute(
            "INSERT INTO audit_log (event, user_id) VALUES ('auth.api_key.revoked', '$heldIdentity')",
        )
        purge.purgeOnce() shouldBe KeyRetentionPurge.Result(keysPurged = 0, identitiesPurged = 0)

        // The trail ages out (retention deletes the audit row); the NEXT sweep releases the pair.
        jdbc.jdbcTemplate.execute("DELETE FROM audit_log WHERE user_id = '$heldIdentity'")
        purge.purgeOnce() shouldBe KeyRetentionPurge.Result(keysPurged = 1, identitiesPurged = 1)
        keyExists(heldKey) shouldBe false
        identityExists(heldIdentity) shouldBe false
    }

    // ---------------------------------------------------------------- fixtures

    /** A `service` identity row, as V34/V37 build them. [active] is the row's `is_active`. */
    private fun seedIdentity(active: Boolean): UUID {
        val id = UUID.randomUUID()
        jdbc.jdbcTemplate.execute(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES" +
                " ('$id', '$id@keys.invalid', 'purge-identity', 'key', 'subject-$id', $active, FALSE, 'service')",
        )
        return id
    }

    /** One api_keys row in the post-V37 shape: kind `mcp`, a role for live keys, NULL on revoked. */
    private fun seedKey(
        id: String,
        identity: UUID,
        revoked: Boolean,
    ): String {
        jdbc.jdbcTemplate.execute(
            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role, is_revoked) VALUES" +
                " ('$id', '$identity', '$person', '$id', 'h', '${DefaultWorkspaceFixture.ID}', 'mcp'," +
                " ${if (revoked) "NULL, TRUE" else "'author', FALSE"})",
        )
        return id
    }

    private fun keyExists(id: String): Boolean =
        jdbc
            .query("SELECT count(*) FROM api_keys WHERE id = '$id'", emptyMap<String, Any?>()) { rs, _ -> rs.getLong(1) }
            .single() == 1L

    private fun identityExists(id: UUID): Boolean =
        jdbc
            .query("SELECT count(*) FROM users WHERE id = '$id'", emptyMap<String, Any?>()) { rs, _ -> rs.getLong(1) }
            .single() == 1L
}
