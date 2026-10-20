package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateService
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.web.SharedPostgres
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import java.time.Instant
import java.util.UUID

/**
 * #579 A.6 — the Templates overview's equality table, [OverviewE2eTest]'s twin: the templates
 * catalog's `lifecycle` filter and the overview's inventory read the SAME bucket definition
 * ([LifecycleBucket]), so on one fixture `count(catalog ?lifecycle=X)` equals the inventory's X
 * for every offered value and every lens. The catalog goes through [TemplateService.list]/[count]
 * (the page's own reads); the inventory through UiConfig's production [FamilyInventory].
 *
 * Discarded-only: asserted at the repository, because the templates catalog does not offer
 * `discarded` (a discarded-only template's workspace route resolves live names, #579) — the
 * bucket REPLACES the liveness predicate there exactly as the pipelines catalog's does.
 * The promoter fixture holds no pointer-on-draft object: the narrowed inventory admits any
 * RELEASED version, the catalog's D55 pointer rule is its own.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [OverviewTemplatesE2eTest.OverviewTemplatesConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
class OverviewTemplatesE2eTest {
    @Autowired
    private lateinit var inventory: FamilyInventory

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private val repository by lazy { TemplateRepository(jdbc) }
    private val service: TemplateService by lazy { TemplateService(repository) }
    private val workspace = UUID.randomUUID()
    private val otherWorkspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    @BeforeEach
    fun seedContext() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'Overview', 'google', :email)",
            mapOf("id" to actor, "email" to "$actor@overview-templates.test"),
        )
        listOf(workspace, otherWorkspace).forEach { id ->
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name) VALUES (:id, :name, 'Overview')",
                mapOf(
                    "id" to id,
                    "name" to "overview_tpl_$id",
                ),
            )
        }
        insert("a/draft", listOf("DRAFT"))
        insert("a/discarded_draft", listOf("DISCARDED", "DRAFT"))
        insert("b/released", listOf("RELEASED"))
        insert("b/released_twice", listOf("RELEASED", "RELEASED"))
        insert("b/released_discarded", listOf("RELEASED", "DISCARDED"))
        insert("c/both", listOf("RELEASED", "DRAFT"))
        insert("c/both_hidden_draft", listOf("RELEASED", "DRAFT"))
        insert("d/discarded", listOf("DISCARDED"))
        insert("d/discarded_twice", listOf("DISCARDED", "DISCARDED"))
        insert("b/released", listOf("DRAFT"), otherWorkspace)
    }

    @AfterEach
    fun cleanOwnRows() {
        val params = mapOf("ids" to listOf(workspace, otherWorkspace))
        jdbc.update("DELETE FROM templates WHERE workspace_id IN (:ids)", params)
        jdbc.update("DELETE FROM workspaces WHERE id IN (:ids)", params)
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to actor))
    }

    @Test
    fun `every offered lifecycle value lists exactly the inventory's bucket, unlensed`() {
        val counted = counted(ReadLens.Everything)
        counted shouldBe Inventory(draftOnly = 2, releasedOnly = 3, releasedWithDraft = 2, discardedOnly = 2)

        val table = equalityTable(ReadLens.Everything, counted)
        table.forEach { row -> withClue(row.toString()) { row.listed shouldBe row.counted } }
        println(
            "event=overview.a6.equality family=templates lens=everything " +
                table.joinToString(" ") { "${it.bucket.wire}=${it.listed}/${it.counted}" },
        )
        names(ReadLens.Everything, LifecycleBucket.DRAFT_ONLY) shouldBe setOf("a/draft", "a/discarded_draft")
        names(ReadLens.Everything, LifecycleBucket.RELEASED_WITH_DRAFT) shouldBe setOf("c/both", "c/both_hidden_draft")
        withClue("the plain catalog is the live objects: the inventory without its discarded-only") {
            service.count(workspace, ReadLens.Everything).toLong() shouldBe counted.objects - counted.discardedOnly
        }
    }

    /**
     * A promoter's lens admits five names; `c/both_hidden_draft`'s DRAFT is not admitted (the
     * narrowed read is RELEASED rows only), so it is released-only for them and absent from every
     * draft value — on the templates catalog exactly as in the inventory.
     */
    @Test
    fun `every offered lifecycle value lists exactly the inventory's bucket, through a narrowed lens`() {
        val lens = ReadLens.Only(setOf("a/draft", "b/released", "c/both_hidden_draft", "d/discarded", "absent/name"))
        val counted = counted(lens)
        counted shouldBe Inventory(draftOnly = 0, releasedOnly = 2, releasedWithDraft = 0, discardedOnly = 0)

        val table = equalityTable(lens, counted)
        table.forEach { row -> withClue(row.toString()) { row.listed shouldBe row.counted } }
        println(
            "event=overview.a6.equality family=templates lens=narrowed " +
                table.joinToString(" ") { "${it.bucket.wire}=${it.listed}/${it.counted}" },
        )
        names(lens, LifecycleBucket.RELEASED_ONLY) shouldBe setOf("b/released", "c/both_hidden_draft")
        names(lens, LifecycleBucket.WITH_DRAFTS) shouldBe emptySet()
        names(lens, LifecycleBucket.DRAFT_ONLY) shouldBe emptySet()
    }

    @Test
    fun `discarded-only replaces the liveness predicate, so the repository's count is the inventory's`() {
        val counted = counted(ReadLens.Everything)

        repository.count(workspace, lifecycle = LifecycleBucket.DISCARDED_ONLY).toLong() shouldBe counted.discardedOnly
        repository.list(workspace, lifecycle = LifecycleBucket.DISCARDED_ONLY).map { it.id }.toSet() shouldBe
            setOf("d/discarded", "d/discarded_twice")
        withClue("a narrowed read sees RELEASED rows only, so nothing is discarded-only through a lens") {
            repository.list(workspace, lifecycle = LifecycleBucket.DISCARDED_ONLY, releasedOnly = true) shouldBe emptyList()
        }
    }

    @Test
    fun `the filtered catalog pages with a truthful total`() {
        val bucket = LifecycleBucket.RELEASED_ONLY
        val first = service.list(workspace, ReadLens.Everything, offset = 0, limit = 2, lifecycle = bucket)
        val second = service.list(workspace, ReadLens.Everything, offset = 2, limit = 2, lifecycle = bucket)

        service.count(workspace, ReadLens.Everything, lifecycle = bucket) shouldBe 3
        first shouldHaveSize 2
        second shouldHaveSize 1
        (first + second).map { it.id }.toSet() shouldBe setOf("b/released", "b/released_twice", "b/released_discarded")
        withClue("the lensed path pages in memory over the same filtered rows") {
            service.count(workspace, ReadLens.Only(setOf("b/released", "b/released_twice")), lifecycle = bucket) shouldBe 2
        }
    }

    private data class Row(
        val bucket: LifecycleBucket,
        val listed: Long,
        val counted: Long,
    )

    /**
     * #599 A.2 — the Templates home from a real read: the chart group renders a strip whose four exclusive
     * groups sum to Total, the inventory's objects, and the page carries the family's guide words, the
     * starter prompt equal to today's text. RED at the base: the family had no chart group there.
     */
    @Test
    fun `the strip's four metrics sum to Total and the page carries the family's words`() {
        val now = Instant.now()
        val charts =
            inventory.chartsFor(
                NavigationFamily.TEMPLATES,
                workspace,
                LensedView.EVERYTHING,
                now,
                OverviewRenderSupport.ACTIVE_DAYS,
            )
        val page = OverviewPage.of(OverviewPage.Family.TEMPLATES, false, charts, RecentResult.Available(emptyList()), now)
        val doc = Jsoup.parse(OverviewRenderSupport.html(page))

        OverviewRenderSupport.assertStripSumsTo(doc, counted(ReadLens.Everything).objects)
        OverviewRenderSupport.assertGuideWords(doc, OverviewPage.Family.TEMPLATES, WORDS)
    }

    private fun equalityTable(
        lens: ReadLens,
        counted: Inventory,
    ): List<Row> =
        CatalogLifecycle.TEMPLATES.map { bucket ->
            Row(bucket, service.count(workspace, lens, lifecycle = bucket).toLong(), bucket.countIn(counted))
        }

    private fun names(
        lens: ReadLens,
        bucket: LifecycleBucket,
    ): Set<String> =
        service
            .list(workspace, lens, offset = 0, limit = PAGE, lifecycle = bucket)
            .map { it.id }
            .toSet()

    private fun counted(lens: ReadLens): Inventory =
        (inventory.forFamily(NavigationFamily.TEMPLATES, workspace, view(lens)) as InventoryResult.Available).inventory

    private fun view(templates: ReadLens): LensedView =
        if (templates == ReadLens.Everything) {
            LensedView.EVERYTHING
        } else {
            LensedView(
                pipelines = ReadLens.NOTHING,
                templates = templates,
                parameterSets = ReadLens.NOTHING,
                visualizations = ReadLens.NOTHING,
                dashboards = ReadLens.NOTHING,
            )
        }

    private fun insert(
        name: String,
        statuses: List<String>,
        workspaceId: UUID = workspace,
    ) {
        val id = UUID.randomUUID()
        val current = statuses.indexOfLast { it == "RELEASED" }.takeIf { it >= 0 }?.plus(1)
        jdbc.update(
            "INSERT INTO templates (id, workspace_id, name, display_name, current_version, created_by) " +
                "VALUES (:id, :workspace, :name, :name, :current, :actor)",
            mapOf("id" to id, "workspace" to workspaceId, "name" to name, "current" to current, "actor" to actor),
        )
        statuses.forEachIndexed { index, status ->
            jdbc.update(
                "INSERT INTO template_versions " +
                    "(template_id, version, engine, type, dialect, is_library, imports_json, body, " +
                    "status, body_hash, created_by, released_at, released_by, discarded_at, discarded_by) " +
                    "VALUES (:id, :version, 'freemarker', 'sql', 'POSTGRES', FALSE, '[]'::jsonb, '{}'::text, " +
                    ":status, :hash, :actor, " +
                    "CASE WHEN :status <> 'DRAFT' THEN NOW() END, CASE WHEN :status <> 'DRAFT' THEN :actor END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN NOW() END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN :actor END)",
                mapOf("id" to id, "version" to index + 1, "status" to status, "hash" to "a".repeat(64), "actor" to actor),
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    class OverviewTemplatesConfiguration {
        @Bean
        fun dataSource(): HikariDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = SharedPostgres.postgres.jdbcUrl
                    username = SharedPostgres.postgres.username
                    password = SharedPostgres.postgres.password
                    maximumPoolSize = 2
                },
            )

        @Bean
        fun jdbc(source: javax.sql.DataSource): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(source)

        @Bean
        fun inventory(jdbc: NamedParameterJdbcTemplate): FamilyInventory =
            UiConfig().familyInventory(
                PipelineRepository(jdbc),
                TemplateRepository(jdbc),
                DashboardRepository(jdbc),
                VisualizationRepository(jdbc),
                ParameterSetRepository(jdbc),
            )
    }

    private companion object {
        /**
         * #599 — the templates guide's words; [OverviewRenderSupport.GuideWords.prompt] is the
         * textarea's text at the base, byte for byte.
         */
        val WORDS =
            OverviewRenderSupport.GuideWords(
                subtitle = "Reuse query and transformation logic across requests. Understand what’s live and what’s next.",
                headline = "From a query to a reusable template.",
                sentence =
                    "Write the logic once with parameters, so the same template answers different requests.",
                steps =
                    listOf(
                        "Describe" to "The query, its inputs and its dialect.",
                        "Build" to "Your agent creates a draft and renders it.",
                        "Review" to "Try it with your own inputs. Release when ready.",
                    ),
                prompt =
                    "In workspace acme, create or reuse a draft template for [query or transformation]. Its inputs are " +
                        "[parameters/input shape] and its output should be [columns or structure]. Read the current template " +
                        "guide, inspect the source or input contract, and use the appropriate supported dialect or engine. " +
                        "Render or test it with representative inputs and explain the output contract and limitations. Return " +
                        "its name/version, draft link and validation evidence. Leave release to me.",
                howItWorksTitle = "Create, render, then release",
                howItWorks =
                    "Templates hold reusable query or transformation logic that other resources can use. Parameters let " +
                        "the same logic answer different requests without duplicating it. For example, aggregate trips by " +
                        "zone for a selected period.",
                related = listOf("/overview/pipelines"),
            )

        const val PAGE = 100
    }
}
