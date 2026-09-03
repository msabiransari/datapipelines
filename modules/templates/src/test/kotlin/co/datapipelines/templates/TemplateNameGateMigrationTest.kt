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
 * V7's §4.6 legacy-name gate against REAL pre-migration rows (template-hierarchy-design.md
 * §12.2): the new grammar is narrower than the old flat rule in two respects, and the loader
 * re-validates at render time, so a stored illegal name would break already-released pipelines
 * silently. The gate makes the deploy fail loudly instead, naming every offender.
 *
 * The schema is built V1–V6 exactly as [VersionBackfillMigrationTest] does (via
 * [ShippedMigrations], never a hand-copied list), offenders are inserted the way V6-era rows
 * exist, and then the V7 script is executed by plain JDBC, exactly as Flyway would apply it.
 *
 * Both tests are order-independent: the abort leaves the schema untouched (the DO block is
 * atomic), the clean-run has no side effects to conflict over, and the offender rows are
 * removed by the test that created them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateNameGateMigrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun createPreV7Schema() {
        jdbc = NamedParameterJdbcTemplate(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        val dir = TemplateFixtures.repoDirectory("modules/app/src/main/resources/db/migration")
        ShippedMigrations.migrations(dir).filter { it.first < 7 }.forEach { pair ->
            jdbc.jdbcTemplate.execute(pair.second.readText())
        }
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject)" +
                " VALUES (:id, 'gate@example.com', 'Gate', 'google', 'sub-gate')",
            mapOf("id" to ACTOR_ID),
        )
    }

    @Test
    fun `V7 aborts and names every offender - active and soft-deleted alike`() {
        insertTemplate("_helper", deleted = false)
        insertTemplate("-legacy", deleted = true)
        try {
            // UncategorizedDataAccessException: a PL/pgSQL RAISE EXCEPTION surfaces as an
            // uncategorized SQLException; the message is the gate's whole point.
            val thrown =
                shouldThrow<UncategorizedDataAccessException> {
                    jdbc.jdbcTemplate.execute(v7())
                }
            thrown.message shouldContain "V7 aborted"
            // The active offender AND the soft-deleted one — is_deleted is deliberately NOT
            // filtered, because lookupVersion still resolves soft-deleted templates for pinned
            // refs, so their names are still subject to the loader's grammar (§4.6).
            thrown.message shouldContain "_helper"
            thrown.message shouldContain "-legacy"
        } finally {
            jdbc.jdbcTemplate.execute("DELETE FROM templates WHERE name IN ('_helper', '-legacy')")
        }
    }

    @Test
    fun `V7 applies cleanly when no stored name violates the grammar`() {
        insertTemplate("fetch_orders.sql", deleted = false)
        insertTemplate("acme/finance/report", deleted = false)

        jdbc.jdbcTemplate.execute(v7())
    }

    private fun v7(): String = TemplateFixtures.repoFile(ShippedMigrations.paths().first { it.contains("V7__") }).readText()

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
        /** templates.created_by is NOT NULL REFERENCES users — V1/V4 seed no users, so one is inserted. */
        val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000043")

        /**
         * A scratch database on the module's shared container: this suite builds the schema
         * PART-WAY on purpose (pre-V7), so it must not see the fully-migrated database the
         * rest of the module runs against — the migration boundary is the subject.
         */
        val db = SharedPostgres.scratchDatabase("pre_v7_name_gate")
    }
}
