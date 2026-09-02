package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * V8's typed-template schema against REAL pre-migration rows (template-hierarchy-design §5.1,
 * §12.6/§12.13, round 046): the `type` column, the relaxed `dialect`, and the four constraint
 * shapes — plus the round's most important gate, hash stability (§5.2).
 *
 * The schema is built V1–V7 exactly as [TemplateNameGateMigrationTest] does (via
 * [ShippedMigrations], never a hand-copied list), pre-V8 template rows are inserted the way
 * V7-era rows exist — with `body_hash` stamped by the SAME canonical expression V6's backfill
 * used — and then the V8 script is executed once, by plain JDBC, exactly as Flyway would
 * apply it. Every test below therefore observes the genuine before/after boundary.
 *
 * ## The hash-stability gate, and why it is an integration test
 *
 * `TEMPLATE_HASH_EXPR` does not gain `type` as an input (§5.2, normative): pre-V8 rows were
 * hashed without it, so adding it would make the draft-write `<>` branch always true — a
 * byte-identical PUT would create a draft on every save, forever. That failure is invisible
 * to any unit test of the repository's SQL strings; it only exists across the migration
 * boundary, so the test seeds a released row PRE-V8 and drives a no-op PUT POST-V8 through
 * the real [TemplateRepository.createDraft]. Falsification (§12.13): add `'type', type` to
 * [TemplateRepository.TEMPLATE_HASH_EXPR] and bind the parameter, and this test goes red —
 * verified in the round's handback, then reverted.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypedTemplatesMigrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun createPreV8SchemaThenApplyV8() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
        val dir = TemplateFixtures.repoDirectory("modules/app/src/main/resources/db/migration")
        ShippedMigrations.migrations(dir).filter { it.first < 8 }.forEach { pair ->
            jdbc.jdbcTemplate.execute(pair.second.readText())
        }
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject)" +
                " VALUES (:id, 'typed@example.com', 'Typed', 'google', 'sub-typed')",
            mapOf("id" to ACTOR_ID),
        )
        // Two pre-V8 templates — one to observe the backfill, one to drive the no-op-PUT gate.
        insertPreV8ReleasedTemplate("legacy_orders.sql", "SELECT 1")
        insertPreV8ReleasedTemplate("pre_v8_report.sql", "SELECT 42 AS answer")
        // V8, applied once, exactly as Flyway would.
        jdbc.jdbcTemplate.execute(v8())
    }

    @Test
    fun `V8 backfills type sql everywhere and leaves dialects alone`() {
        val rows =
            jdbc.queryForList(
                "SELECT name, v.type, v.dialect FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                    " ORDER BY t.name",
                emptyMap<String, Any>(),
            )
        assertSoftly {
            rows.size shouldBe 2
            rows.forEach { row ->
                row["type"] shouldBe "sql"
                row["dialect"] shouldBe "POSTGRES"
            }
        }
    }

    @Test
    fun `the four violation shapes are rejected and a valid html row is storable`() {
        // sql + null dialect (chk_type_dialect)
        insertVersionShouldFail(type = "sql", dialect = null, constraint = "chk_type_dialect")
        // html + present dialect (chk_type_dialect)
        insertVersionShouldFail(type = "html", dialect = "POSTGRES", constraint = "chk_type_dialect")
        // both invalid type values (chk_template_type)
        insertVersionShouldFail(type = "csv", dialect = null, constraint = "chk_template_type")
        insertVersionShouldFail(type = "SQL", dialect = null, constraint = "chk_template_type")

        // The one legal html shape: type html, dialect null.
        insertVersion(type = "html", dialect = null)
        jdbc.update(
            "DELETE FROM template_versions v USING templates t" +
                " WHERE t.id = v.template_id AND t.name = 'shape_probe.sql'",
            emptyMap<String, Any>(),
        )
    }

    /**
     * §5.2's gate (§12.13): a template released **before** V8, hashed by the pre-V8
     * expression, still no-ops a byte-identical PUT **after** V8 — no draft, no burned
     * version number, the RELEASED detail returned as the no-op signal.
     */
    @Test
    fun `a pre-V8 release still no-ops a byte-identical PUT after V8`() {
        val repository = TemplateRepository(jdbc)
        val stored = checkNotNull(repository.findLatest(WORKSPACE_ID, "pre_v8_report.sql"))
        stored.type shouldBe co.datapipelines.pipeline.TemplateType.SQL

        val identical = TemplateFixtures.draft(id = "pre_v8_report.sql", body = "SELECT 42 AS answer")
        val outcome =
            repository.createDraft(WORKSPACE_ID, "pre_v8_report.sql", identical, stored.bodyHash, ACTOR_ID)

        assertSoftly {
            // The no-op signal: the returned detail is the current RELEASED version, not a draft.
            outcome?.status shouldBe PipelineVersionStatus.RELEASED
            outcome?.version shouldBe stored.version
            // And no draft row was created — the failure mode §5.2 forbids.
            draftCount("pre_v8_report.sql") shouldBe 0
        }
    }

    /** A PUT whose content DOES differ still creates exactly one draft — the guard is not dead. */
    @Test
    fun `a changed PUT still creates a draft after V8`() {
        val repository = TemplateRepository(jdbc)
        val stored = checkNotNull(repository.findLatest(WORKSPACE_ID, "legacy_orders.sql"))
        val changed = TemplateFixtures.draft(id = "legacy_orders.sql", body = "SELECT 2")
        val outcome =
            repository.createDraft(WORKSPACE_ID, "legacy_orders.sql", changed, stored.bodyHash, ACTOR_ID)

        outcome?.status shouldBe PipelineVersionStatus.DRAFT
        draftCount("legacy_orders.sql") shouldBe 1
        jdbc.update(
            "DELETE FROM template_versions v USING templates t" +
                " WHERE t.id = v.template_id AND t.name = 'legacy_orders.sql' AND v.status = 'DRAFT'",
            emptyMap<String, Any>(),
        )
    }

    /** The type-immutability refusal through the full draft-service path, post-V8. */
    @Test
    fun `a PUT carrying a different type is refused with type_immutable`() {
        val repository = TemplateRepository(jdbc)
        val service = TemplateDraftService(repository, co.datapipelines.pipeline.AuthoringGuard(true))
        val stored = checkNotNull(repository.findLatest(WORKSPACE_ID, "legacy_orders.sql"))
        val offending =
            TemplateFixtures
                .draft(id = "legacy_orders.sql", body = "SELECT 3")
                .copy(type = co.datapipelines.pipeline.TemplateType.HTML)

        val thrown =
            shouldThrow<DatapipelinesException> {
                service.write(WORKSPACE_ID, "legacy_orders.sql", offending, stored.bodyHash, ACTOR_ID)
            }
        thrown.code shouldBe PipelineErrorCodes.Template.TYPE_IMMUTABLE
        thrown.details["established_type"] shouldBe "sql"
        draftCount("legacy_orders.sql") shouldBe 0
    }

    // ---------------------------------------------------------------------------------------------

    /** Seeds a RELEASED v1 exactly the way a V7-era row exists, hash stamped by V6's expression. */
    private fun insertPreV8ReleasedTemplate(
        name: String,
        body: String,
    ) {
        jdbc.update(
            """
            INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)
            VALUES (:name, 'Pre-V8', '', 1, :ws, :actor)
            """.trimIndent(),
            mapOf("name" to name, "ws" to WORKSPACE_ID, "actor" to ACTOR_ID),
        )
        jdbc.update(
            """
            INSERT INTO template_versions
                (template_id, version, engine, dialect, is_library, imports_json, body,
                 status, body_hash, created_by, released_by, released_at)
            SELECT t.id, 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, :body,
                   'RELEASED',
                   encode(sha256(convert_to(jsonb_build_object('engine', 'freemarker', 'dialect', 'POSTGRES',
                       'is_library', FALSE, 'imports', '[]'::jsonb, 'body', :body)::text, 'UTF8')), 'hex'),
                   :actor, :actor, NOW()
              FROM templates t WHERE t.name = :name
            """.trimIndent(),
            mapOf("name" to name, "body" to body, "actor" to ACTOR_ID),
        )
    }

    /** A post-V8 direct insert of one probe version with an arbitrary type/dialect pairing. */
    private fun insertVersion(
        type: String?,
        dialect: String?,
    ) {
        jdbc.update(
            """
            INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)
            VALUES ('shape_probe.sql', 'Probe', '', 1, :ws, :actor)
            ON CONFLICT (workspace_id, name) DO NOTHING
            """.trimIndent(),
            mapOf("ws" to WORKSPACE_ID, "actor" to ACTOR_ID),
        )
        jdbc.update(
            """
            INSERT INTO template_versions
                (template_id, version, engine, type, dialect, is_library, imports_json, body,
                 status, body_hash, created_by)
            SELECT t.id, 1, 'freemarker', CAST(:type AS TEXT), CAST(:dialect AS TEXT), FALSE, '[]'::jsonb, 'x',
                   'RELEASED', 'h', :actor
              FROM templates t WHERE t.name = 'shape_probe.sql'
            """.trimIndent(),
            mapOf("type" to type, "dialect" to dialect, "actor" to ACTOR_ID),
        )
    }

    private fun draftCount(name: String): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                    " WHERE t.name = :name AND v.status = 'DRAFT'",
                mapOf("name" to name),
                Int::class.java,
            ),
        )

    /** Asserts the insert fails naming exactly [constraint] — the shape's own refusal. */
    private fun insertVersionShouldFail(
        type: String?,
        dialect: String?,
        constraint: String,
    ) {
        val thrown =
            shouldThrow<org.springframework.dao.DataIntegrityViolationException> {
                insertVersion(type, dialect)
            }
        thrown.mostSpecificCause.message shouldContain constraint
    }

    private fun v8(): String = TemplateFixtures.repoFile(ShippedMigrations.paths().first { it.contains("V8__") }).readText()

    private fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    private companion object {
        /** templates.created_by is NOT NULL REFERENCES users — V1/V4 seed no users, so one is inserted. */
        val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000046")

        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("dp")
                .withPassword("dp")
    }
}
