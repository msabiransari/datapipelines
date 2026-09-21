package co.datapipelines.templates

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * [TemplateService] against the real schema (178): under [ReadLens.Everything] every read is
 * the repository's own answer; under a narrowing lens lists, counts and tree levels are the
 * admitted subset — derived in memory by the repository's rules — and a hidden id answers
 * exactly as an absent one. [TemplateRepository.findCurrentVersions] is pinned here too: it is
 * the lens's input and must exclude the never-released.
 *
 * ## Falsifying it
 * Drop the `lens.admits(id)` guard from `findLatest` and `a hidden id answers as an absent one`
 * goes red; drop the `startsWith(scope)` filter from `listChildFolders` and the level test
 * names the foreign folder.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateServiceIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: TemplateRepository
    private lateinit var service: TemplateService
    private lateinit var actor: UUID

    private val workspaceId: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

    /** The lens admits one of the two finance templates and the ops one — nothing from `hr/` and no draft. */
    private val lens = ReadLens.Only(setOf("finance/daily.sql", "ops/nightly.sql"))

    @BeforeAll
    fun seed() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        repository = TemplateRepository(jdbc)
        service = TemplateService(repository)
        jdbc.jdbcTemplate.execute("TRUNCATE templates, users CASCADE")
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
        listOf("finance/daily.sql", "finance/weekly.sql", "finance/reports/q1.sql", "ops/nightly.sql", "hr/payroll.sql")
            .forEach { create(it, CreateLifecycle.RELEASED) }
        // D55: authoring lands v1 DRAFT with a NULL pointer — never promotable, never in the lens's input.
        create("ops/draft_only.sql", CreateLifecycle.DRAFT)
    }

    @Test
    fun `Everything is the repository's own answer, read for read`() {
        service.list(workspaceId, ReadLens.Everything).map { it.id } shouldContainExactly
            repository.list(workspaceId).map { it.id }
        service.count(workspaceId, ReadLens.Everything) shouldBe repository.count(workspaceId)
        service.listChildFolders(workspaceId, ReadLens.Everything) shouldBe repository.listChildFolders(workspaceId)
        service.listChildTemplates(workspaceId, ReadLens.Everything, "finance").map { it.id } shouldContainExactly
            repository.listChildTemplates(workspaceId, "finance").map { it.id }
        service.countChildTemplates(workspaceId, ReadLens.Everything, "finance") shouldBe 2
        service.findLatest(workspaceId, ReadLens.Everything, "hr/payroll.sql").shouldNotBeNull()
        service.findWorking(workspaceId, ReadLens.Everything, "ops/draft_only.sql").shouldNotBeNull()
        service.existsId(workspaceId, ReadLens.Everything, "hr/payroll.sql") shouldBe true
    }

    @Test
    fun `a narrowing lens keeps the admitted rows in lists and counts, with the truthful total`() {
        service.list(workspaceId, lens).map { it.id } shouldContainExactly listOf("finance/daily.sql", "ops/nightly.sql")
        service.count(workspaceId, lens) shouldBe 2
        withClue("the list filters still apply under the lens (q, dialect, type)") {
            service.list(workspaceId, lens, q = "nightly").map { it.id } shouldContainExactly listOf("ops/nightly.sql")
            service.count(workspaceId, lens, q = "nightly") shouldBe 1
            service.list(workspaceId, lens, type = TemplateType.HTML).shouldBeEmpty()
        }
        withClue("paging is over the admitted rows") {
            service.list(workspaceId, lens, offset = 1, limit = 1).map { it.id } shouldContainExactly listOf("ops/nightly.sql")
            service.list(workspaceId, lens, offset = 2, limit = 1).shouldBeEmpty()
        }
        service.list(workspaceId, ReadLens.NOTHING).shouldBeEmpty()
        service.count(workspaceId, ReadLens.NOTHING) shouldBe 0
    }

    @Test
    fun `a narrowing lens derives the tree level from the admitted set - folder counts follow the lens`() {
        withClue("the root: a folder per admitted first segment, counted over the admitted subtree only") {
            service.listChildFolders(workspaceId, lens) shouldContainExactly
                listOf(TemplateFolder("finance", "finance", templateCount = 1), TemplateFolder("ops", "ops", templateCount = 1))
        }
        withClue("a folder level: only the admitted direct leaves, and no sub-folder whose subtree is all hidden") {
            service.listChildTemplates(workspaceId, lens, "finance").map { it.id } shouldContainExactly listOf("finance/daily.sql")
            service.countChildTemplates(workspaceId, lens, "finance") shouldBe 1
            service.listChildFolders(workspaceId, lens, "finance").shouldBeEmpty()
            service.listChildTemplates(workspaceId, lens, "hr").shouldBeEmpty()
            service.countChildTemplates(workspaceId, lens, "hr") shouldBe 0
        }
        withClue("scope is `prefix/` — a sibling sharing the prefix's letters is out") {
            create("fin/other.sql", CreateLifecycle.RELEASED)
            service
                .listChildTemplates(workspaceId, ReadLens.Only(setOf("fin/other.sql", "finance/daily.sql")), "fin")
                .map { it.id } shouldContainExactly listOf("fin/other.sql")
        }
    }

    @Test
    fun `a hidden id answers exactly as an absent one, on every id-keyed read`() {
        listOf("finance/weekly.sql", "hr/payroll.sql", "does/not/exist.sql").forEach { id ->
            withClue(id) {
                service.findLatest(workspaceId, lens, id).shouldBeNull()
                service.findWorking(workspaceId, lens, id).shouldBeNull()
                service.findVersion(workspaceId, lens, id, 1).shouldBeNull()
                service.existsId(workspaceId, lens, id) shouldBe false
                service.listVersions(workspaceId, lens, id).shouldBeEmpty()
                service.findVersionDetail(workspaceId, lens, id, 1).shouldBeNull()
                service.findDraftDetail(workspaceId, lens, id).shouldBeNull()
                service.findVersionStatus(workspaceId, lens, id, 1).shouldBeNull()
            }
        }
        withClue("and the admitted one reads exactly as without a lens") {
            service.findLatest(workspaceId, lens, "finance/daily.sql") shouldBe repository.findLatest(workspaceId, "finance/daily.sql")
            service.listVersions(workspaceId, lens, "finance/daily.sql").size shouldBe 1
            service.findVersionStatus(workspaceId, lens, "finance/daily.sql", 1).shouldNotBeNull()
        }
    }

    @Test
    fun `findCurrentVersions is the lens's input - released pointers only, never a draft`() {
        val current = repository.findCurrentVersions(workspaceId)

        current.map { it.id }.contains("ops/draft_only.sql") shouldBe false
        current.map { it.id }.containsAll(listOf("finance/daily.sql", "hr/payroll.sql", "ops/nightly.sql")) shouldBe true
        val daily = current.single { it.id == "finance/daily.sql" }
        daily.version shouldBe 1
        daily.bodyHash shouldBe checkNotNull(repository.findLatest(workspaceId, "finance/daily.sql")).bodyHash
        daily.displayName shouldBe "finance/daily.sql"
        withClue("`list` DOES carry the draft-only template (D55's COALESCE) — which is why the lens has its own read") {
            repository.list(workspaceId, limit = TemplateRepository.MAX_PAGE_LIMIT).map { it.id }.contains("ops/draft_only.sql") shouldBe
                true
        }
    }

    private fun create(
        name: String,
        lifecycle: CreateLifecycle,
    ): Template =
        repository.create(
            workspaceId,
            TemplateDraft(
                id = name,
                type = TemplateType.SQL,
                dialect = Dialect.POSTGRES,
                displayName = name,
                description = "Fixture.",
                body = "SELECT 1",
            ),
            actor,
            lifecycle,
            WriteSurface.SESSION,
        )
}
