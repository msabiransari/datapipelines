package co.datapipelines.integration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * V4 (`workspaces rekey`) applied to a **populated** pre-V4 database, asserted at the row
 * level (the 016 rule: migration proofs assert rows, never just API behavior).
 *
 * [FlywayMigrationIntegrationTest] boots the app and proves Flyway applies V1→V4 cleanly on
 * an empty database; it cannot prove the backfills, because on a booted app every row is
 * written *after* V4. So this class drives the same shipped scripts directly — executed off
 * disk in version order, the discipline the module repository tests document (the Flyway
 * dependency stays in `app`, module-structure §3.1 rule 2): V1→V3, then pre-existing rows in
 * the pre-V4 shape, then V4. Every assertion below is raw SQL against the result.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspacesRekeyMigrationTest {
    private val userOne = UUID.randomUUID()
    private val userTwo = UUID.randomUUID()

    @BeforeAll
    fun migratePopulatedDatabase() {
        MIGRATION_PATHS_PRE_V4.forEach { execute(repoFile(it).readText()) }
        seedPreV4Rows()
        execute(repoFile(V4_PATH).readText())
    }

    @Test
    fun `the default workspace is seeded with the constant UUID and no creator`() {
        // metadata-db §4.11 (R1/R2): NULL created_by = system-provisioned; the constant is
        // deterministic across deployments and greppable.
        query(
            "SELECT id::TEXT || '|' || name || '|' || is_personal || '|' || COALESCE(created_by::TEXT, 'NULL')" +
                " FROM workspaces WHERE id = '$DEFAULT_WORKSPACE_ID'",
        ) { it.getString(1) } shouldContainExactly
            listOf("$DEFAULT_WORKSPACE_ID|default|false|NULL")
    }

    @Test
    fun `every pre-existing user becomes an owner member of the default workspace`() {
        // D9: the pre-workspaces world was one shared space every active user had full
        // capability over — the backfill enters existing users as 'owner' (metadata-db §4.12).
        query(
            "SELECT user_id::TEXT || '|' || role FROM workspace_members WHERE workspace_id = '$DEFAULT_WORKSPACE_ID'",
        ) { it.getString(1) } shouldContainExactlyInAnyOrder
            listOf("$userOne|owner", "$userTwo|owner")
    }

    @Test
    fun `every pre-existing pipeline and api key points at the default workspace`() {
        // Scoped to the pre-V4 rows: sibling tests insert their own pipelines in other
        // workspaces, and this class shares one container.
        query(
            "SELECT name || '|' || workspace_id::TEXT FROM pipelines" +
                " WHERE name IN ('orders_etl', 'old_report') AND workspace_id = '$DEFAULT_WORKSPACE_ID' ORDER BY name",
        ) {
            it.getString(1)
        } shouldContainExactly
            listOf(
                "old_report|$DEFAULT_WORKSPACE_ID", // soft-deleted rows backfill too
                "orders_etl|$DEFAULT_WORKSPACE_ID",
            )

        query("SELECT id || '|' || workspace_id::TEXT FROM api_keys") {
            it.getString(1)
        } shouldContainExactly listOf("dpk_PREEXISTING1|$DEFAULT_WORKSPACE_ID")
    }

    @Test
    fun `pre-existing datasources stay global and writable`() {
        // D9: they were shared before, so NULL (= global) preserves behavior.
        query("SELECT COALESCE(workspace_id::TEXT, 'NULL') || '|' || is_readonly FROM datasources") {
            it.getString(1)
        } shouldContainExactly listOf("NULL|false")
    }

    @Test
    fun `template versions re-key onto the surrogate and still resolve to their named template`() {
        // The pre-existing version rows must resolve to 'fetch_orders.sql' within `default`
        // exactly as before — pipeline-JSON {id, version} refs mean the human id, now `name`.
        query(
            "SELECT name || '|' || current_version || '|' || workspace_id::TEXT FROM templates" +
                " WHERE workspace_id = '$DEFAULT_WORKSPACE_ID'",
        ) {
            it.getString(1)
        } shouldContainExactly listOf("fetch_orders.sql|2|$DEFAULT_WORKSPACE_ID")

        query(
            """
            SELECT v.version || '|' || t.name || '|' || v.is_library
              FROM template_versions v
              JOIN templates t ON t.id = v.template_id
             WHERE t.workspace_id = '$DEFAULT_WORKSPACE_ID'
             ORDER BY v.version
            """.trimIndent(),
        ) { it.getString(1) } shouldContainExactly
            listOf(
                "1|fetch_orders.sql|true",
                "2|fetch_orders.sql|false",
            )

        // The surrogate is not the old TEXT id: templates.id is a generated UUID.
        query("SELECT COUNT(*) FROM templates WHERE id::TEXT = name") { it.getInt(1) } shouldContainExactly listOf(0)
    }

    @Test
    fun `the same pipeline name is legal in two workspaces and violated in one`() {
        val second = insertSecondWorkspace()

        insertPipeline("orders_etl", second) // legal: another workspace

        // Live row in `default`: violated. Soft-deleted 'old_report' holds its name too —
        // the soft-delete rule is per workspace, unchanged in spirit (metadata-db §4.4).
        shouldThrow<SQLException> { insertPipeline("orders_etl", UUID.fromString(DEFAULT_WORKSPACE_ID)) }
            .message shouldContain "uq_pipelines_workspace_name"
        shouldThrow<SQLException> { insertPipeline("old_report", UUID.fromString(DEFAULT_WORKSPACE_ID)) }
            .message shouldContain "uq_pipelines_workspace_name"
        insertPipeline("old_report", second) // legal: soft-delete holds the name per workspace only
    }

    @Test
    fun `the same template name is legal in two workspaces and violated in one`() {
        val second = UUID.randomUUID()
        execute("INSERT INTO workspaces (id, name, display_name, created_by) VALUES ('$second', 'third', 'Third', '$userOne')")

        execute(
            """
            INSERT INTO templates (name, display_name, current_version, workspace_id, created_by)
            VALUES ('fetch_orders.sql', 'Forked', 1, '$second', '$userOne')
            """.trimIndent(),
        )

        shouldThrow<SQLException> {
            execute(
                """
                INSERT INTO templates (name, display_name, current_version, workspace_id, created_by)
                VALUES ('fetch_orders.sql', 'Duplicate', 1, '$DEFAULT_WORKSPACE_ID', '$userOne')
                """.trimIndent(),
            )
        }.message shouldContain "uq_templates_workspace_name"
    }

    @Test
    fun `workspace_id carries no database default - the pin is repository code`() {
        // R2/house rule: no column DEFAULT does the slice-1 pinning, or slice 2 could not
        // find every pin by grepping the constant. An INSERT that omits workspace_id fails.
        shouldThrow<SQLException> {
            execute(
                """
                INSERT INTO pipelines (id, name, display_name, owner_id, current_version)
                VALUES ('${UUID.randomUUID()}', 'no_workspace', 'T', '$userOne', 1)
                """.trimIndent(),
            )
        }.message shouldContain "workspace_id"
    }

    // ------------------------------------------------------------ fixtures

    /** Rows in the pre-V4 shape: the population the backfills must carry over. */
    private fun seedPreV4Rows() {
        execute(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES
                ('$userOne', 'one@example.com', 'One', 'google', 'sub-$userOne'),
                ('$userTwo', 'two@example.com', 'Two', 'google', 'sub-$userTwo');

            INSERT INTO pipelines (id, name, display_name, owner_id, current_version, is_deleted) VALUES
                ('${UUID.randomUUID()}', 'orders_etl', 'Orders', '$userOne', 1, FALSE),
                ('${UUID.randomUUID()}', 'old_report', 'Old', '$userOne', 1, TRUE);

            INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by)
            SELECT id, 1, CAST('{}' AS jsonb), '$userOne' FROM pipelines;

            INSERT INTO templates (id, display_name, current_version, created_by)
            VALUES ('fetch_orders.sql', 'Fetch Orders', 2, '$userOne');

            INSERT INTO template_versions (template_id, version, dialect, is_library, body, created_by) VALUES
                ('fetch_orders.sql', 1, 'POSTGRES', TRUE, 'SELECT 1', '$userOne'),
                ('fetch_orders.sql', 2, 'POSTGRES', FALSE, 'SELECT 2', '$userTwo');

            INSERT INTO api_keys (id, user_id, name, key_hash, scopes)
            VALUES ('dpk_PREEXISTING1', '$userOne', 'claude', 'hash', '{read}');

            INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, password_encrypted, created_by)
            VALUES ('pg_prod', 'PG Prod', 'POSTGRES', 'jdbc:postgresql://db:5432/app', 'app', '\\x01'::bytea, '$userOne');
            """.trimIndent(),
        )
    }

    private fun insertSecondWorkspace(): UUID =
        UUID.randomUUID().also {
            execute("INSERT INTO workspaces (id, name, display_name, created_by) VALUES ('$it', 'second', 'Second', '$userOne')")
        }

    private fun insertPipeline(
        name: String,
        workspaceId: UUID,
    ) = execute(
        """
        INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)
        VALUES ('${UUID.randomUUID()}', '$name', 'T', '$userOne', 1, '$workspaceId')
        """.trimIndent(),
    )

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

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private companion object {
        const val DEFAULT_WORKSPACE_ID = "defa0000-0000-0000-0000-000000000001"
        const val MIGRATION_DIR = "modules/app/src/main/resources/db/migration"
        const val V4_PATH = "$MIGRATION_DIR/V4__workspaces_rekey.sql"
        val MIGRATION_PATHS_PRE_V4 =
            listOf(
                "$MIGRATION_DIR/V1__initial_schema.sql",
                "$MIGRATION_DIR/V2__datasource_introspection_include_schemas.sql",
                "$MIGRATION_DIR/V3__execution_lineage.sql",
            )

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

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("datapipelines")
                .withPassword("datapipelines")
    }
}
