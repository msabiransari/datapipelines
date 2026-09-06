package co.datapipelines.templates

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.UncategorizedDataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * `V12__folder_required.sql`'s §4.6 gate against REAL pre-migration rows
 * (template-hierarchy-design.md §12.14): 077 makes a flat template name illegal, and the
 * loader re-validates at render time, so a stored flat name would break already-released
 * pipelines silently after an upgrade. The gate makes the deploy fail loudly instead, naming
 * every offender.
 *
 * Deliberately [TemplateNameGateMigrationTest]'s shape, one migration on: the schema is built
 * V1–V11 through [ShippedMigrations] (never a hand-copied list), offenders are inserted the
 * way V11-era rows exist, and the V12 script is executed by plain JDBC exactly as Flyway
 * would apply it. That sibling still guards V7's narrowing — the two gates are separate
 * migrations because an applied migration's text is frozen by its checksum, so they get
 * separate tests too.
 *
 * Both tests are order-independent: the abort leaves the schema untouched (the DO block is
 * atomic), the clean run has no side effects to conflict over, and the offender rows are
 * removed by the test that created them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateFolderGateMigrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun createPreV12Schema() {
        jdbc = NamedParameterJdbcTemplate(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        val dir = TemplateFixtures.repoDirectory("modules/app/src/main/resources/db/migration")
        ShippedMigrations.migrations(dir).filter { it.first < 12 }.forEach { pair ->
            jdbc.jdbcTemplate.execute(pair.second.readText())
        }
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject)" +
                " VALUES (:id, 'folder-gate@example.com', 'Folder Gate', 'google', 'sub-folder-gate')",
            mapOf("id" to ACTOR_ID),
        )
    }

    @Test
    fun `V12 aborts and names every flat offender - active and soft-deleted alike`() {
        insertTemplate("active_users.sql", deleted = false)
        insertTemplate("legacy_report", deleted = true)
        try {
            // UncategorizedDataAccessException: a PL/pgSQL RAISE EXCEPTION surfaces as an
            // uncategorized SQLException; the message is the gate's whole point.
            val thrown =
                shouldThrow<UncategorizedDataAccessException> {
                    jdbc.jdbcTemplate.execute(v12())
                }
            thrown.message shouldContain "V12 aborted"
            // The active offender AND the soft-deleted one — is_deleted is deliberately NOT
            // filtered, because lookupVersion still resolves soft-deleted templates for pinned
            // refs, so their names are still subject to the loader's grammar (§4.6).
            thrown.message shouldContain "active_users.sql"
            thrown.message shouldContain "legacy_report"
            // The remediation an operator needs is in the message, not only in the doc.
            thrown.message shouldContain "test/"
        } finally {
            jdbc.jdbcTemplate.execute("DELETE FROM templates WHERE name IN ('active_users.sql', 'legacy_report')")
        }
    }

    @Test
    fun `V12 applies cleanly when every stored name carries a folder`() {
        insertTemplate("nyc/mobility/daily_by_zone.sql", deleted = false)
        insertTemplate("test/scratch", deleted = true)

        jdbc.jdbcTemplate.execute(v12())
    }

    /**
     * The gate looks at `templates` and nothing else (§14.2): a pipeline name is validated at
     * SAVE only, so a legacy flat pipeline still runs and aborting a deployment over it would
     * be a false alarm. Asserted, not assumed — this is the one difference between the two
     * asset kinds that a future edit could quietly erase.
     */
    @Test
    fun `V12 ignores a flat pipeline name`() {
        jdbc.update(
            """
            INSERT INTO pipelines (workspace_id, name, display_name, owner_id)
            VALUES ('defa0000-0000-0000-0000-000000000001', 'legacy_flat_pipeline', 'P', :actor)
            """.trimIndent(),
            mapOf("actor" to ACTOR_ID),
        )
        try {
            jdbc.jdbcTemplate.execute(v12())
        } finally {
            jdbc.jdbcTemplate.execute("DELETE FROM pipelines WHERE name = 'legacy_flat_pipeline'")
        }
    }

    private fun v12(): String = TemplateFixtures.repoFile(ShippedMigrations.paths().first { it.contains("V12__") }).readText()

    private fun insertTemplate(
        name: String,
        deleted: Boolean,
    ) {
        jdbc.update(
            """
            INSERT INTO templates (workspace_id, name, display_name, is_deleted, created_by)
            VALUES ('defa0000-0000-0000-0000-000000000001', :name, 'T', :deleted, :actor)
            """.trimIndent(),
            mapOf("name" to name, "deleted" to deleted, "actor" to ACTOR_ID),
        )
    }

    private companion object {
        /** templates.created_by is NOT NULL REFERENCES users — the migrations seed no users, so one is inserted. */
        val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000077")

        /**
         * A scratch database on the module's shared container: this suite builds the schema
         * PART-WAY on purpose (pre-V12), so it must not see the fully-migrated database the
         * rest of the module runs against — the migration boundary is the subject.
         */
        val db = SharedPostgres.scratchDatabase("pre_v12_folder_gate")
    }
}
