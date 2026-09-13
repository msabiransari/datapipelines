package co.datapipelines.templates

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * versioning.md §3.5 — the entity purge's offer, at the one place it is actually decided:
 * [TemplateRepository.exclusiveDraftTemplateIds] and its SQL. Every other suite that touches the
 * offer stubs this call (`PipelineServiceIntegrationTest` answers a fixed list through the
 * [co.datapipelines.pipeline.ExclusiveDraftTemplates] seam; the web tests stub the dialog model),
 * so until now the query that decides "pinned by no OTHER live pipeline version" ran only in
 * production. The owner's own scenario — two draft pipelines sharing one draft template — is the
 * case that matters: the shared template must stay while the other pipeline lives and be offered
 * once it is gone (2026-09-13).
 *
 * Pipelines are seeded by SQL (the same shape `VersionLifecycleModelTest` uses): a node pins a
 * template by `template.id`, exactly as a real body does. Same real-Postgres harness as
 * [TemplateRepositoryIntegrationTest]; this suite cleans the tables it touches.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExclusiveDraftTemplatesIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: TemplateRepository
    private lateinit var actor: UUID

    private val workspaceId: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = TemplateRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE templates, pipelines, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default')",
        )
        actor =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject)" +
                        " VALUES ('owner@example.com', 'Owner', 'google', 'sub-1') RETURNING id",
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )
    }

    @Test
    fun `a draft template pinned only by the purged pipeline is offered`() {
        draftTemplate("demo/lake/only_mine.sql")
        val mine = pipeline("demo/p1", "DRAFT", pins = listOf("demo/lake/only_mine.sql"))

        repository.exclusiveDraftTemplateIds(workspaceId, mine) shouldContainExactly listOf("demo/lake/only_mine.sql")
    }

    @Test
    fun `a template shared with another live pipeline stays, and is offered once that pipeline is gone`() {
        // The owner's scenario: two draft pipelines pin one draft lookup template.
        draftTemplate("demo/reference/boroughs.sql")
        draftTemplate("demo/lake/only_p1.sql")
        draftTemplate("demo/answer/only_p2.sql")
        val p1 = pipeline("demo/p1", "DRAFT", pins = listOf("demo/reference/boroughs.sql", "demo/lake/only_p1.sql"))
        val p2 = pipeline("demo/p2", "DRAFT", pins = listOf("demo/reference/boroughs.sql", "demo/answer/only_p2.sql"))

        withClue("purging p1 offers only what nothing else pins — the shared lookup is p2's too") {
            repository.exclusiveDraftTemplateIds(workspaceId, p1) shouldContainExactly listOf("demo/lake/only_p1.sql")
        }
        withClue("a RELEASED pinner protects exactly like a draft one") {
            pipeline("demo/p3", "RELEASED", pins = listOf("demo/lake/only_p1.sql"))
            repository.exclusiveDraftTemplateIds(workspaceId, p1).shouldBeEmpty()
        }
        // p1 goes (the entity purge deletes the row; versions cascade). Now the lookup is p2's alone.
        jdbc.update("DELETE FROM pipelines WHERE id = :id", mapOf("id" to p1))
        withClue("with p1 gone, purging p2 offers the shared lookup as well as its own") {
            repository.exclusiveDraftTemplateIds(workspaceId, p2) shouldContainExactly
                listOf("demo/answer/only_p2.sql", "demo/reference/boroughs.sql")
        }
    }

    @Test
    fun `a template with a released version is never offered, however exclusively it is pinned`() {
        releasedTemplate("demo/lake/shipped.sql")
        draftTemplate("demo/lake/unshipped.sql")
        val mine = pipeline("demo/p1", "DRAFT", pins = listOf("demo/lake/shipped.sql", "demo/lake/unshipped.sql"))

        repository.exclusiveDraftTemplateIds(workspaceId, mine) shouldContainExactly listOf("demo/lake/unshipped.sql")
    }

    @Test
    fun `a name that merely appears in another pipeline's prose is not a pin`() {
        draftTemplate("demo/lake/only_mine.sql")
        val mine = pipeline("demo/p1", "DRAFT", pins = listOf("demo/lake/only_mine.sql"))
        pipeline("demo/p2", "DRAFT", pins = emptyList(), description = "see demo/lake/only_mine.sql for the idiom")

        repository.exclusiveDraftTemplateIds(workspaceId, mine) shouldContainExactly listOf("demo/lake/only_mine.sql")
    }

    /**
     * Owner ruling R12 (2026-09-13): a DISCARDED version of another pipeline protects the
     * template exactly like a live one — a discarded version can be restored (D59: "restore
     * always works"), and restore would lose its meaning if the templates it runs could be
     * purged out from under it. Before the ruling the query counted only DRAFT/RELEASED pinners.
     */
    @Test
    fun `a pin held only by another pipeline's DISCARDED version protects the template - restore must keep what it runs`() {
        draftTemplate("demo/lake/only_live.sql")
        draftTemplate("demo/lake/truly_mine.sql")
        val mine = pipeline("demo/p1", "DRAFT", pins = listOf("demo/lake/only_live.sql", "demo/lake/truly_mine.sql"))
        pipeline("demo/p2", "DISCARDED", pins = listOf("demo/lake/only_live.sql"))

        repository.exclusiveDraftTemplateIds(workspaceId, mine) shouldContainExactly listOf("demo/lake/truly_mine.sql")
    }

    // ------------------------------------------------------------------ fixtures

    private fun draftTemplate(id: String): Template =
        repository.create(workspaceId, templateDraft(id), actor, CreateLifecycle.DRAFT, WriteSurface.SESSION)

    private fun releasedTemplate(id: String) {
        val draft = draftTemplate(id)
        checkNotNull(repository.releaseDraft(workspaceId, id, draft.bodyHash, actor))
    }

    private fun templateDraft(id: String) =
        TemplateDraft(
            id = id,
            dialect = Dialect.POSTGRES,
            displayName = id,
            description = "fixture",
            imports = emptyList(),
            body = "SELECT 1",
            isLibrary = false,
        )

    /** One pipeline with one version in [status], whose nodes pin [pins] at version 1. */
    private fun pipeline(
        name: String,
        status: String,
        pins: List<String>,
        description: String = "d",
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version)" +
                " VALUES (:id, :name, 'P', :description, :owner, :ws, NULL)",
            mapOf("id" to id, "name" to name, "description" to description, "owner" to actor, "ws" to workspaceId),
        )
        val nodes =
            pins.joinToString(",") { pin ->
                """{"id":"n_${pin.hashCode().toUInt()}","type":"DQL","source":"s","template":{"id":"$pin","version":1},"depends_on":[]}"""
            }
        val body =
            """{"schema_version":1,"name":"$name","display_name":"P","description":"$description",""" +
                """"parameters":{},"nodes":[$nodes]}"""
        jdbc.update(
            """
            INSERT INTO pipeline_versions
                (pipeline_id, version, body_json, body_hash, status, created_by,
                 released_at, released_by, discarded_at, discarded_by)
            VALUES (:id, 1, CAST(:body AS jsonb),
                    encode(sha256(convert_to(CAST(:body AS jsonb)::text, 'UTF8')), 'hex'),
                    :status, :owner,
                    CASE WHEN :status IN ('RELEASED','DISCARDED') THEN NOW() END,
                    CASE WHEN :status IN ('RELEASED','DISCARDED') THEN :owner END,
                    CASE WHEN :status = 'DISCARDED' THEN NOW() END,
                    CASE WHEN :status = 'DISCARDED' THEN :owner END)
            """.trimIndent(),
            mapOf("id" to id, "body" to body, "status" to status, "owner" to actor),
        )
        return id
    }
}
