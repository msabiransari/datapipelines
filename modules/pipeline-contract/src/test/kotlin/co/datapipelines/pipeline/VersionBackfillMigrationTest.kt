package co.datapipelines.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * V6's backfill against REAL pre-migration rows (versioning §11 / 035 A2 — "backfill is not
 * a footnote").
 *
 * The failure mode this suite exists to catch: the backfill computing `body_hash` with a
 * DIFFERENT canonicalization than the runtime uses, so every pre-existing row fails its
 * first precondition check — a pipeline no agent or human can ever edit again, silently,
 * because reads still work. The proof is behavioural, not tautological: a row inserted the
 * way V5-era code inserted it (no status, no hash) must, after V6, satisfy the draft-create
 * precondition with the hash the MIGRATION stored — the exact "first precondition check"
 * the brief names.
 *
 * The schema is built in three steps on one container — V1–V5, pre-V6 inserts, then V6 —
 * which is the only way to stand up genuinely pre-migration data;
 * `PipelineRepositoryIntegrationTest` runs the full set for the lifecycle suite, and
 * `TemplateRepositoryIntegrationTest` mirrors the template half of the backfill with the
 * real `TemplateRepository`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VersionBackfillMigrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: PipelineRepository
    private lateinit var owner: UUID
    private val serializer = PipelineSerializer()

    @BeforeAll
    fun createPreV6SchemaThenMigrate() {
        jdbc = NamedParameterJdbcTemplate(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        // V1–V5 only, by version number: the state a live deployment was in before V6.
        val dir = Fixtures.repoDirectory("modules/app/src/main/resources/db/migration")
        val preV6 = ShippedMigrations.migrations(dir).filter { it.first < 6 }
        preV6.forEach { pair -> jdbc.jdbcTemplate.execute(pair.second.readText()) }

        owner = insertUser()
        insertPreV6Pipeline("legacy_pipeline", """{"schema_version":1,"name":"legacy_pipeline"}""")
        insertPreV6Pipeline("second_legacy", """{"schema_version":1,"name":"second_legacy"}""")
        insertPreV6Pipeline("third_legacy", """{"schema_version":1,"name":"third_legacy"}""")
        // Owns the A2 proof's draft: that test LEAVES a version-2 DRAFT behind (the same
        // reason third_legacy exists — JUnit order is not fixed, and the RELEASED-rows test
        // below counts legacy_pipeline's versions unfiltered).
        insertPreV6Pipeline("precondition_legacy", """{"schema_version":1,"name":"precondition_legacy"}""")

        // …and then V6, exactly as Flyway would apply it to a deployment holding those rows.
        val v6 = ShippedMigrations.paths().first { it.contains("V6__") }
        jdbc.jdbcTemplate.execute(Fixtures.repoFile(v6).readText())

        repository = PipelineRepository(jdbc)
    }

    @Test
    fun `every pre-migration row is RELEASED with a computed hash and release stamps`() {
        val rows =
            jdbc.jdbcTemplate.queryForList(
                """
                SELECT v.version, v.status, v.body_hash, v.released_at, v.released_by, v.updated_by, v.updated_at
                  FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id
                 WHERE p.name = 'legacy_pipeline'
                """.trimIndent(),
            )
        rows.size shouldBe 1
        val row = rows.single()
        row["status"] shouldBe "RELEASED"
        (row["body_hash"] as String).length shouldBe 64
        row["released_at"] shouldNotBe null
        row["released_by"] shouldBe owner
        row["updated_by"] shouldBe null
        row["updated_at"] shouldBe null
    }

    @Test
    fun `a pre-migration row passes its first precondition check - the A2 proof`() {
        val record = checkNotNull(repository.findByName(WORKSPACE_ID, "precondition_legacy"))
        val detail = checkNotNull(repository.findCurrentVersionDetail(WORKSPACE_ID, record.id))

        // The hash the migration stored is the hash the runtime's own expression computes…
        val storedBody = checkNotNull(repository.findVersionBody(WORKSPACE_ID, record.id, 1))
        detail.bodyHash shouldBe repository.computeBodyHash(storedBody)

        // …and therefore the FIRST draft write against this pre-existing pipeline succeeds —
        // the exact operation that would 409 forever if backfill and runtime disagreed.
        val draft =
            repository.createDraft(
                WORKSPACE_ID,
                record.id,
                serializer.write(Fixtures.pipeline(name = "precondition_legacy")),
                detail.bodyHash,
                owner,
            )
        draft.shouldBeInstanceOf<PipelineVersionDetail>()
        draft.version shouldBe 2
        draft.status shouldBe PipelineVersionStatus.DRAFT
    }

    @Test
    fun `the backfill hash survives a full lifecycle on a pre-migration row`() {
        val record = checkNotNull(repository.findByName(WORKSPACE_ID, "second_legacy"))
        val detail = checkNotNull(repository.findCurrentVersionDetail(WORKSPACE_ID, record.id))
        val body = Fixtures.pipeline(name = "second_legacy")

        val draftBody = serializer.write(body)
        val draft =
            checkNotNull(
                repository.createDraft(WORKSPACE_ID, record.id, draftBody, detail.bodyHash, owner),
            )
        val released =
            checkNotNull(
                repository.releaseDraft(WORKSPACE_ID, record.id, body.name, body.displayName, body.description, draft.bodyHash, owner),
            )
        released.version.version shouldBe 2
        released.record.currentVersion shouldBe 2
    }

    @Test
    fun `the one-draft index exists after migration - a second draft insert is refused`() {
        // Own pipeline: this test LEAVES a draft behind, and the A2 proof test must not
        // find one already present when it runs (JUnit order is not fixed).
        val record = checkNotNull(repository.findByName(WORKSPACE_ID, "third_legacy"))
        val detail = checkNotNull(repository.findCurrentVersionDetail(WORKSPACE_ID, record.id))
        repository.createDraft(WORKSPACE_ID, record.id, serializer.write(Fixtures.pipeline(name = "third_legacy")), detail.bodyHash, owner)

        val thrown =
            shouldThrow<org.springframework.dao.DuplicateKeyException> {
                jdbc.update(
                    "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by)" +
                        " VALUES (:id, 50, CAST('{}' AS jsonb), 'x', 'DRAFT', :actor)",
                    mapOf("id" to record.id, "actor" to owner),
                )
            }
        thrown.mostSpecificCause.message?.contains("uq_pipeline_versions_one_draft") shouldBe true
    }

    @Test
    fun `status CHECK refuses unknown lifecycle values`() {
        shouldThrow<org.springframework.dao.DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by)" +
                    " SELECT pipeline_id, 77, CAST('{}' AS jsonb), 'x', 'FROZEN', created_by FROM pipeline_versions LIMIT 1",
                emptyMap<String, Any>(),
            )
        }
    }

    private fun insertPreV6Pipeline(
        name: String,
        bodyJson: String,
    ) {
        jdbc.update(
            """
            INSERT INTO pipelines (id, name, display_name, owner_id, workspace_id, current_version)
            VALUES (gen_random_uuid(), :name, 'Legacy', :owner, :ws, 1)
            """.trimIndent(),
            mapOf("name" to name, "owner" to owner, "ws" to WORKSPACE_ID),
        )
        jdbc.update(
            """
            INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by)
            SELECT id, 1, CAST(:body AS jsonb), :owner FROM pipelines WHERE name = :name
            """.trimIndent(),
            mapOf("body" to bodyJson, "owner" to owner, "name" to name),
        )
    }

    private fun insertUser(): UUID =
        checkNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO users (email, display_name, provider, provider_subject)
                VALUES ('backfill@example.com', 'Backfill', 'google', 'sub-backfill')
                RETURNING id
                """.trimIndent(),
                emptyMap<String, Any>(),
                UUID::class.java,
            ),
        )

    private companion object {
        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        /**
         * A scratch database on the module's shared container: this suite builds the schema
         * PART-WAY on purpose (V1–V5, then V6), so it must not see the fully-migrated
         * database the rest of the module runs against — the backfill's whole subject is
         * the before/after boundary.
         */
        val db = SharedPostgres.scratchDatabase("pre_v6_backfill")
    }
}
