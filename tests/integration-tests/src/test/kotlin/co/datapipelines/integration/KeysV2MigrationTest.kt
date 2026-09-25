package co.datapipelines.integration

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * V37 (`keys_v2_robot_members`) applied to a **populated** pre-V37 database, asserted at the row
 * level — the keys-v2 B4 dry run with counts, in the [WorkspaceRolesMigrationTest] shape: the
 * shipped scripts are driven directly, V1 through V34, then pre-V37 rows in the old shape (the
 * login mint: `minted_at_login = TRUE`, `kind = 'user'`, `role IS NULL`, `user_id = the member`),
 * then V37 — and every row the migration produces is named here.
 *
 * What keys v2 (A13–A19, B4) promises, and what each test holds it to:
 * - an owner whose membership is author | promoter | workspace_admin keeps a LIVE key, now
 *   IDENTITY-BACKED: `role` set from the membership, `user_id` moved to a fresh `service`
 *   identity (provider 'key', subject = the key id, `@keys.invalid`), `created_by` keeping the
 *   owner — the person who may revoke it and whose removal revokes it (A17);
 * - an owner seeded `is_active = FALSE` converts exactly the same (A20, owner ruling
 *   2026-09-25: the CTE reads the membership, never `users.is_active`) — the converted key is
 *   then DEAD at request time until the owner is reactivated, which is the validation path's
 *   judgment (`ApiKeyService.liveActor`), never the migration's;
 * - a viewer's key, and the key of an owner with NO membership (the super-admin case #225
 *   turned on), are REVOKED, never upgraded and never guessed;
 * - an owner's SUPER-ADMIN flag alone grants nothing: only the explicit membership counts;
 * - `minted_at_login` and the V31 one-live-key index are gone; a revoked pre-v2 login key is
 *   left untouched (inert, `role IS NULL`, admitted by the CHECK's revoked arm);
 * - duplicate live names in one workspace are disambiguated and `(workspace_id, name)` is then
 *   unique for live keys (A18).
 *
 * The migration's own NOTICE counts (converted / revoked / renamed) are the dry run's output;
 * the assertions below are the counts' meaning.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KeysV2MigrationTest {
    private val author = UUID.randomUUID()
    private val promoter = UUID.randomUUID()
    private val admin = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val superAdmin = UUID.randomUUID()
    private val dupOwnerA = UUID.randomUUID()
    private val dupOwnerB = UUID.randomUUID()
    private val deadOwner = UUID.randomUUID()
    private val ws = UUID.randomUUID()
    private val otherWs = UUID.randomUUID()

    private val authorKey = "dpk_V37AUTHOR01"
    private val promoterKey = "dpk_V37PROMOT01"
    private val adminKey = "dpk_V37ADMIN001"
    private val viewerKey = "dpk_V37VIEWER01"
    private val superKey = "dpk_V37SUPER001"
    private val alreadyRevokedKey = "dpk_V37DEAD001"
    private val dupKept = "dpk_V37DUPNEW1"
    private val dupRenamed = "dpk_V37DUPOLD1"
    private val deadOwnerKey = "dpk_V37INACTIV1"

    private val migrated by lazy {
        MIGRATION_PATHS_PRE_V37.forEach { execute(repoFile(it).readText()) }
        seedPreV37Rows()
        execute(repoFile(V37_PATH).readText())
        true
    }

    // ---------------------------------------------------------------- the conversion (B4)

    @Test
    @Order(1)
    fun `a member's login key keeps living as an identity-backed key with the member's role`() {
        migrated shouldBe true
        keyRow(authorKey) shouldBe listOf("author", "false", author.toString())
        keyRow(promoterKey) shouldBe listOf("promoter", "false", promoter.toString())
        keyRow(adminKey) shouldBe listOf("workspace_admin", "false", admin.toString())
        // user_id now names a `service` identity built like V34's: provider 'key', subject the
        // key id, the RFC 2606 address, no admin flag.
        identityRow(authorKey) shouldBe listOf("service", "key", authorKey, "${authorKey.lowercase()}@keys.invalid", "true")
    }

    @Test
    @Order(2)
    fun `an inactive owner's key still converts - liveness is judged at request time, not migrated (A20)`() {
        migrated shouldBe true
        // The conversion reads the owner's MEMBERSHIP, never `users.is_active` (A20, owner
        // ruling 2026-09-25): the key is identity-backed and LIVE in the row world — and
        // refused at validation (`auth.principal_deactivated`) until the owner is
        // reactivated. That refusal is the request path's judgment (ApiKeyService.liveActor),
        // proven over the real filter chain by the DeactivationSweepTest
        // `mcp-key:deactivated-owner` arm and McpEntryPointChecksE2eTest's
        // deactivate/reactivate case. Reactivation restores the key: nothing was revoked.
        keyRow(deadOwnerKey) shouldBe listOf("author", "false", deadOwner.toString())
        identityRow(deadOwnerKey) shouldBe listOf("service", "key", deadOwnerKey, "${deadOwnerKey.lowercase()}@keys.invalid", "true")
    }

    @Test
    @Order(3)
    fun `a viewer's key and a membership-less owner's key are revoked, never upgraded`() {
        migrated shouldBe true
        keyRow(viewerKey)[1] shouldBe "true"
        keyRow(superKey)[1] shouldBe "true"
        // Revoked, with NO role invented for them (the CHECK's revoked arm admits NULL).
        keyRow(viewerKey)[0] shouldBe "NULL"
        keyRow(superKey)[0] shouldBe "NULL"
        // The super admin's flag granted nothing: the ruling is about the membership (B4).
    }

    @Test
    @Order(4)
    fun `an already-revoked login key is left untouched - history is not rewritten`() {
        migrated shouldBe true
        keyRow(alreadyRevokedKey) shouldBe listOf("NULL", "true", superAdmin.toString())
        // No identity was created for it: identities are for LIVE keys only.
        query("SELECT count(*) FROM users WHERE provider = 'key' AND provider_subject = '$alreadyRevokedKey'") { it.getLong(1) }
            .single() shouldBe 0L
    }

    // ---------------------------------------------------------------- the schema (A15/A18/A19)

    @Test
    @Order(5)
    fun `the login mint's column and index are gone, and kind user is renamed mcp everywhere`() {
        migrated shouldBe true
        columnsOf("api_keys") shouldNotContain "minted_at_login"
        indexesOf("api_keys") shouldNotContain "api_keys_one_live_user_key"
        query("SELECT count(*) FROM api_keys WHERE kind = 'user'") { it.getLong(1) }.single() shouldBe 0L
        query("SELECT column_default FROM information_schema.columns WHERE table_name = 'api_keys' AND column_name = 'kind'") {
            it.getString(1)
        }.single() shouldBe "'mcp'::text"
    }

    @Test
    @Order(6)
    fun `duplicate live names are disambiguated and the workspace-name uniqueness holds for live keys`() {
        migrated shouldBe true
        // The newest keeps its name; the older gains the key id's tail. The dup rows are
        // pre-R3 ON-DEMAND keys (minted_at_login FALSE, the V31 default) — the widened V37
        // conversion covers them too: live, identity-backed, with the owner's role.
        nameOf(dupKept) shouldBe "shared name"
        nameOf(dupRenamed) shouldBe "shared name (old1)"
        keyRow(dupKept) shouldBe listOf("author", "false", dupOwnerA.toString())
        // The unique index refuses a third live row of the same name in the same workspace.
        val refusal =
            runCatching {
                execute(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                        " VALUES ('dpk_V37DUPNEW2', '$dupOwnerA', '$dupOwnerA', 'shared name', 'h', '$ws', 'mcp', 'author')",
                )
            }.exceptionOrNull()
        refusal?.message.orEmpty().contains("uq_api_keys_live_workspace_name") shouldBe true
        // A REVOKED row may repeat a live name: the index is partial (A18's "for live keys").
        execute(
            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role, is_revoked)" +
                " VALUES ('dpk_V37DUPREV1', '$author', '$author', 'shared name', 'h', '$ws', 'mcp', 'author', TRUE)",
        )
    }

    // ---------------------------------------------------------------- fixtures

    private fun seedPreV37Rows() {
        execute(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$author', 'author@acme.test', 'Author', 'test', 'author-sub', TRUE, FALSE),
                ('$promoter', 'promoter@acme.test', 'Promoter', 'test', 'promoter-sub', TRUE, FALSE),
                ('$admin', 'admin@acme.test', 'Admin', 'test', 'admin-sub', TRUE, FALSE),
                ('$viewer', 'viewer@acme.test', 'Viewer', 'test', 'viewer-sub', TRUE, FALSE),
                ('$superAdmin', 'boss@acme.test', 'Boss', 'test', 'boss-sub', TRUE, TRUE),
                ('$dupOwnerA', 'dupa@acme.test', 'Dup A', 'test', 'dupa-sub', TRUE, FALSE),
                ('$dupOwnerB', 'dupb@acme.test', 'Dup B', 'test', 'dupb-sub', TRUE, FALSE),
                ('$deadOwner', 'deadowner@acme.test', 'Dead Owner', 'test', 'deadowner-sub', FALSE, FALSE)
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO workspaces (id, name, display_name) VALUES
                ('$ws', 'acme', 'Acme'),
                ('$otherWs', 'other', 'Other')
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$ws', '$author', 'author'),
                ('$ws', '$promoter', 'promoter'),
                ('$ws', '$admin', 'workspace_admin'),
                ('$ws', '$viewer', 'viewer'),
                ('$ws', '$dupOwnerA', 'author'),
                ('$ws', '$dupOwnerB', 'author'),
                ('$ws', '$deadOwner', 'author'),
                ('$otherWs', '$superAdmin', 'workspace_admin')
            """.trimIndent(),
        )
        // The super admin's LOGIN key lives in `acme`, where they hold NO membership (#225's
        // shape); their membership is in another workspace, which grants nothing here.
        seedLoginKey(authorKey, author, ws)
        seedLoginKey(promoterKey, promoter, ws)
        seedLoginKey(adminKey, admin, ws)
        seedLoginKey(viewerKey, viewer, ws)
        seedLoginKey(superKey, superAdmin, ws)
        seedLoginKey(alreadyRevokedKey, superAdmin, ws, revoked = true)
        seedLoginKey(deadOwnerKey, deadOwner, ws)
        // The duplicate live names: same workspace, same name, different created_at.
        execute(
            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role, is_revoked, created_at)" +
                " VALUES ('$dupKept', '$dupOwnerA', '$dupOwnerA', 'shared name', 'h', '$ws', 'user', NULL, FALSE, '2026-09-01T00:00:00Z')",
        )
        execute(
            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role, is_revoked, created_at)" +
                " VALUES ('$dupRenamed', '$dupOwnerB', '$dupOwnerB', 'shared name', 'h', '$ws', 'user', NULL, FALSE," +
                " '2026-08-01T00:00:00Z')",
        )
    }

    /** A pre-V37 login-minted key, in the shape V31/V34 left: kind `user`, no role, owner = member. */
    private fun seedLoginKey(
        id: String,
        ownerId: UUID,
        workspaceId: UUID,
        revoked: Boolean = false,
    ) {
        execute(
            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role, is_revoked, minted_at_login)" +
                " VALUES ('$id', '$ownerId', '$ownerId', 'mcp/acme', 'h', '$workspaceId', 'user', NULL, $revoked, TRUE)",
        )
    }

    /** role | is_revoked | created_by, as text, for one key row. */
    private fun keyRow(id: String): List<String> =
        query(
            "SELECT COALESCE(role, 'NULL'), is_revoked::text, created_by::text FROM api_keys WHERE id = '$id'",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3)) }.single()

    /** kind | provider | provider_subject | email | is_active of the identity a converted key acts as. */
    private fun identityRow(keyId: String): List<String> =
        query(
            "SELECT u.kind, u.provider, u.provider_subject, u.email, u.is_active::text FROM users u" +
                " JOIN api_keys k ON k.user_id = u.id WHERE k.id = '$keyId'",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5)) }.single()

    private fun nameOf(id: String): String = query("SELECT name FROM api_keys WHERE id = '$id'") { it.getString(1) }.single()

    private fun columnsOf(table: String): List<String> =
        query("SELECT column_name FROM information_schema.columns WHERE table_name = '$table'") { it.getString(1) }

    private fun indexesOf(table: String): List<String> =
        query("SELECT indexname FROM pg_indexes WHERE tablename = '$table'") { it.getString(1) }

    private fun execute(sql: String) {
        connection().use { it.createStatement().use { statement -> statement.execute(sql) } }
    }

    private fun <T> query(
        sql: String,
        row: (ResultSet) -> T,
    ): List<T> =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add(row(rs)) }
                }
            }
        }

    private fun connection(): Connection = DriverManager.getConnection(db.jdbcUrl, db.username, db.password)

    private companion object {
        const val MIGRATION_DIR = "modules/app/src/main/resources/db/migration"
        const val V37_PATH = "$MIGRATION_DIR/V37__keys_v2_robot_members.sql"

        /** V1 through V34, in Flyway's own order — spelled out, never globbed (the V29 test's rule). */
        val MIGRATION_PATHS_PRE_V37 =
            listOf(
                "V1__initial_schema.sql",
                "V2__datasource_introspection_include_schemas.sql",
                "V3__execution_lineage.sql",
                "V4__workspaces_rekey.sql",
                "V5__local_password_auth.sql",
                "V6__version_lifecycle.sql",
                "V7__hierarchical_template_names.sql",
                "V8__typed_templates.sql",
                "V9__datasource_last_test_outcome.sql",
                "V10__datasource_credential_key_version.sql",
                "V11__published_endpoints.sql",
                "V12__folder_required.sql",
                "V13__datasource_credential_kind.sql",
                "V14__lake_dialect.sql",
                "V15__lake_tables.sql",
                "V16__lake_template_dialect.sql",
                "V17__api_key_kind_server.sql",
                "V18__draft_first_create.sql",
                "V19__version_lifecycle.sql",
                "V20__version_write_surface.sql",
                "V21__execution_heartbeat.sql",
                "V22__lake_table_view_errors.sql",
                "V23__rbac_core.sql",
                "V24__workspace_invitations.sql",
                "V25__learned_facts.sql",
                "V26__learned_facts_workspace_rule_refs.sql",
                "V27__mail_sends.sql",
                "V28__pipeline_check_runs.sql",
                "V29__workspace_roles.sql",
                "V30__executions_executed_by.sql",
                "V31__login_minted_mcp_keys.sql",
                "V32__mcp_key_sealed_copy_cleared.sql",
                "V33__transform_template_blocks.sql",
                "V34__key_identities_and_roles.sql",
            ).map { "$MIGRATION_DIR/$it" }

        /** Walks up from the working directory — the same locator the module fixtures use. */
        fun repoFile(relativePath: String): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val candidate = File(dir, relativePath)
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            error("$relativePath not found walking up from ${File("").absolutePath}")
        }

        /**
         * A scratch database: this suite builds the schema PART-WAY on purpose (V1–V34, pre-V37
         * rows, then V37) — the V37 boundary is the subject.
         */
        val db = SharedE2e.scratchDatabase("pre_v35_keys_v2")
    }
}
