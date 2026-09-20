package co.datapipelines.integration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import java.sql.SQLException
import java.util.UUID

/**
 * V29 (`workspace_roles`) and V30 (`executions_executed_by`) applied to a **populated**
 * pre-V29 database, asserted at the row level (the 016 rule: migration proofs assert rows,
 * never just API behaviour) — and then V29's documented DOWN path applied on top, so the
 * handback's "up-and-down" claim is a measured fact rather than a comment in a SQL file.
 *
 * `FlywayMigrationIntegrationTest` proves Flyway applies V1→V30 cleanly on an EMPTY database;
 * it cannot prove the backfills, because on a booted app every row is written *after* V30. So
 * this class drives the shipped scripts directly — V1 through V28, then pre-V29 rows in the
 * old shape (the three flags, `triggered_by`), then V29 and V30 — the `RbacCoreMigrationTest`
 * shape for the V23 boundary.
 *
 * What the roles record's §5 promises, and what each test holds it to:
 * - the precedence `admin → workspace_admin, else promoter → promoter, else author → author,
 *   else viewer` — including the row that motivated the change, author+promoter → PROMOTER;
 * - invitations migrate by the same rule (D20);
 * - the value set is a CHECK on both tables, so a fifth role cannot be stored;
 * - `triggered_by` → `executed_by` keeps every actor; `executed_by_key_kind` is backfilled
 *   from the trigger (ENDPOINT → `endpoint`, MCP → `user`, everything else NULL);
 * - the down path restores the three flags losslessly for every row V29 produced.
 *
 * Ordered on purpose: the down-path test mutates the schema the others read.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WorkspaceRolesMigrationTest {
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private val dave = UUID.randomUUID()
    private val ws = UUID.randomUUID()
    private val pipeline = UUID.randomUUID()
    private val uiRun = UUID.randomUUID()
    private val mcpRun = UUID.randomUUID()
    private val endpointRun = UUID.randomUUID()
    private val restRun = UUID.randomUUID()

    private val migrated by lazy {
        MIGRATION_PATHS_PRE_V29.forEach { execute(repoFile(it).readText()) }
        seedPreV29Rows()
        execute(repoFile(V29_PATH).readText())
        execute(repoFile(V30_PATH).readText())
        true
    }

    // ---------------------------------------------------------------- V29 up

    @Test
    @Order(1)
    fun `the three flags fold into ONE role by the record's precedence - author+promoter becomes PROMOTER`() {
        migrated shouldBe true
        roleOf(alice) shouldBe "workspace_admin" // admin + author
        roleOf(bob) shouldBe "author" // author only
        roleOf(carol) shouldBe "promoter" // author + promoter: the combination the change retires
        roleOf(dave) shouldBe "viewer" // no flag at all
    }

    @Test
    @Order(2)
    fun `invitations migrate by the same rule (D20)`() {
        migrated shouldBe true
        query("SELECT email || '=' || role FROM workspace_invitations ORDER BY email") { it.getString(1) } shouldContainExactly
            listOf("ghost-admin@acme.test=workspace_admin", "ghost-promoter@acme.test=promoter", "ghost-viewer@acme.test=viewer")
    }

    @Test
    @Order(3)
    fun `the flag columns and their CHECK are gone, the role column and its CHECK replaced them, on both tables`() {
        migrated shouldBe true
        columnsOf("workspace_members") shouldContainExactlyInAnyOrder listOf("workspace_id", "user_id", "joined_at", "role")
        constraintsOf("workspace_members") shouldContainExactly listOf("chk_workspace_member_role")
        columnsOf("workspace_invitations") shouldContainExactlyInAnyOrder
            listOf("workspace_id", "email", "invited_by", "invited_at", "role")
        constraintsOf("workspace_invitations") shouldContainExactlyInAnyOrder
            listOf("chk_workspace_invitation_email_lower", "chk_workspace_invitation_role")
        // The last-admin counter's index follows the column it counts — same name, new predicate.
        query("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_workspace_members_admins'") { it.getString(1) }
            .single() shouldBe
            "CREATE INDEX idx_workspace_members_admins ON public.workspace_members USING btree (workspace_id) " +
            "WHERE (role = 'workspace_admin'::text)"
    }

    @Test
    @Order(4)
    fun `a fifth role cannot be stored - the value set is the database's`() {
        migrated shouldBe true
        val refusal =
            shouldThrow<SQLException> {
                execute("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$ws', '$dave', 'owner')")
            }
        refusal.message.orEmpty().contains("chk_workspace_member_role") shouldBe true
        // The unique key still names the pair; the refused insert wrote nothing.
        roleOf(dave) shouldBe "viewer"
    }

    // ---------------------------------------------------------------- V30 up

    @Test
    @Order(5)
    fun `triggered_by becomes executed_by with every actor kept, and the key kind is backfilled from the trigger`() {
        migrated shouldBe true
        columnsOf("pipeline_executions").contains("triggered_by") shouldBe false
        columnsOf("pipeline_executions").contains("executed_by") shouldBe true
        query(
            "SELECT execution_id::text || '=' || executed_by::text || '|' || COALESCE(executed_by_key_kind, 'NULL')" +
                " FROM pipeline_executions ORDER BY triggered_via",
        ) { it.getString(1) } shouldContainExactlyInAnyOrder
            listOf(
                "$uiRun=$alice|NULL",
                "$restRun=$bob|NULL",
                "$mcpRun=$bob|user",
                "$endpointRun=$alice|endpoint",
            )
        // The CHECK admits the three kinds and NULL, nothing else.
        val refusal =
            shouldThrow<SQLException> {
                execute("UPDATE pipeline_executions SET executed_by_key_kind = 'robot' WHERE execution_id = '$restRun'")
            }
        refusal.message.orEmpty().contains("chk_executions_executed_by_key_kind") shouldBe true
    }

    // ---------------------------------------------------------------- V29 down

    @Test
    @Order(6)
    fun `the documented down path restores the three flags losslessly for every row V29 produced`() {
        migrated shouldBe true
        execute(V29_DOWN)

        columnsOf("workspace_members") shouldContainExactlyInAnyOrder
            listOf("workspace_id", "user_id", "joined_at", "author", "promoter", "admin")
        constraintsOf("workspace_members") shouldContainExactly listOf("chk_workspace_member_admin_authors")
        // Each role maps back to exactly one flag triple. The one row that cannot come back is
        // the author+promoter combination V29 exists to retire: carol is a promoter now.
        flagsOf(alice) shouldBe "true|false|true"
        flagsOf(bob) shouldBe "true|false|false"
        flagsOf(carol) shouldBe "false|true|false"
        flagsOf(dave) shouldBe "false|false|false"
        query("SELECT email || '=' || author::text || '|' || promoter::text || '|' || admin::text FROM workspace_invitations ORDER BY email") {
            it.getString(1)
        } shouldContainExactly
            listOf(
                "ghost-admin@acme.test=true|false|true",
                "ghost-promoter@acme.test=false|true|false",
                "ghost-viewer@acme.test=false|false|false",
            )
    }

    // ---------------------------------------------------------------- fixtures

    private fun seedPreV29Rows() {
        execute(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_admin) VALUES
                ('$alice', 'alice@acme.test', 'Alice', 'test', 'alice-sub', FALSE),
                ('$bob', 'bob@acme.test', 'Bob', 'test', 'bob-sub', FALSE),
                ('$carol', 'carol@acme.test', 'Carol', 'test', 'carol-sub', FALSE),
                ('$dave', 'dave@acme.test', 'Dave', 'test', 'dave-sub', FALSE)
            """.trimIndent(),
        )
        execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$ws', 'acme', 'Acme')")
        // One row per flag shape the V23 world could hold — including the author+promoter
        // combination the change retires, which is the row whose precedence matters.
        execute(
            """
            INSERT INTO workspace_members (workspace_id, user_id, author, promoter, admin) VALUES
                ('$ws', '$alice', TRUE, FALSE, TRUE),
                ('$ws', '$bob', TRUE, FALSE, FALSE),
                ('$ws', '$carol', TRUE, TRUE, FALSE),
                ('$ws', '$dave', FALSE, FALSE, FALSE)
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO workspace_invitations (workspace_id, email, author, promoter, admin, invited_by) VALUES
                ('$ws', 'ghost-admin@acme.test', TRUE, FALSE, TRUE, '$alice'),
                ('$ws', 'ghost-promoter@acme.test', TRUE, TRUE, FALSE, '$alice'),
                ('$ws', 'ghost-viewer@acme.test', FALSE, FALSE, FALSE, '$alice')
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version)
            VALUES ('$pipeline', 'acme/probe', 'Probe', '', '$alice', '$ws', NULL)
            """.trimIndent(),
        )
        // `fk_executions_pipeline_version` (V1): an execution names a stored version.
        execute(
            """
            INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by)
            VALUES ('$pipeline', 1, '{}'::jsonb, 'probe-hash', 'DRAFT', '$alice')
            """.trimIndent(),
        )
        // One execution per trigger the backfill distinguishes: UI and REST stay NULL, MCP →
        // user, ENDPOINT → endpoint. `root_execution_id` is NOT NULL since V3.
        listOf(uiRun to ("UI" to alice), restRun to ("REST" to bob), mcpRun to ("MCP" to bob), endpointRun to ("ENDPOINT" to alice))
            .forEach { (id, viaAndActor) ->
                val (via, actor) = viaAndActor
                execute(
                    """
                    INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status, parameters_json,
                                                     triggered_by, triggered_via, root_execution_id)
                    VALUES ('$id', '$pipeline', 1, 'SUCCESS', '{}'::jsonb, '$actor', '$via', '$id')
                    """.trimIndent(),
                )
            }
    }

    private fun roleOf(userId: UUID): String =
        query("SELECT role FROM workspace_members WHERE workspace_id = '$ws' AND user_id = '$userId'") { it.getString(1) }.single()

    private fun flagsOf(userId: UUID): String =
        query(
            "SELECT author::TEXT || '|' || promoter::TEXT || '|' || admin::TEXT FROM workspace_members" +
                " WHERE workspace_id = '$ws' AND user_id = '$userId'",
        ) { it.getString(1) }.single()

    private fun columnsOf(table: String): List<String> =
        query("SELECT column_name FROM information_schema.columns WHERE table_name = '$table'") { it.getString(1) }

    private fun constraintsOf(table: String): List<String> =
        query(
            "SELECT constraint_name FROM information_schema.table_constraints" +
                " WHERE table_name = '$table' AND constraint_type = 'CHECK' AND constraint_name LIKE 'chk_%' ORDER BY 1",
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
        const val V29_PATH = "$MIGRATION_DIR/V29__workspace_roles.sql"
        const val V30_PATH = "$MIGRATION_DIR/V30__executions_executed_by.sql"

        /**
         * V29's DOWN path, as the migration's header documents it — kept here VERBATIM so the
         * comment and the proof cannot drift apart without this test noticing.
         */
        val V29_DOWN =
            """
            ALTER TABLE workspace_members ADD COLUMN author BOOLEAN NOT NULL DEFAULT FALSE,
                ADD COLUMN promoter BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN admin BOOLEAN NOT NULL DEFAULT FALSE;
            UPDATE workspace_members SET admin = TRUE, author = TRUE WHERE role = 'workspace_admin';
            UPDATE workspace_members SET promoter = TRUE                WHERE role = 'promoter';
            UPDATE workspace_members SET author = TRUE                  WHERE role = 'author';
            ALTER TABLE workspace_members ADD CONSTRAINT chk_workspace_member_admin_authors CHECK (NOT admin OR author);
            DROP INDEX idx_workspace_members_admins;
            CREATE INDEX idx_workspace_members_admins ON workspace_members(workspace_id) WHERE admin;
            ALTER TABLE workspace_members DROP CONSTRAINT chk_workspace_member_role, DROP COLUMN role;
            ALTER TABLE workspace_invitations ADD COLUMN author BOOLEAN NOT NULL DEFAULT FALSE,
                ADD COLUMN promoter BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN admin BOOLEAN NOT NULL DEFAULT FALSE;
            UPDATE workspace_invitations SET admin = TRUE, author = TRUE WHERE role = 'workspace_admin';
            UPDATE workspace_invitations SET promoter = TRUE                WHERE role = 'promoter';
            UPDATE workspace_invitations SET author = TRUE                  WHERE role = 'author';
            ALTER TABLE workspace_invitations ADD CONSTRAINT chk_workspace_invitation_admin_authors CHECK (NOT admin OR author);
            ALTER TABLE workspace_invitations DROP CONSTRAINT chk_workspace_invitation_role, DROP COLUMN role;
            """.trimIndent()

        /**
         * V1 through V28, in Flyway's own order. Spelled out rather than globbed: a directory
         * listing sorts `V10` before `V2`, and a migration chain applied out of order fails in
         * a way that looks like a defect in the migration under test.
         */
        val MIGRATION_PATHS_PRE_V29 =
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
         * A scratch database: this suite builds the schema PART-WAY on purpose (V1–V28,
         * pre-V29 rows, then V29 and V30, then V29's down path) and must not see the
         * fully-migrated database the other suites run against — the V29/V30 boundary is the
         * subject.
         */
        val db = SharedE2e.scratchDatabase("pre_v29_roles")
    }
}
