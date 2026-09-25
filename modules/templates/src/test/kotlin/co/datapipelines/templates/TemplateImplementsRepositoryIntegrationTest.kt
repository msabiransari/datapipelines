package co.datapipelines.templates

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.RetiredFactCitation
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * [TemplateImplementsRepository] against the shipped schema ([SharedPostgres]): the citation
 * write (`replace`, `copy`), the §8.2 retired read (`retiredCitations`) and the §8.3 reverse
 * read (`implementedBy`) under both lens shapes. The facts are seeded as rows — this module
 * owns no fact store, only the [CitableFacts] seam — with the recorder's exact stamps.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateImplementsRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var templates: TemplateRepository
    private lateinit var citations: TemplateImplementsRepository
    private lateinit var actor: UUID

    /** The V4-seeded `default` workspace, re-seeded after every truncate — a pinned literal, not a guess. */
    private val workspaceId: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    }

    @BeforeEach
    fun setUp() {
        templates = TemplateRepository(jdbc)
        citations = TemplateImplementsRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE templates, learned_facts, datasources, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name) VALUES ('$workspaceId', 'default', 'Default') ON CONFLICT (id) DO NOTHING",
        )
        actor = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to actor, "email" to "u$actor@example.com", "sub" to "sub-$actor"),
        )
        jdbc.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by) " +
                "VALUES ('warehouse', 'Warehouse', 'POSTGRES', 'jdbc:postgresql://x/y', 'none', :actor)",
            mapOf("actor" to actor),
        )
    }

    // ---------------------------------------------------------------- fixtures

    private fun draft(
        id: String,
        body: String = "SELECT 1",
    ): TemplateDraft =
        TemplateDraft(
            id = id,
            dialect = Dialect.POSTGRES,
            displayName = "Fixture",
            description = "A fixture.",
            imports = emptyList(),
            body = body,
            isLibrary = false,
        )

    /** Version 1 created as a DRAFT and released — the shape the import path and the seeders land. */
    private fun released(id: String): Template {
        val created = templates.create(workspaceId, draft(id), actor, CreateLifecycle.DRAFT, WriteSurface.SESSION)
        return checkNotNull(templates.releaseDraft(workspaceId, id, created.bodyHash, actor)).let { created }
    }

    /** A new DRAFT version on top of the release — the first write after a release opens it. */
    private fun draftVersion(released: Template): Int =
        checkNotNull(
            templates.createDraft(workspaceId, released.id, draft(released.id, "SELECT 2"), released.bodyHash, actor, WriteSurface.SESSION),
        ).version

    /** A WORKSPACE definition fact as the recorder inserts it (asserted, no refs, recorded over MCP). */
    private fun fact(
        text: String,
        supersedes: UUID? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO learned_facts (id, scope, workspace_id, datasource_name, kind, fact, refs_json, trust, schema_fingerprint,
                                       recorded_by, recorded_via, recorded_in, supersedes)
            VALUES (:id, 'WORKSPACE', :ws, 'warehouse', 'definition', :fact, '[]'::jsonb, 'asserted', 'orders=seed',
                    :actor, 'mcp', :ws, :supersedes)
            """.trimIndent(),
            mapOf("id" to id, "ws" to workspaceId, "fact" to text, "actor" to actor, "supersedes" to supersedes),
        )
        return id
    }

    /** The recorder's retirement: the trust and the stamp together, as the CHECK demands. */
    private fun retire(
        id: UUID,
        reason: String,
    ) {
        jdbc.update(
            "UPDATE learned_facts SET trust = 'retired', retired_at = NOW(), retired_reason = :reason WHERE id = :id",
            mapOf("id" to id, "reason" to reason),
        )
    }

    private fun citationRows(): Int = jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM template_implements", Int::class.java)!!

    // ---------------------------------------------------------------- the write

    @Test
    fun `replace makes the list the citations, an empty list clears, and a missing version is a no-op`() {
        val rule = fact("rainy = precipitation_mm >= 2.5")
        val exclusion = fact("tips are excluded from revenue")
        val name = released("acme/shape/order_lines.jsonata").id

        citations.replace(workspaceId, name, 1, listOf(rule, exclusion, rule))
        citations.implementedBy(workspaceId, ReadLens.Everything, listOf(rule, exclusion)) shouldBe
            mapOf(rule to listOf(ImplementingVersion(name, 1)), exclusion to listOf(ImplementingVersion(name, 1)))

        citations.replace(workspaceId, name, 1, listOf(exclusion))
        citations.implementedBy(workspaceId, ReadLens.Everything, listOf(rule, exclusion)) shouldBe
            mapOf(exclusion to listOf(ImplementingVersion(name, 1)))

        citations.replace(workspaceId, name, 1, emptyList())
        citationRows() shouldBe 0

        citations.replace(workspaceId, name, 99, listOf(rule))
        citationRows() shouldBe 0
    }

    @Test
    fun `copy carries a version's citations onto another version of the same template`() {
        val rule = fact("rainy = precipitation_mm >= 2.5")
        val template = released("acme/shape/order_lines.jsonata")
        citations.replace(workspaceId, template.id, 1, listOf(rule))
        val v2 = draftVersion(template)

        citations.copy(workspaceId, template.id, 1, v2) shouldBe 1
        citations.implementedBy(workspaceId, ReadLens.Everything, listOf(rule)).getValue(rule) shouldContainExactly
            listOf(ImplementingVersion(template.id, 1), ImplementingVersion(template.id, v2))
    }

    // ---------------------------------------------------------------- §8.2 the retired read

    @Test
    fun `retiredCitations names each pin's retired facts with the successor, and a clean pin is absent`() {
        val old = fact("rainy = precipitation_mm >= 2.5")
        val live = fact("tips are excluded from revenue")
        val marked = released("acme/shape/order_lines.jsonata").id
        val clean = released("acme/shape/refunds.jsonata").id
        citations.replace(workspaceId, marked, 1, listOf(old, live))
        citations.replace(workspaceId, clean, 1, listOf(live))
        val successor = fact("rainy = precipitation_mm >= 1.0", supersedes = old)
        retire(old, "superseded")

        val answer =
            citations.retiredCitations(
                workspaceId,
                listOf(TemplateRef(marked, 1), TemplateRef(clean, 1), TemplateRef(marked, 99), TemplateRef(marked, 1)),
            )

        answer shouldBe
            mapOf(TemplateRef(marked, 1) to listOf(RetiredFactCitation(old.toString(), "superseded", successor.toString())))
        citations.retiredCitations(workspaceId, emptyList()).shouldBeEmpty()
    }

    // ---------------------------------------------------------------- §8.3 the reverse read

    @Test
    fun `implementedBy lists live versions under Everything and only released ones under a narrowing lens`() {
        val rule = fact("rainy = precipitation_mm >= 2.5")
        val nobody = fact("a rule no transform implements")
        val template = released("acme/shape/order_lines.jsonata")
        citations.replace(workspaceId, template.id, 1, listOf(rule))
        val v2 = draftVersion(template)
        citations.copy(workspaceId, template.id, 1, v2)

        citations.implementedBy(workspaceId, ReadLens.Everything, listOf(rule, nobody)) shouldBe
            mapOf(rule to listOf(ImplementingVersion(template.id, 1), ImplementingVersion(template.id, v2)))
        citations.implementedBy(workspaceId, ReadLens.Only(setOf(template.id)), listOf(rule)) shouldBe
            mapOf(rule to listOf(ImplementingVersion(template.id, 1)))
        citations.implementedBy(workspaceId, ReadLens.Only(setOf("acme/other.jsonata")), listOf(rule)).shouldBeEmpty()
        citations.implementedBy(workspaceId, ReadLens.NOTHING, listOf(rule)).shouldBeEmpty()
        citations.implementedBy(workspaceId, ReadLens.Everything, emptyList()).shouldBeEmpty()
    }
}
