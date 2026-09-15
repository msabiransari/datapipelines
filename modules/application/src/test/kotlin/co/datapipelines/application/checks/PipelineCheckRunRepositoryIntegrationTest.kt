package co.datapipelines.application.checks

import co.datapipelines.application.SharedPostgres
import co.datapipelines.pipeline.CheckRunVerdict
import co.datapipelines.pipeline.CheckRunVia
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertAll
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * [PipelineCheckRunRepository] against a real Postgres (metadata-db §4.20, the module's 083 §D
 * rule): the properties under test are statements ABOUT Postgres — the two JSONB columns
 * round-tripping through `CAST(:x AS jsonb)`, the wire enums binding under their CHECK
 * constraints, and the `DISTINCT ON (check_id)` latest-run read that
 * `idx_pipeline_check_runs_latest` exists for. A mocked template would assert that this module
 * passes strings to Spring, which nobody doubts.
 *
 * Cleans the tables it touches (`TRUNCATE … CASCADE` plus re-seed) — the SharedPostgres
 * discipline every sibling spec follows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineCheckRunRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: PipelineCheckRunRepository

    private lateinit var userId: UUID
    private lateinit var pipelineId: UUID

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        repository = PipelineCheckRunRepository(jdbc)
    }

    @BeforeEach
    fun clean() {
        jdbc.update("TRUNCATE pipeline_check_runs, pipelines, users CASCADE", emptyMap<String, Any>())
        // The default workspace is a migration SEED, and sibling specs on this shared database
        // truncate it away — re-seed rather than assume (the SharedPostgres discipline: each
        // spec restores what it needs).
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, is_personal, created_by) " +
                "VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default', FALSE, NULL) ON CONFLICT (id) DO NOTHING",
            emptyMap<String, Any>(),
        )
        userId = UUID.randomUUID()
        pipelineId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) " +
                "VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) " +
                "VALUES (:id, :name, 'T', '', :owner, 'defa0000-0000-0000-0000-000000000001', NULL)",
            mapOf("id" to pipelineId, "name" to "test/${pipelineId.toString().take(8)}", "owner" to userId),
        )
    }

    @Test
    fun `insert returns the stored row - jsonb round-tripped, enums through their CHECKs`() {
        val inserted =
            repository.insert(
                run(
                    checkId = "share_q4",
                    observedJson = """{"value":"74.62"}""",
                    verdict = CheckRunVerdict.PASS,
                    durationMs = 12,
                ),
            )

        assertAll(
            { inserted.pipelineId shouldBe pipelineId },
            { inserted.checkId shouldBe "share_q4" },
            { inserted.via shouldBe CheckRunVia.RELEASE },
            { inserted.verdict shouldBe CheckRunVerdict.PASS },
            { inserted.parametersJson shouldBe """{"month": "2026-08-01"}""" },
            { inserted.observedJson shouldBe """{"value": "74.62"}""" },
            { inserted.ranBy shouldBe userId },
            { inserted.durationMs shouldBe 12L },
        )
    }

    @Test
    fun `an error row stores null observed_json and the reason`() {
        val inserted =
            repository.insert(
                run(
                    checkId = "broke",
                    observedJson = null,
                    verdict = CheckRunVerdict.ERROR,
                    message = "Datasource 'x' could not be reached.",
                ),
            )

        assertAll(
            { inserted.observedJson shouldBe null },
            { inserted.verdict shouldBe CheckRunVerdict.ERROR },
            { inserted.message shouldBe "Datasource 'x' could not be reached." },
        )
    }

    @Test
    fun `latestPerCheck returns the newest run per check of exactly one pipeline version`() {
        // Two runs of the same check — the first stamped an hour older so the "latest" half is
        // decided by ran_at, never by a same-transaction timestamp tie; one of another check;
        // one of another VERSION and one of another PIPELINE, which the read must not see.
        val older = repository.insert(run(checkId = "a", verdict = CheckRunVerdict.FAIL, message = "first run"))
        jdbc.update(
            "UPDATE pipeline_check_runs SET ran_at = ran_at - INTERVAL '1 hour' WHERE id = :id",
            mapOf("id" to older.id),
        )
        repository.insert(run(checkId = "a", verdict = CheckRunVerdict.PASS, message = null))
        repository.insert(run(checkId = "b", verdict = CheckRunVerdict.ERROR, message = "datasource down"))
        repository.insert(run(checkId = "a", version = 8, verdict = CheckRunVerdict.PASS))
        val otherPipeline = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) " +
                "VALUES (:id, :name, 'T', '', :owner, 'defa0000-0000-0000-0000-000000000001', NULL)",
            mapOf("id" to otherPipeline, "name" to "test/${otherPipeline.toString().take(8)}", "owner" to userId),
        )
        repository.insert(run(checkId = "a", pipeline = otherPipeline, verdict = CheckRunVerdict.PASS))

        val latest = repository.latestPerCheck(pipelineId, 7)

        latest shouldHaveSize 2
        assertAll(
            { latest.map { it.checkId } shouldContainExactly listOf("a", "b") },
            { latest.single { it.checkId == "a" }.verdict shouldBe CheckRunVerdict.PASS },
            { latest.single { it.checkId == "b" }.message shouldBe "datasource down" },
        )
    }

    // ------------------------------------------------------------------------------- fixtures

    private fun run(
        checkId: String,
        pipeline: UUID = pipelineId,
        version: Int = 7,
        observedJson: String? = null,
        verdict: CheckRunVerdict,
        message: String? = null,
        durationMs: Long? = null,
    ) = NewPipelineCheckRun(
        pipelineId = pipeline,
        version = version,
        checkId = checkId,
        ranBy = userId,
        via = CheckRunVia.RELEASE,
        parametersJson = """{"month": "2026-08-01"}""",
        observedJson = observedJson,
        verdict = verdict,
        message = message,
        correlationId = null,
        durationMs = durationMs,
    )
}
