package co.datapipelines.application.endpoints

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.ShippedMigrations
import co.datapipelines.application.TestRepoFiles
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.UUID

/**
 * `V41__retire_pre_rep5_endpoint_rows.sql` against REAL pre-migration rows (#274).
 *
 * [DatasourceCredentialKindMigrationTest]'s shape, one migration on (datasources.md §3.4): the
 * schema is built V1–V39 through [ShippedMigrations] (never a hand-copied list), rows are
 * inserted the way pre-R-EP5 rows exist — a two-segment `path_pattern`, `is_enabled` TRUE, no
 * `retired_reason` column yet — and the V41 script is executed by plain JDBC exactly as Flyway
 * would apply it. The DOWN path documented in the migration's header is executed the same way,
 * so the round trip is proven, not asserted.
 *
 * The migration's whole claim is that retirement is honest rather than destructive: the legacy
 * row comes back disabled WITH its reason, the normal row next to it is untouched (a NULL
 * reason stays NULL, an enabled row stays enabled), and the down path restores what the column
 * found. What that leaves for [EndpointPersistenceIntegrationTest] and the boot E2E to prove is
 * that a retired row neither serves nor blocks a publish.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class) // the third case IS the down path — it runs last
class RetirePreRep5RowsMigrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun createPreV41SchemaMigrateSeedAndMigrate() {
        val db = SharedPostgres.scratchDatabase("pre_v41_retire_endpoints")
        jdbc = NamedParameterJdbcTemplate(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        ShippedMigrations.paths().filter { versionOf(it) < V41 }.forEach { path ->
            jdbc.jdbcTemplate.execute(TestRepoFiles.read(path))
        }
        seedPreV41Rows()
        jdbc.jdbcTemplate.execute(v41())
    }

    @Order(1)
    @Test
    fun `the two-segment row is disabled and names the reason`() {
        legacyRow() shouldBe
            mapOf(
                "is_enabled" to false,
                "retired_reason" to "pre-R-EP5 path",
            )
    }

    @Order(2)
    @Test
    fun `the row that already meets today's grammar is untouched`() {
        validRow() shouldBe
            mapOf(
                "is_enabled" to true,
                "retired_reason" to null,
            )
    }

    @Order(3)
    @Test
    fun `the documented down path restores the state the column found`() {
        jdbc.jdbcTemplate.execute(
            "UPDATE published_endpoints SET is_enabled = TRUE WHERE retired_reason = 'pre-R-EP5 path'",
        )
        jdbc.jdbcTemplate.execute("ALTER TABLE published_endpoints DROP COLUMN retired_reason")

        // Both rows back the way V39 left them: enabled, no reason column at all.
        jdbc
            .queryForList(
                "SELECT is_enabled FROM published_endpoints WHERE path_pattern IN (:legacy, :valid) ORDER BY path_pattern",
                mapOf("legacy" to LEGACY_PATH, "valid" to VALID_PATH),
                Boolean::class.java,
            ) shouldBe listOf(true, true)
        val hasReasonColumn =
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'published_endpoints'" +
                    " AND column_name = 'retired_reason'",
                emptyMap<String, Any>(),
                Long::class.java,
            )
        hasReasonColumn shouldBe 0L
    }

    // ------------------------------------------------------------------------- helpers

    /** Rows as 2026-09-18 wrote them: a two-segment path enabled beside a three-segment one. */
    private fun seedPreV41Rows() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject)" +
                " VALUES (:id, 'retire-v41@example.com', 'V41', 'google', 'sub-retire-v41')",
            mapOf("id" to ACTOR_ID),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf(
                "id" to WORKSPACE_ID,
                "name" to "w_${WORKSPACE_ID.toString().replace("-", "")}",
                "owner" to ACTOR_ID,
            ),
        )
        jdbc.update(
            """
            INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)
            VALUES (:id, :name, 'P', :owner, 1, :ws)
            """.trimIndent(),
            mapOf(
                "id" to PIPELINE_ID,
                "name" to "p_${PIPELINE_ID.toString().replace("-", "")}",
                "owner" to ACTOR_ID,
                "ws" to WORKSPACE_ID,
            ),
        )
        insertEndpoint(LEGACY_PATH)
        insertEndpoint(VALID_PATH)
    }

    /** The columns V11 defined and V41 does not touch — the shape the old seeder left behind. */
    private fun insertEndpoint(path: String) =
        jdbc.update(
            """
            INSERT INTO published_endpoints
                (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, description, is_enabled, created_by, created_at, updated_at)
            VALUES (:id, :workspaceId, :path, :pipelineId, 30, '', TRUE, :createdBy, :at, :at)
            """.trimIndent(),
            mapOf(
                "id" to UUID.nameUUIDFromBytes(path.toByteArray()),
                "workspaceId" to WORKSPACE_ID,
                "path" to path,
                "pipelineId" to PIPELINE_ID,
                "createdBy" to ACTOR_ID,
                "at" to java.sql.Timestamp.from(Instant.EPOCH),
            ),
        )

    private fun legacyRow(): Map<String, Any?> =
        jdbc
            .queryForList(
                "SELECT is_enabled, retired_reason FROM published_endpoints WHERE path_pattern = :path",
                mapOf("path" to LEGACY_PATH),
            ).single()

    private fun validRow(): Map<String, Any?> =
        jdbc
            .queryForList(
                "SELECT is_enabled, retired_reason FROM published_endpoints WHERE path_pattern = :path",
                mapOf("path" to VALID_PATH),
            ).single()

    /** The SHIPPED V41 script, located by its PURPOSE — the orchestrator renumbers parallel lanes. */
    private fun v41(): String =
        TestRepoFiles.read(
            ShippedMigrations.paths().singleOrNull { it.endsWith(MIGRATION_SUFFIX) }
                ?: error("no shipped migration ends with '$MIGRATION_SUFFIX' — it was renamed, not renumbered"),
        )

    private fun versionOf(path: String): Int = path.substringAfterLast("/V").substringBefore("__").toInt()

    private companion object {
        const val MIGRATION_SUFFIX = "__retire_pre_rep5_endpoint_rows.sql"

        /** The owner's local row, verbatim (#274): the demo workspace's pre-R-EP5 path. */
        const val LEGACY_PATH = "/nyc/revenue-by-borough"

        /** A row today's grammar admits, so the update can be seen to touch only the short ones. */
        const val VALID_PATH = "/nyc/v1/revenue-by-borough"

        /**
         * The version this suite builds UP TO but not including. Derived from the shipped file
         * rather than typed, so a renumber moves the boundary with the script instead of
         * silently applying V41 twice.
         */
        val V41: Int =
            ShippedMigrations
                .paths()
                .single { it.endsWith(MIGRATION_SUFFIX) }
                .substringAfterLast("/V")
                .substringBefore("__")
                .toInt()

        val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000274")
        val WORKSPACE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000275")
        val PIPELINE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000276")
    }
}
