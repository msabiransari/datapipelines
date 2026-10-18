package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.BodyCodec
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardLayout
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.pipelines.PromotableView
import co.datapipelines.web.pipelines.PromotionWire
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
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
import java.util.UUID
import javax.sql.DataSource

/**
 * #580 — the Dashboards overview against the shared migrated PostgreSQL, through UiConfig's
 * production [FamilyInventory], [FamilyRecent] and [NavCounts], and the catalog's own read
 * ([DashboardBrowseModel.fillList] over a real [DashboardService]) — the reads both catalog
 * controllers make.
 *
 * The equality table: on one fixture `count(catalog ?lifecycle=X)` equals the inventory's X for
 * every offered value under three lenses — the viewer's, a hand-built promoter lens that names a
 * dashboard whose current pointer sits on a DRAFT over an older release (Amendment 6: the filtered
 * catalog uses the inventory's membership rule, so the two agree; the UNFILTERED catalog keeps its
 * pointer rule and answers one fewer — a recorded finding, pinned here so a change to it is seen),
 * and the real promoter derivation ([PromotableView.dashboardArms], which admits only a RELEASED
 * pointer, #563, so the divergence cannot arise through it). `discarded` is asserted at the
 * repository: the catalog does not offer it, because a discarded-only dashboard has no page.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [OverviewDashboardsE2eTest.DashboardsOverviewConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
class OverviewDashboardsE2eTest {
    @Autowired private lateinit var inventory: FamilyInventory

    @Autowired private lateinit var recent: FamilyRecent

    @Autowired private lateinit var navCounts: NavCounts

    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate
    private val repository by lazy { DashboardRepository(jdbc) }
    private val service by lazy { DashboardService(repository, mockk(), mockk(), { _, _ -> null }, AuthoringGuard(true)) }
    private val browse by lazy { DashboardBrowseModel(service, mockk(), mockk(), mockk(), mockk(), mockk(), mockk()) }
    private val workspace = UUID.randomUUID()
    private val otherWorkspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    @BeforeEach
    fun seedContext() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'Boards', 'google', :email)",
            mapOf("id" to actor, "email" to "$actor@boards.test"),
        )
        listOf(workspace, otherWorkspace).forEach { id ->
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name) VALUES (:id, :name, 'Boards')",
                mapOf("id" to id, "name" to "boards_$id"),
            )
        }
    }

    @AfterEach
    fun cleanOwnRows() {
        val params = mapOf("ids" to listOf(workspace, otherWorkspace))
        jdbc.update("DELETE FROM dashboards WHERE workspace_id IN (:ids)", params)
        jdbc.update("DELETE FROM workspaces WHERE id IN (:ids)", params)
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to actor))
    }

    @Test
    fun `every offered lifecycle value lists exactly the inventory's bucket, unlensed`() {
        fixture()
        val counted = counted(LensedView.EVERYTHING)
        counted shouldBe Inventory(draftOnly = 4, releasedOnly = 2, releasedWithDraft = 2, discardedOnly = 1)

        val table = equalityTable(LensedView.EVERYTHING, counted)
        table.forEach { row -> withClue(row.toString()) { row.listed shouldBe row.counted } }
        report("viewer", table)
        names(LensedView.EVERYTHING, LifecycleBucket.DRAFT_ONLY) shouldBe
            setOf("a/draft", "a/draft_edited", "a/discarded_draft", "a/draft_old")
        names(LensedView.EVERYTHING, LifecycleBucket.RELEASED_ONLY) shouldBe setOf("b/released", "b/released_twice")
        names(LensedView.EVERYTHING, LifecycleBucket.RELEASED_WITH_DRAFT) shouldBe setOf("c/both", "c/draft_pointer")
        withClue("the plain catalog is the live objects: the inventory without its discarded-only") {
            catalogTotal(LensedView.EVERYTHING, null).toLong() shouldBe counted.objects - counted.discardedOnly
        }
    }

    /**
     * `c/both`'s DRAFT is not admitted (a narrowed read is RELEASED rows only), so it is released-only
     * for the promoter and absent from every draft value — the hidden-draft pair, the viewer's half
     * being the unlensed case above. `c/draft_pointer` is released-only too: its v1 release is
     * admitted whatever the pointer says.
     */
    @Test
    fun `every offered lifecycle value lists exactly the inventory's bucket, through a lens naming a draft-pointer one`() {
        fixture()
        val view = lens(ReadLens.Only(setOf("a/draft", "b/released", "c/both", "c/draft_pointer", "d/discarded", "absent/name")))
        val counted = counted(view)
        counted shouldBe Inventory(draftOnly = 0, releasedOnly = 3, releasedWithDraft = 0, discardedOnly = 0)

        val table = equalityTable(view, counted)
        table.forEach { row -> withClue(row.toString()) { row.listed shouldBe row.counted } }
        report("hand", table)
        names(view, LifecycleBucket.RELEASED_ONLY) shouldBe setOf("b/released", "c/both", "c/draft_pointer")
        names(view, LifecycleBucket.WITH_DRAFTS) shouldBe emptySet()
        names(view, LifecycleBucket.DRAFT_ONLY) shouldBe emptySet()
        withClue("a filtered row never shows a promoter a draft: the draft-pointer object lists its release") {
            rows(view, LifecycleBucket.RELEASED_ONLY).single { it.name == "c/draft_pointer" }.let {
                it.version shouldBe 1
                it.released shouldBe true
            }
        }
        val unfiltered = catalogTotal(view, null)
        println("event=overview.dashboards.unfiltered lens=hand catalog=$unfiltered objects=${counted.objects}")
        withClue("the unfiltered catalog keeps its pointer rule (Amendment 6): the draft-pointer object is not in it") {
            unfiltered shouldBe 2
        }
    }

    @Test
    fun `every offered lifecycle value lists exactly the inventory's bucket, through the real promoter derivation`() {
        fixture()
        val arms =
            PromotableView.dashboardArms(
                ReadLens.Everything,
                PromotionWire.Inventory(deployment = "prod", authoringEnabled = false, workspace = "boards"),
                emptySet(),
                repository.findCurrentPinsAndSources(workspace),
            )
        val view = lens(arms.dashboardsLens)
        (arms.dashboardsLens as ReadLens.Only).names shouldBe setOf("b/released", "b/released_twice", "c/both")
        val counted = counted(view)
        counted shouldBe Inventory(draftOnly = 0, releasedOnly = 3, releasedWithDraft = 0, discardedOnly = 0)

        val table = equalityTable(view, counted)
        table.forEach { row -> withClue(row.toString()) { row.listed shouldBe row.counted } }
        report("real", table)
        names(view, LifecycleBucket.WITH_DRAFTS) shouldBe emptySet()
        val unfiltered = catalogTotal(view, null)
        println("event=overview.dashboards.unfiltered lens=real catalog=$unfiltered objects=${counted.objects}")
        unfiltered.toLong() shouldBe counted.objects
    }

    @Test
    fun `discarded-only replaces the liveness predicate at the repository, and such a dashboard has no workspace page`() {
        fixture()
        val counted = counted(LensedView.EVERYTHING)

        repository.countIn(workspace, LifecycleBucket.DISCARDED_ONLY, null, null).toLong() shouldBe counted.discardedOnly
        repository.listIn(workspace, LifecycleBucket.DISCARDED_ONLY, null, null).map { it.record.name } shouldBe listOf("d/discarded")
        withClue("a narrowed read sees RELEASED rows only, so nothing is discarded-only through a lens") {
            repository.countIn(workspace, LifecycleBucket.DISCARDED_ONLY, setOf("d/discarded"), null) shouldBe 0
        }
        withClue("an empty lens reads nothing") {
            repository.countIn(workspace, LifecycleBucket.RELEASED_ONLY, emptySet(), null) shouldBe 0
            repository.listIn(workspace, LifecycleBucket.RELEASED_ONLY, emptySet(), null) shouldBe emptyList()
        }
        withClue("why the catalog does not offer discarded: the workspace's read answers nothing (its 404)") {
            service.findWorking(workspace, ReadLens.Everything, idOf("d/discarded")).shouldBeNull()
            service.findWorking(workspace, ReadLens.Everything, idOf("a/discarded_draft")).shouldNotBeNull()
        }
    }

    @Test
    fun `the filtered catalog composes the search, pages with a truthful total and never reads another workspace`() {
        fixture()
        val ui = ExtendedModelMap()

        browse.fillList(ui, workspace, LensedView.EVERYTHING, "draft_", 0, LifecycleBucket.DRAFT_ONLY)
        ui["total"] shouldBe 2
        leafNames(ui) shouldBe listOf("a/draft_edited", "a/draft_old")

        val first = service.listIn(workspace, ReadLens.Everything, LifecycleBucket.WITH_RELEASES, null, 0, 2)
        val second = service.listIn(workspace, ReadLens.Everything, LifecycleBucket.WITH_RELEASES, null, 2, 2)
        first.total shouldBe 4
        (first.items + second.items).map { it.record.name } shouldBe
            listOf("b/released", "b/released_twice", "c/both", "c/draft_pointer")
        withClue("the other workspace's same-named draft is never this workspace's row") {
            service.listIn(otherWorkspace, ReadLens.Everything, LifecycleBucket.DRAFT_ONLY, null).items.map { it.record.name } shouldBe
                listOf("b/released")
            names(LensedView.EVERYTHING, LifecycleBucket.DRAFT_ONLY).contains("b/released") shouldBe false
        }
    }

    @Test
    fun `Recently updated is newest first over the four columns, never discarded-only, never another workspace`() {
        recentFixture()
        // Every row starts at one old instant, so only the column a step moves can reorder the list.
        age("dashboards", "updated_at")
        listOf("created_at", "updated_at", "released_at", "discarded_at").forEach { age("dashboard_versions", it) }
        recentNames() shouldContainExactly listOf("both", "discarded_draft", "draft", "released")

        touch("UPDATE dashboard_versions SET updated_at = NOW() WHERE dashboard_id = :id AND status = 'DRAFT'", "draft")
        withClue("an in-place draft write moves only the version row's updated_at") { recentNames().first() shouldBe "draft" }
        touch("UPDATE dashboard_versions SET released_at = NOW() + INTERVAL '1 minute' WHERE dashboard_id = :id", "released")
        withClue("a release moves released_at") { recentNames().first() shouldBe "released" }
        touch("UPDATE dashboards SET updated_at = NOW() + INTERVAL '2 minutes' WHERE id = :id", "both")
        withClue("a switch or restore moves the parent's updated_at") { recentNames().first() shouldBe "both" }
        touch(
            "UPDATE dashboard_versions SET discarded_at = NOW() + INTERVAL '3 minutes' WHERE dashboard_id = :id AND version = 1",
            "discarded_draft",
        )
        withClue("a discard of one version moves discarded_at, and the object keeps a live draft") {
            recentNames().first() shouldBe "discarded_draft"
        }
        withClue("the discarded-only object has no page to link, whatever its clock says") {
            touch("UPDATE dashboards SET updated_at = NOW() + INTERVAL '1 hour' WHERE id = :id", "discarded")
            ("discarded" in recentNames()) shouldBe false
        }
        recentEntries().first { it.name == "both" }.hasDraft shouldBe true
        recentEntries().first { it.name == "released" }.hasDraft shouldBe false
        recentEntries().forEach { it.href shouldBe "/dashboards/${idOf(it.name)}" }
        withClue("the other workspace lists its own same-named release, never this workspace's row") {
            val other = recentEntries(LensedView.EVERYTHING, otherWorkspace)
            other.map { it.name } shouldContainExactly listOf("released")
            (other.single().href == "/dashboards/${idOf("released")}") shouldBe false
        }
    }

    /** The A.4 `coalesce(v.updated_at, v.created_at)` case: a draft edited AFTER another dashboard's release. */
    @Test
    fun `a draft edited after another dashboard's release sorts above it`() {
        recentFixture()
        age("dashboards", "updated_at")
        listOf("created_at", "updated_at", "released_at", "discarded_at").forEach { age("dashboard_versions", it) }
        touch("UPDATE dashboard_versions SET released_at = TIMESTAMPTZ '2026-02-01 00:00:00+00' WHERE dashboard_id = :id", "released")
        touch(
            "UPDATE dashboard_versions SET updated_at = TIMESTAMPTZ '2026-02-01 00:01:00+00' WHERE dashboard_id = :id AND status = 'DRAFT'",
            "draft",
        )

        recentNames().take(2) shouldContainExactly listOf("draft", "released")
    }

    @Test
    fun `a promoter's Recently updated hides the draft - no badge, and its write moves nothing`() {
        recentFixture()
        age("dashboards", "updated_at")
        listOf("created_at", "updated_at", "released_at", "discarded_at").forEach { age("dashboard_versions", it) }
        val promoter = lens(ReadLens.Only(setOf("both", "released", "draft")))
        val before = recentNames(promoter)
        before shouldContainExactly listOf("both", "released")

        touch("UPDATE dashboard_versions SET updated_at = NOW() + INTERVAL '1 hour' WHERE dashboard_id = :id AND status = 'DRAFT'", "both")
        touch("UPDATE dashboard_versions SET released_at = NOW() WHERE dashboard_id = :id", "released")

        withClue("the viewer sees the draft's write and its badge") {
            recentNames().first() shouldBe "both"
            recentEntries().first { it.name == "both" }.hasDraft shouldBe true
        }
        withClue("the promoter's order is the releases' only, and the draft is not admitted") {
            recentNames(promoter) shouldContainExactly listOf("released", "both")
            recentEntries(promoter).first { it.name == "both" }.hasDraft shouldBe false
        }
    }

    /** The browser header's count (`ArtifactSql.countAll`, `liveS`) is the inventory's A + B + C on one snapshot. */
    @Test
    fun `the browser header count is the inventory without its discarded-only objects`() {
        fixture()
        navCounts.invalidate(workspace)
        val counted = counted(LensedView.EVERYTHING)

        val header = navCounts.forWorkspace(workspace).dashboards
        println("event=overview.dashboards.parity header=$header objects=${counted.objects} discardedOnly=${counted.discardedOnly}")
        header shouldBe (counted.objects - counted.discardedOnly).toInt()
    }

    private data class Row(
        val bucket: LifecycleBucket,
        val listed: Long,
        val counted: Long,
    )

    private fun equalityTable(
        view: LensedView,
        counted: Inventory,
    ): List<Row> = CatalogLifecycle.DASHBOARDS.map { bucket -> Row(bucket, catalogTotal(view, bucket).toLong(), bucket.countIn(counted)) }

    private fun report(
        lens: String,
        table: List<Row>,
    ) {
        val cells = table.joinToString(" ") { "${it.bucket.wire}=${it.listed}/${it.counted}" }
        println("event=overview.dashboards.equality lens=$lens $cells")
    }

    private fun catalogTotal(
        view: LensedView,
        bucket: LifecycleBucket?,
    ): Int {
        val ui = ExtendedModelMap()
        browse.fillList(ui, workspace, view, null, 0, bucket)
        return ui["total"] as Int
    }

    private fun rows(
        view: LensedView,
        bucket: LifecycleBucket,
    ): List<DashboardBrowseModel.DashboardLeafView> {
        val ui = ExtendedModelMap()
        browse.fillList(ui, workspace, view, null, 0, bucket)
        @Suppress("UNCHECKED_CAST") // the fill's own attribute, typed at its one writer
        return ui["dashboards"] as List<DashboardBrowseModel.DashboardLeafView>
    }

    private fun names(
        view: LensedView,
        bucket: LifecycleBucket,
    ): Set<String> = rows(view, bucket).map { it.name }.toSet()

    @Suppress("UNCHECKED_CAST") // the fill's own attribute, typed at its one writer
    private fun leafNames(ui: ExtendedModelMap): List<String> =
        (ui["dashboards"] as List<DashboardBrowseModel.DashboardLeafView>).map { it.name }

    private fun counted(view: LensedView): Inventory =
        (inventory.forFamily(NavigationFamily.DASHBOARDS, workspace, view) as InventoryResult.Available).inventory

    private fun recentEntries(
        view: LensedView = LensedView.EVERYTHING,
        workspaceId: UUID = workspace,
    ): List<RecentEntry> = (recent.forFamily(NavigationFamily.DASHBOARDS, workspaceId, view) as RecentResult.Available).entries

    private fun recentNames(view: LensedView = LensedView.EVERYTHING): List<String> = recentEntries(view).map { it.name }

    private fun lens(dashboards: ReadLens): LensedView =
        LensedView(
            pipelines = ReadLens.NOTHING,
            templates = ReadLens.NOTHING,
            parameterSets = ReadLens.NOTHING,
            visualizations = ReadLens.NOTHING,
            dashboards = dashboards,
        )

    /**
     * Draft-only, released-only, both and discarded-only → 4 / 2 / 2 / 1 (the issue's §8 fixture):
     * `b/released_twice`'s two releases count once; `a/discarded_draft` is the draft-only bucket's
     * second shape; `c/draft_pointer` (v1 RELEASED, v2 DISCARDED, v3 DRAFT, pointer v3 — where
     * discard's fallback puts it when drafts are pointer-eligible) is Amendment 6's object.
     */
    private fun fixture() {
        insert("a/draft", listOf("DRAFT"))
        insert("a/draft_edited", listOf("DRAFT"))
        insert("a/draft_old", listOf("DRAFT"))
        insert("a/discarded_draft", listOf("DISCARDED", "DRAFT"))
        insert("b/released", listOf("RELEASED"))
        insert("b/released_twice", listOf("RELEASED", "RELEASED"))
        insert("c/both", listOf("RELEASED", "DRAFT"))
        insert("c/draft_pointer", listOf("RELEASED", "DISCARDED", "DRAFT"), current = 3)
        insert("d/discarded", listOf("DISCARDED"))
        insert("b/released", listOf("DRAFT"), workspaceId = otherWorkspace)
    }

    /** [FamilyRecentE2eTest]'s fixture, for the Dashboards family. */
    private fun recentFixture() {
        insert("draft", listOf("DRAFT"))
        insert("released", listOf("RELEASED"))
        insert("both", listOf("RELEASED", "DRAFT"))
        insert("discarded", listOf("DISCARDED"))
        insert("discarded_draft", listOf("DISCARDED", "DRAFT"))
        insert("released", listOf("RELEASED"), workspaceId = otherWorkspace)
    }

    /** Sets [column] of every row of this workspace in [table] (where non-null) to one old instant. */
    private fun age(
        table: String,
        column: String,
    ) {
        val scope =
            if (table == "dashboards") {
                "workspace_id = :workspace"
            } else {
                "dashboard_id IN (SELECT id FROM dashboards WHERE workspace_id = :workspace)"
            }
        jdbc.update(
            "UPDATE $table SET $column = TIMESTAMPTZ '2026-01-01 00:00:00+00' WHERE $scope AND $column IS NOT NULL",
            mapOf("workspace" to workspace),
        )
    }

    private fun touch(
        sql: String,
        name: String,
    ) {
        withClue("$sql must move a row of $name") { (jdbc.update(sql, mapOf("id" to idOf(name))) >= 1) shouldBe true }
    }

    private fun idOf(name: String): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT id FROM dashboards WHERE workspace_id = :workspace AND name = :name",
                mapOf("workspace" to workspace, "name" to name),
                UUID::class.java,
            ),
        )

    private fun insert(
        name: String,
        statuses: List<String>,
        workspaceId: UUID = workspace,
        current: Int? = statuses.indexOfLast { it == "RELEASED" }.takeIf { it >= 0 }?.plus(1),
    ) {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO dashboards (id, workspace_id, name, display_name, current_version, created_by) " +
                "VALUES (:id, :workspace, :name, :name, :current, :actor)",
            mapOf("id" to id, "workspace" to workspaceId, "name" to name, "current" to current, "actor" to actor),
        )
        val body =
            BodyCodec.Dashboard.write(DashboardBody(displayName = name, visualizations = emptyList(), layout = DashboardLayout()))
        statuses.forEachIndexed { index, status ->
            jdbc.update(
                "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, created_by, " +
                    "released_at, released_by, discarded_at, discarded_by) " +
                    "VALUES (:id, :version, CAST(:body AS jsonb), :status, :hash, :actor, " +
                    "CASE WHEN :status <> 'DRAFT' THEN NOW() END, CASE WHEN :status <> 'DRAFT' THEN :actor END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN NOW() END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN :actor END)",
                mapOf(
                    "id" to id,
                    "version" to index + 1,
                    "body" to body,
                    "status" to status,
                    "hash" to "a".repeat(64),
                    "actor" to actor,
                ),
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    class DashboardsOverviewConfiguration {
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
        fun jdbc(source: DataSource): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(source)

        @Bean
        fun recent(jdbc: NamedParameterJdbcTemplate): FamilyRecent =
            UiConfig().familyRecent(PipelineRepository(jdbc), TemplateRepository(jdbc), DashboardRepository(jdbc))

        @Bean
        fun inventory(jdbc: NamedParameterJdbcTemplate): FamilyInventory =
            UiConfig().familyInventory(
                PipelineRepository(jdbc),
                TemplateRepository(jdbc),
                DashboardRepository(jdbc),
                VisualizationRepository(jdbc),
                ParameterSetRepository(jdbc),
            )

        @Bean
        fun navCounts(jdbc: NamedParameterJdbcTemplate): NavCounts =
            UiConfig().navCounts(
                PipelineRepository(jdbc),
                TemplateRepository(jdbc),
                DashboardRepository(jdbc),
                VisualizationRepository(jdbc),
                ParameterSetRepository(jdbc),
            )
    }
}
