package co.datapipelines.integration

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * V23 (`rbac_core`) applied to a **populated** pre-V23 database, asserted at the row level
 * (the 016 rule: migration proofs assert rows, never just API behaviour).
 *
 * `FlywayMigrationIntegrationTest` proves Flyway applies V1→V23 cleanly on an EMPTY database;
 * it cannot prove the backfills, because on a booted app every row is written *after* V23. So
 * this class drives the shipped scripts directly — V1 through V22, then pre-V23 rows in the
 * old shape, then V23 — exactly as `WorkspacesRekeyMigrationTest` does for the V4 boundary.
 *
 * What D-R14 promises, and what each test below holds it to:
 * - `owner → admin + author`, `member → author` — nobody is demoted, because a `member` had
 *   the whole authoring surface the day before (session capability was `JwtService.scopesFor`,
 *   which handed every non-admin user `author` globally);
 * - a workspace-bound datasource is granted to its own workspace and owned by it;
 * - a **global** datasource is granted to EVERY existing workspace, so nothing that was
 *   visible yesterday stopped being visible, and owns none;
 * - `api_keys.scopes` loses `admin`, the scope keys may no longer hold (O-2).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RbacCoreMigrationTest {
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private val wsAcme = UUID.randomUUID()
    private val wsGlobex = UUID.randomUUID()

    @BeforeAll
    fun migratePopulatedDatabase() {
        MIGRATION_PATHS_PRE_V23.forEach { execute(repoFile(it).readText()) }
        seedPreV23Rows()
        execute(repoFile(V23_PATH).readText())
    }

    // ---------------------------------------------------------------- memberships (D-R14)

    @Test
    fun `owner becomes a workspace ADMIN, and therefore an author`() {
        flagsOf(wsAcme, alice) shouldBe "t|f|t"
    }

    @Test
    fun `member becomes an AUTHOR - nobody is demoted by the migration`() {
        // The pre-V23 world gave every non-admin session `author` GLOBALLY, so `member →
        // author` preserves exactly what worked the day before. Anything less would be a
        // silent capability removal on upgrade, which is the one thing a migration must not do.
        flagsOf(wsGlobex, bob) shouldBe "t|f|f"
    }

    @Test
    fun `the role column and its CHECK are gone, and the admin-implies-author CHECK replaced them`() {
        columnsOf("workspace_members") shouldContainExactlyInAnyOrder
            listOf("workspace_id", "user_id", "author", "promoter", "admin", "joined_at")

        constraintsOf("workspace_members") shouldContainExactly listOf("chk_workspace_member_admin_authors")
    }

    // ---------------------------------------------------------------- deactivation (D-R10)

    @Test
    fun `workspaces gain the deactivation columns, and every existing workspace is ACTIVE`() {
        columnsOf("workspaces").contains("deactivated_at") shouldBe true
        columnsOf("workspaces").contains("deactivated_by") shouldBe true

        // A migration that deactivated anything would take a working deployment offline.
        query("SELECT COUNT(*) FROM workspaces WHERE deactivated_at IS NOT NULL") { it.getInt(1) } shouldContainExactly listOf(0)
    }

    @Test
    fun `the migration does NOT seed demo - the boot seeder owns it, because it can seed CONTENT`() {
        // The first cut of V23 inserted the row, which made `DemoWorkspaceSeeder` dead code:
        // it imports the example content only when it CREATES the workspace, so every
        // deployment got an empty `demo` and nothing said why. One authority, and it is the
        // half that can do both.
        query("SELECT COUNT(*) FROM workspaces WHERE name = 'demo'") { it.getInt(1) } shouldContainExactly listOf(0)
    }

    // ---------------------------------------------------------------- datasource grants (D-R7)

    @Test
    fun `a workspace-bound datasource is granted to its own workspace and owned by it`() {
        grantsOf("acme-db") shouldContainExactly listOf("acme")
        ownerOf("acme-db") shouldBe wsAcme.toString()
    }

    @Test
    fun `a GLOBAL datasource is granted to EVERY existing workspace, and owns none`() {
        // "Global" is gone (D-R7), and this is the whole reason the migration can be safe:
        // a datasource everybody could see becomes a datasource everybody has a grant to, so
        // nothing that worked yesterday stops today.
        grantsOf("shared-db") shouldContainExactlyInAnyOrder listOf("default", "acme", "globex")
        ownerOf("shared-db") shouldBe null
    }

    @Test
    fun `the grant records WHO granted it - the datasource's own creator, not an invented actor`() {
        query("SELECT DISTINCT granted_by::TEXT FROM datasource_workspaces") {
            it.getString(1)
        } shouldContainExactly listOf(alice.toString())
    }

    @Test
    fun `datasources lose workspace_id - ownership and visibility are two things now`() {
        columnsOf("datasources").contains("workspace_id") shouldBe false
        columnsOf("datasources").contains("owner_workspace_id") shouldBe true
    }

    // ---------------------------------------------------------------- keys (O-2)

    @Test
    fun `admin is stripped from every key's scopes, and the lower scopes survive`() {
        query("SELECT id || '|' || array_to_string(scopes, ',') FROM api_keys ORDER BY id") {
            it.getString(1)
        } shouldContainExactly listOf("dpk_ADMINKEY|read,author", "dpk_READKEY|read")
    }

    @Test
    fun `users keeps is_admin - it is the ONE global capability left, and it means super admin`() {
        // D-R1 says "users.scopes goes away". There is no such column and never was: the only
        // scopes array in the schema is `api_keys.scopes`. What carried global session
        // capability was Kotlin, not SQL — so this migration has nothing to drop here, and
        // `is_admin` must still be standing.
        columnsOf("users").contains("is_admin") shouldBe true
        columnsOf("users").contains("scopes") shouldBe false
    }

    // ---------------------------------------------------------------- fixtures

    private fun seedPreV23Rows() {
        execute(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_admin) VALUES
                ('$alice', 'alice@acme.test', 'Alice', 'test', 'alice-sub', TRUE),
                ('$bob', 'bob@globex.test', 'Bob', 'test', 'bob-sub', FALSE),
                ('$carol', 'carol@acme.test', 'Carol', 'test', 'carol-sub', FALSE)
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO workspaces (id, name, display_name) VALUES
                ('$wsAcme', 'acme', 'Acme'), ('$wsGlobex', 'globex', 'Globex')
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$wsAcme', '$alice', 'owner'),
                ('$wsGlobex', '$bob', 'member'),
                ('$wsAcme', '$carol', 'member')
            """.trimIndent(),
        )
        // One workspace-bound datasource and one GLOBAL one — the two halves of D-R14.
        execute(
            """
            INSERT INTO datasources (name, display_name, dialect, jdbc_url, username,
                                     credential_encrypted, created_by, workspace_id) VALUES
                ('acme-db', 'Acme DB', 'POSTGRES', 'jdbc:postgresql://h/acme', 'u', 'x'::bytea, '$alice', '$wsAcme'),
                ('shared-db', 'Shared DB', 'POSTGRES', 'jdbc:postgresql://h/shared', 'u', 'x'::bytea, '$alice', NULL)
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id) VALUES
                ('dpk_ADMINKEY', '$alice', 'admin key', 'h', ARRAY['read','author','admin'], '$wsAcme'),
                ('dpk_READKEY', '$bob', 'read key', 'h', ARRAY['read'], '$wsGlobex')
            """.trimIndent(),
        )
    }

    private fun flagsOf(
        workspaceId: UUID,
        userId: UUID,
    ): String =
        query(
            "SELECT author::TEXT || '|' || promoter::TEXT || '|' || admin::TEXT FROM workspace_members" +
                " WHERE workspace_id = '$workspaceId' AND user_id = '$userId'",
        ) { it.getString(1) }.single()

    private fun grantsOf(datasource: String): List<String> =
        query(
            "SELECT w.name FROM datasource_workspaces g JOIN workspaces w ON w.id = g.workspace_id" +
                " WHERE g.datasource_name = '$datasource'",
        ) { it.getString(1) }

    private fun ownerOf(datasource: String): String? =
        query("SELECT owner_workspace_id::TEXT FROM datasources WHERE name = '$datasource'") { it.getString(1) }.single()

    private fun columnsOf(table: String): List<String> =
        query("SELECT column_name FROM information_schema.columns WHERE table_name = '$table'") { it.getString(1) }

    private fun constraintsOf(table: String): List<String> =
        query(
            "SELECT constraint_name FROM information_schema.table_constraints" +
                " WHERE table_name = '$table' AND constraint_type = 'CHECK' AND constraint_name LIKE 'chk_%'",
        ) { it.getString(1) }

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
        const val V23_PATH = "$MIGRATION_DIR/V23__rbac_core.sql"

        /**
         * V1 through V22, in Flyway's own order. Spelled out rather than globbed: a directory
         * listing sorts `V10` before `V2`, and a migration chain applied out of order fails in
         * a way that looks like a defect in the migration under test.
         */
        val MIGRATION_PATHS_PRE_V23 =
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
         * A scratch database: this suite builds the schema PART-WAY on purpose (V1–V22,
         * pre-V23 rows, then V23) and must not see the fully-migrated database the other
         * suites run against — the V23 boundary is the subject.
         */
        val db = SharedE2e.scratchDatabase("pre_v23_rbac")
    }
}
