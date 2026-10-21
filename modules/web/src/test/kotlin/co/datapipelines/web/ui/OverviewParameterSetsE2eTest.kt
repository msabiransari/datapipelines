package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.web.SharedPostgres
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
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
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * #582 — the Parameter Sets overview over a real Postgres: the inventory (UiConfig's production
 * [FamilyInventory]), the catalog's `lifecycle` filter (the production [ParameterSetsBrowseModel]
 * over [ParameterSetService], exactly what `GET /parameter-sets?lifecycle=` fills), Recently
 * updated (UiConfig's [FamilyRecent]) and the browser header's count ([NavCounts]) read one
 * fixture, so `count(catalog ?lifecycle=X) == inventory.X` for every offered value under the
 * everything lens and a narrowed one, and the hidden-draft pair holds on both surfaces.
 *
 * The narrowed fixture holds a DRAFT-POINTER object (`c/draft_pointer`: v1 RELEASED, v2 DRAFT,
 * the pointer on v2 — D60's development-posture pointer): the filtered catalog takes the
 * inventory's rule (any RELEASED row, `InventorySource.membership`), so it is counted and listed at
 * its RELEASED v1; the UNFILTERED lensed catalog keeps its own current-pointer rule
 * (`findCurrentVersions`) and omits it — pinned below as the reading the handback reports, not
 * changed here (#582 amendment 1 item 6). The production promoter lens never admits such a name
 * (`PromotableView` keeps a RELEASED pointer only), so the lens here is hand-built.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [OverviewParameterSetsE2eTest.OverviewParameterSetsConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
class OverviewParameterSetsE2eTest {
    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate
    private val repository by lazy { ParameterSetRepository(jdbc) }
    private val browse by lazy { ParameterSetsBrowseModel(ParameterSetService(repository, mockk(), mockk(), mockk(), mockk())) }
    private val inventory by lazy {
        UiConfig().familyInventory(
            PipelineRepository(jdbc),
            TemplateRepository(jdbc),
            DashboardRepository(jdbc),
            VisualizationRepository(jdbc),
            repository,
        )
    }
    private val recent by lazy {
        UiConfig().familyRecent(
            PipelineRepository(jdbc),
            TemplateRepository(jdbc),
            DashboardRepository(jdbc),
            VisualizationRepository(jdbc),
            repository,
        )
    }
    private val workspace = UUID.randomUUID()
    private val minimal = UUID.randomUUID()
    private val recentWorkspace = UUID.randomUUID()
    private val otherWorkspace = UUID.randomUUID()
    private val workspaces = listOf(workspace, minimal, recentWorkspace, otherWorkspace)
    private val actor = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'Overview', 'google', :email)",
            mapOf("id" to actor, "email" to "$actor@overview582.test"),
        )
        workspaces.forEach { id ->
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name) VALUES (:id, :name, 'Overview 582')",
                mapOf("id" to id, "name" to "overview582_$id"),
            )
        }
        insert("a/draft", listOf(v("DRAFT")))
        insert("a/discarded_draft", listOf(v("DISCARDED"), v("DRAFT")))
        insert("b/released", listOf(v("RELEASED")))
        insert("b/released_twice", listOf(v("RELEASED"), v("RELEASED")))
        insert("b/released_discarded", listOf(v("RELEASED"), v("DISCARDED")))
        insert("c/both", listOf(v("RELEASED"), v("DRAFT")))
        insert("c/both_hidden_draft", listOf(v("RELEASED"), v("DRAFT")))
        insert("c/draft_pointer", listOf(v("RELEASED"), v("DRAFT")), current = 2)
        insert("d/discarded", listOf(v("DISCARDED")))
        insert("d/discarded_twice", listOf(v("DISCARDED"), v("DISCARDED")))
        insert("b/released", listOf(v("DRAFT")), otherWorkspace)

        // The record's §8 fixture: draft-only, released-only (released twice), both, discarded-only.
        insert("m/draft", listOf(v("DRAFT")), minimal)
        insert("m/released", listOf(v("RELEASED"), v("RELEASED")), minimal)
        insert("m/both", listOf(v("RELEASED"), v("DRAFT")), minimal)
        insert("m/discarded", listOf(v("DISCARDED")), minimal)

        // Recently updated, with explicit ORDERED times (hours after T0): a draft edited at 5 after a
        // release at 3; a released set whose later draft was edited at 6; a discarded-only set at 7.
        insert("r/edited_draft", listOf(v("DRAFT", created = at(1), updated = at(5))), recentWorkspace, setUpdated = at(1))
        insert("r/released", listOf(v("RELEASED", created = at(2), released = at(3))), recentWorkspace, setUpdated = at(3))
        insert(
            "r/hidden",
            listOf(v("RELEASED", created = at(0), released = at(0)), v("DRAFT", created = at(4), updated = at(6))),
            recentWorkspace,
            setUpdated = at(0),
        )
        insert(
            "r/discarded",
            listOf(v("DISCARDED", created = at(1), released = at(1), discarded = at(7))),
            recentWorkspace,
            setUpdated = at(7),
        )
    }

    @AfterEach
    fun cleanOwnRows() {
        val params = mapOf("ids" to workspaces)
        jdbc.update("DELETE FROM parameter_sets WHERE workspace_id IN (:ids)", params)
        jdbc.update("DELETE FROM workspaces WHERE id IN (:ids)", params)
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to actor))
    }

    @Test
    fun `the record's fixture counts 4, 2, 2 and 1 for a viewer, several releases once, and the admitted half for a promoter`() {
        val viewer = counted(ReadLens.Everything, minimal)
        viewer shouldBe Inventory(draftOnly = 1, releasedOnly = 1, releasedWithDraft = 1, discardedOnly = 1)
        listOf(viewer.objects, viewer.withDrafts, viewer.withReleases, viewer.discardedOnly) shouldBe listOf(4L, 2L, 2L, 1L)

        // The promoter's lens names every set; m/both's draft is hidden, so it is released-only for them.
        val promoter = counted(ReadLens.Only(setOf("m/draft", "m/released", "m/both", "m/discarded")), minimal)
        promoter shouldBe Inventory(draftOnly = 0, releasedOnly = 2, releasedWithDraft = 0, discardedOnly = 0)
        println(
            "event=overview582.fixture viewer=${viewer.objects}/${viewer.withDrafts}/${viewer.withReleases}/${viewer.discardedOnly} " +
                "promoter=${promoter.objects}/${promoter.withDrafts}/${promoter.withReleases}/${promoter.discardedOnly}",
        )
    }

    @Test
    fun `every offered lifecycle value lists exactly the inventory's bucket, unlensed`() {
        val counted = counted(ReadLens.Everything)
        counted shouldBe Inventory(draftOnly = 2, releasedOnly = 3, releasedWithDraft = 3, discardedOnly = 2)

        val table = equalityTable(ReadLens.Everything, counted)
        table.forEach { row -> withClue(row.toString()) { row.listed shouldBe row.counted } }
        println("event=overview582.equality lens=everything ${table.joinToString(" ") { "${it.bucket.wire}=${it.listed}/${it.counted}" }}")
        names(ReadLens.Everything, LifecycleBucket.DRAFT_ONLY) shouldBe setOf("a/draft", "a/discarded_draft")
        names(ReadLens.Everything, LifecycleBucket.RELEASED_WITH_DRAFT) shouldBe setOf("c/both", "c/both_hidden_draft", "c/draft_pointer")
        withClue("the plain catalog is the live sets: the inventory without its discarded-only") {
            catalog(ReadLens.Everything, null).total.toLong() shouldBe counted.objects - counted.discardedOnly
        }
    }

    @Test
    fun `every offered lifecycle value lists exactly the inventory's bucket, through a narrowed lens`() {
        val lens = ReadLens.Only(setOf("a/draft", "b/released", "c/both_hidden_draft", "c/draft_pointer", "d/discarded", "absent/name"))
        val counted = counted(lens)
        counted shouldBe Inventory(draftOnly = 0, releasedOnly = 3, releasedWithDraft = 0, discardedOnly = 0)

        val table = equalityTable(lens, counted)
        table.forEach { row -> withClue(row.toString()) { row.listed shouldBe row.counted } }
        println("event=overview582.equality lens=narrowed ${table.joinToString(" ") { "${it.bucket.wire}=${it.listed}/${it.counted}" }}")
        names(lens, LifecycleBucket.RELEASED_ONLY) shouldBe setOf("b/released", "c/both_hidden_draft", "c/draft_pointer")
        names(lens, LifecycleBucket.WITH_DRAFTS) shouldBe emptySet()
        names(lens, LifecycleBucket.DRAFT_ONLY) shouldBe emptySet()
        withClue("a filtered lensed row is listed at a RELEASED version, never at the draft its pointer names") {
            val pointer = catalog(lens, LifecycleBucket.RELEASED_ONLY).rows.single { it.name == "c/draft_pointer" }
            pointer.version shouldBe 1
            pointer.released shouldBe true
        }
    }

    /**
     * The divergence the brief asked to REPORT: the unfiltered lensed catalog is `findCurrentVersions`'
     * current-pointer rule, so the admitted draft-pointer set is counted by the inventory's Objects
     * and absent from the plain catalog. Pinned as it is today; the follow-up is the handback's.
     */
    @Test
    fun `the unfiltered lensed catalog omits the draft-pointer set the inventory counts`() {
        val lens = ReadLens.Only(setOf("b/released", "c/both_hidden_draft", "c/draft_pointer"))
        val counted = counted(lens)
        val plain = catalog(lens, null)

        counted.objects shouldBe 3L
        plain.rows.map { it.name }.toSet() shouldBe setOf("b/released", "c/both_hidden_draft")
        plain.total shouldBe 2
        withClue("unlensed, the plain catalog lists the draft-pointer set at its pointer's version") {
            catalog(ReadLens.Everything, null).rows.single { it.name == "c/draft_pointer" }.version shouldBe 2
        }
        println("event=overview582.unfiltered lens=narrowed inventory.objects=${counted.objects} catalog.total=${plain.total}")
    }

    @Test
    fun `discarded-only replaces the liveness predicate, so the repository's count is the inventory's`() {
        val counted = counted(ReadLens.Everything)

        repository.countAll(workspace, LifecycleBucket.DISCARDED_ONLY).toLong() shouldBe counted.discardedOnly
        repository.countAll(workspace, LifecycleBucket.DISCARDED_ONLY) shouldBe 2
        withClue("a discarded-only set has no LISTED version (no pointer, no draft): the reason the catalog does not offer it") {
            repository.listAll(workspace, lifecycle = LifecycleBucket.DISCARDED_ONLY) shouldBe emptyList()
        }
        withClue("a narrowed read sees RELEASED rows only, so nothing is discarded-only through a lens") {
            repository.findMembers(workspace, LifecycleBucket.DISCARDED_ONLY) shouldBe emptyList()
        }
    }

    @Test
    fun `the filtered catalog pages with a truthful total and composes with the search`() {
        val bucket = LifecycleBucket.RELEASED_ONLY
        val first = catalog(ReadLens.Everything, bucket, offset = 0)
        first.total shouldBe 3
        first.rows.map { it.name }.toSet() shouldBe setOf("b/released", "b/released_twice", "b/released_discarded")
        catalog(ReadLens.Everything, bucket, q = "twice").rows.map { it.name } shouldBe listOf("b/released_twice")
        catalog(ReadLens.Everything, bucket, q = "twice").total shouldBe 1
        withClue("the lensed path searches the same filtered rows in memory") {
            catalog(ReadLens.Only(setOf("b/released", "c/draft_pointer")), bucket, q = "pointer").rows.map { it.name } shouldBe
                listOf("c/draft_pointer")
        }
    }

    @Test
    fun `recently updated is newest first by the modification expression, and the discarded-only set is absent`() {
        val viewer = entries(ReadLens.Everything)

        viewer.map { it.name } shouldBe listOf("r/hidden", "r/edited_draft", "r/released")
        viewer.map { it.modifiedAt } shouldBe listOf(at(6), at(5), at(3))
        viewer.map { it.hasDraft } shouldBe listOf(true, true, false)
        viewer.first().href shouldBe "/parameter-sets/${idOf("r/hidden", recentWorkspace)}"
    }

    /**
     * The hidden-draft pair on one fixture: a viewer sees `r/edited_draft` (draft-only) in Recently
     * updated and under `?lifecycle=with-drafts` and `?lifecycle=draft`; a promoter whose lens names
     * it sees it on neither, and `r/hidden`'s later draft edit moves nothing for them.
     */
    @Test
    fun `a promoter's hidden drafts move neither Recently updated nor the filtered catalog`() {
        val lens = ReadLens.Only(setOf("r/edited_draft", "r/released", "r/hidden", "r/discarded"))

        names(ReadLens.Everything, LifecycleBucket.WITH_DRAFTS, recentWorkspace) shouldBe setOf("r/edited_draft", "r/hidden")
        names(ReadLens.Everything, LifecycleBucket.DRAFT_ONLY, recentWorkspace) shouldBe setOf("r/edited_draft")

        val promoter = entries(lens)
        promoter.map { it.name } shouldBe listOf("r/released", "r/hidden")
        promoter.map { it.modifiedAt } shouldBe listOf(at(3), at(0))
        promoter.map { it.hasDraft } shouldBe listOf(false, false)
        names(lens, LifecycleBucket.WITH_DRAFTS, recentWorkspace) shouldBe emptySet()
        names(lens, LifecycleBucket.DRAFT_ONLY, recentWorkspace) shouldBe emptySet()
        counted(lens, recentWorkspace).withDrafts shouldBe 0L
    }

    @Test
    fun `the browser header's count is the inventory's objects without its discarded-only`() {
        val counts =
            UiConfig().navCounts(
                PipelineRepository(jdbc),
                TemplateRepository(jdbc),
                DashboardRepository(jdbc),
                VisualizationRepository(jdbc),
                repository,
            )
        val counted = counted(ReadLens.Everything)

        val header = counts.forWorkspace(workspace).parameterSets
        header?.toLong() shouldBe counted.objects - counted.discardedOnly
        println("event=overview582.parity header=$header objects=${counted.objects} discardedOnly=${counted.discardedOnly}")
    }

    private data class Row(
        val bucket: LifecycleBucket,
        val listed: Long,
        val counted: Long,
    )

    private data class Catalog(
        val rows: List<ParameterSetsBrowseModel.ParameterSetLeafView>,
        val total: Int,
    )

    /**
     * #599 A.2 — the Parameter Sets home from a real read: the chart group renders a strip whose four exclusive
     * groups sum to Total, the inventory's objects, and the page carries the family's guide words, the
     * starter prompt equal to today's text. RED at the base: the family had no chart group there.
     */
    @Test
    fun `the strip's four metrics sum to Total and the page carries the family's words`() {
        val now = Instant.now()
        val charts =
            inventory.chartsFor(
                NavigationFamily.PARAMETER_SETS,
                workspace,
                LensedView.EVERYTHING,
                now,
                OverviewRenderSupport.ACTIVE_DAYS,
            )
        val page = OverviewPage.of(OverviewPage.Family.PARAMETER_SETS, false, charts, RecentResult.Available(emptyList()), now)
        val doc = Jsoup.parse(OverviewRenderSupport.html(page))

        OverviewRenderSupport.assertStripSumsTo(doc, counted(ReadLens.Everything).objects)
        OverviewRenderSupport.assertGuideWords(doc, OverviewPage.Family.PARAMETER_SETS, WORDS)
    }

    private fun equalityTable(
        lens: ReadLens,
        counted: Inventory,
    ): List<Row> = CatalogLifecycle.PARAMETER_SETS.map { Row(it, catalog(lens, it).total.toLong(), it.countIn(counted)) }

    /** The catalog page's own fill — `GET /parameter-sets?lifecycle=` and its partial call exactly this. */
    @Suppress("UNCHECKED_CAST")
    private fun catalog(
        lens: ReadLens,
        bucket: LifecycleBucket?,
        workspaceId: UUID = workspace,
        q: String? = null,
        offset: Int = 0,
    ): Catalog {
        val model = ExtendedModelMap()
        browse.fillSearch(model, workspaceId, view(lens), q, offset, bucket)
        return Catalog(model["parameterSets"] as List<ParameterSetsBrowseModel.ParameterSetLeafView>, model["total"] as Int)
    }

    private fun names(
        lens: ReadLens,
        bucket: LifecycleBucket,
        workspaceId: UUID = workspace,
    ): Set<String> = catalog(lens, bucket, workspaceId).rows.map { it.name }.toSet()

    private fun counted(
        lens: ReadLens,
        workspaceId: UUID = workspace,
    ): Inventory = (inventory.forFamily(NavigationFamily.PARAMETER_SETS, workspaceId, view(lens)) as InventoryResult.Available).inventory

    private fun entries(lens: ReadLens): List<RecentEntry> =
        (recent.forFamily(NavigationFamily.PARAMETER_SETS, recentWorkspace, view(lens)) as RecentResult.Available).entries

    private fun view(parameterSets: ReadLens): LensedView =
        if (parameterSets == ReadLens.Everything) {
            LensedView.EVERYTHING
        } else {
            LensedView(
                pipelines = ReadLens.NOTHING,
                templates = ReadLens.NOTHING,
                parameterSets = parameterSets,
                visualizations = ReadLens.NOTHING,
                dashboards = ReadLens.NOTHING,
            )
        }

    private fun idOf(
        name: String,
        workspaceId: UUID,
    ): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT id FROM parameter_sets WHERE workspace_id = :workspace AND name = :name",
                mapOf("workspace" to workspaceId, "name" to name),
                UUID::class.java,
            ),
        )

    /** One version row: its status and its stamps (a non-DRAFT is released; a DISCARDED is discarded). */
    private data class V(
        val status: String,
        val created: Instant = T0,
        val updated: Instant? = null,
        val released: Instant? = if (status == "DRAFT") null else T0,
        val discarded: Instant? = if (status == "DISCARDED") T0 else null,
    )

    private fun v(
        status: String,
        created: Instant = T0,
        updated: Instant? = null,
        released: Instant? = if (status == "DRAFT") null else T0,
        discarded: Instant? = if (status == "DISCARDED") T0 else null,
    ) = V(status, created, updated, released, discarded)

    private fun insert(
        name: String,
        versions: List<V>,
        workspaceId: UUID = workspace,
        current: Int? = versions.indexOfLast { it.status == "RELEASED" }.takeIf { it >= 0 }?.plus(1),
        setUpdated: Instant = T0,
    ) {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO parameter_sets (id, workspace_id, name, display_name, current_version, created_by, created_at, updated_at) " +
                "VALUES (:id, :workspace, :name, :name, :current, :actor, :created, :updated)",
            mapOf(
                "id" to id,
                "workspace" to workspaceId,
                "name" to name,
                "current" to current,
                "actor" to actor,
                "created" to ts(versions.first().created),
                "updated" to ts(setUpdated),
            ),
        )
        versions.forEachIndexed { index, version ->
            jdbc.update(
                "INSERT INTO parameter_set_versions (parameter_set_id, version, body_json, status, body_hash, created_by, created_at, " +
                    "updated_at, released_at, released_by, discarded_at, discarded_by) " +
                    "VALUES (:id, :version, CAST(:body AS jsonb), :status, :hash, :actor, :created, :updated, :released, " +
                    "CASE WHEN :status <> 'DRAFT' THEN CAST(:actor AS uuid) END, :discarded, " +
                    "CASE WHEN :status = 'DISCARDED' THEN CAST(:actor AS uuid) END)",
                mapOf(
                    "id" to id,
                    "version" to index + 1,
                    "body" to """{"display_name":"$name","parameters":[]}""",
                    "status" to version.status,
                    "hash" to "a".repeat(64),
                    "actor" to actor,
                    "created" to ts(version.created),
                    "updated" to version.updated?.let(::ts),
                    "released" to version.released?.let(::ts),
                    "discarded" to version.discarded?.let(::ts),
                ),
            )
        }
    }

    private fun ts(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun at(hours: Long): Instant = T0.plusSeconds(hours * SECONDS_PER_HOUR)

    @Configuration(proxyBeanMethods = false)
    class OverviewParameterSetsConfiguration {
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
    }

    private companion object {
        /**
         * #599 — the parameter-sets guide's words; [OverviewRenderSupport.GuideWords.prompt] is the
         * textarea's text at the base, byte for byte.
         */
        val WORDS =
            OverviewRenderSupport.GuideWords(
                subtitle = "Give related views consistent inputs and dependent controls. Understand what’s live and what’s next.",
                headline = "From inputs to consistent controls.",
                sentence =
                    "Define reusable inputs and how their values are evaluated together, once for every view that uses " +
                        "them.",
                steps =
                    listOf(
                        "Describe" to "The inputs, their defaults and dependencies.",
                        "Build" to "Your agent creates a draft and evaluates it.",
                        "Review" to "Try your own choices. Release when ready.",
                    ),
                prompt =
                    "In workspace acme, create a draft parameter set for [use case, such as the dashboard it will drive]. " +
                        "I need [inputs], with defaults [defaults] and dependencies [how one choice affects another]. Read " +
                        "the current parameter guide, define each control's type and valid choices, and reuse suitable " +
                        "existing templates for options that come from data. Choose an existing folder with me if the " +
                        "destination is unclear, and ask me how any multi-select control should render before you create it. " +
                        "Verify the draft by evaluating it: a first render, representative choices, and empty or invalid " +
                        "ones. Explain the dependency order and any unresolved assumptions. Return the draft's name and id, " +
                        "the templates it depends on and the evaluation evidence. Leave release to me.",
                howItWorksTitle = "Create, evaluate, then release",
                howItWorks =
                    "Parameter sets define reusable inputs and how their values are evaluated together. They give related " +
                        "views consistent choices and dependent controls. For example, choose a year and then offer only the " +
                        "periods available within that year.",
                related = listOf("/overview/templates", "/overview/dashboards"),
            )

        val T0: Instant = Instant.parse("2026-10-01T00:00:00Z")
        const val SECONDS_PER_HOUR = 3600L
    }
}
