package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.pipelineServiceOver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import java.time.Instant
import java.util.UUID

/**
 * #556 A.6 — the Pipelines catalog's `lifecycle` filter and the overview's inventory read the SAME
 * bucket definition (`LifecycleBucket`), so on one fixture `count(catalog ?lifecycle=X)` equals the
 * inventory's X for every value and every lens (the record's "the graph never disagrees with its
 * adjacent totals", made mechanical across the two pages). The catalog page goes through
 * [PipelineService.page], the catalog's own read; the inventory through UiConfig's production
 * [FamilyInventory].
 *
 * Discarded-only (amendment 2): the bucket REPLACES the catalog's liveness predicate, asserted at
 * the repository, because the pipelines catalog does not offer `discarded` (a discarded-only
 * pipeline's workspace page is a 404, `PipelineRepository.findById`'s liveness). The promoter
 * fixture holds no pointer-on-draft object (amendment 3): the narrowed inventory admits any
 * RELEASED version, the catalogs' pointer rule is theirs.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [OverviewE2eTest.OverviewConfiguration::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OverviewE2eTest {
    @Autowired private lateinit var inventory: FamilyInventory

    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate
    private val repository by lazy { PipelineRepository(jdbc) }
    private val service: PipelineService by lazy { pipelineServiceOver(repository) }
    private val workspace = UUID.randomUUID()
    private val otherWorkspace = UUID.randomUUID()
    private val familyWorkspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    @BeforeEach
    fun seedContext() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'Overview', 'google', :email)",
            mapOf("id" to actor, "email" to "$actor@overview.test"),
        )
        listOf(workspace, otherWorkspace, familyWorkspace).forEach { id ->
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name) VALUES (:id, :name, 'Overview')",
                mapOf(
                    "id" to id,
                    "name" to "overview_$id",
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
        val params = mapOf("ids" to listOf(workspace, otherWorkspace, familyWorkspace))
        NavigationFamily.entries.forEach { jdbc.update("DELETE FROM ${it.table} WHERE workspace_id IN (:ids)", params) }
        jdbc.update("DELETE FROM workspaces WHERE id IN (:ids)", params)
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to actor))
    }

    @Test
    fun `every offered lifecycle value lists exactly the inventory's bucket, unlensed`() {
        val counted = counted(ReadLens.Everything)
        counted shouldBe Inventory(draftOnly = 2, releasedOnly = 3, releasedWithDraft = 2, discardedOnly = 2)

        val table = equalityTable(ReadLens.Everything, counted)
        table.forEach { row -> withClue(row.toString()) { row.listed shouldBe row.counted } }
        println("event=overview.a6.equality lens=everything ${table.joinToString(" ") { "${it.bucket.wire}=${it.listed}/${it.counted}" }}")
        names(ReadLens.Everything, LifecycleBucket.DRAFT_ONLY) shouldBe setOf("a/draft", "a/discarded_draft")
        names(ReadLens.Everything, LifecycleBucket.RELEASED_WITH_DRAFT) shouldBe setOf("c/both", "c/both_hidden_draft")
        withClue("the plain catalog is the live objects: the inventory without its discarded-only") {
            service.page(workspace, ReadLens.Everything, null, 0, PAGE, null).total.toLong() shouldBe
                counted.objects - counted.discardedOnly
        }
    }

    /**
     * A promoter's lens admits five names; `c/both_hidden_draft`'s DRAFT is not admitted (the
     * narrowed read is RELEASED rows only), so it is released-only for them and absent from every
     * draft value — on the catalog exactly as in the inventory, the hidden-draft case of the
     * record (`:349`) on the filtered catalog.
     */
    @Test
    fun `every offered lifecycle value lists exactly the inventory's bucket, through a narrowed lens`() {
        val lens = ReadLens.Only(setOf("a/draft", "b/released", "c/both_hidden_draft", "d/discarded", "absent/name"))
        val counted = counted(lens)
        counted shouldBe Inventory(draftOnly = 0, releasedOnly = 2, releasedWithDraft = 0, discardedOnly = 0)

        val table = equalityTable(lens, counted)
        table.forEach { row -> withClue(row.toString()) { row.listed shouldBe row.counted } }
        println("event=overview.a6.equality lens=narrowed ${table.joinToString(" ") { "${it.bucket.wire}=${it.listed}/${it.counted}" }}")
        names(lens, LifecycleBucket.RELEASED_ONLY) shouldBe setOf("b/released", "c/both_hidden_draft")
        names(lens, LifecycleBucket.WITH_DRAFTS) shouldBe emptySet()
        names(lens, LifecycleBucket.DRAFT_ONLY) shouldBe emptySet()
    }

    @Test
    fun `discarded-only replaces the liveness predicate, so the repository's count is the inventory's`() {
        val counted = counted(ReadLens.Everything)

        repository.countAll(workspace, LifecycleBucket.DISCARDED_ONLY).toLong() shouldBe counted.discardedOnly
        repository.findAll(workspace, null, lifecycle = LifecycleBucket.DISCARDED_ONLY).map { it.name }.toSet() shouldBe
            setOf("d/discarded", "d/discarded_twice")
        withClue("a narrowed read sees RELEASED rows only, so nothing is discarded-only through a lens") {
            repository.findAll(workspace, null, lifecycle = LifecycleBucket.DISCARDED_ONLY, releasedOnly = true) shouldBe emptyList()
        }
    }

    @Test
    fun `the filtered catalog pages with a truthful total`() {
        val bucket = LifecycleBucket.RELEASED_ONLY
        val first = service.page(workspace, ReadLens.Everything, null, 0, 2, bucket)
        val second = service.page(workspace, ReadLens.Everything, null, 2, 2, bucket)

        first.total shouldBe 3
        first.hasMore shouldBe true
        second.hasMore shouldBe false
        (first.items + second.items).map { it.name }.toSet() shouldBe setOf("b/released", "b/released_twice", "b/released_discarded")
        withClue("the lensed path pages in memory over the same filtered rows") {
            service.page(workspace, ReadLens.Only(setOf("b/released", "b/released_twice")), null, 0, 1, bucket).total shouldBe 2
        }
    }

    /**
     * #598a — the Pipelines home's chart group (`FamilyInventory.chartsFor`, one statement) and the
     * four-bucket inventory read the same lifecycle, bucket for bucket, unlensed and through the
     * narrowed lens; every object sits in one release bin. So each lifecycle part's link opens a
     * catalog whose total is the part's number (the equality cases above). #598b: the same read's
     * activity never names more new objects than the lifecycle holds, nor more actively developed
     * ones than hold a draft; through the narrowed lens nobody is actively developing.
     */
    @Test
    fun `the chart group's lifecycle is the inventory's, unlensed and narrowed`() {
        val narrowed = ReadLens.Only(setOf("a/draft", "b/released", "c/both_hidden_draft", "d/discarded", "absent/name"))
        listOf(ReadLens.Everything, narrowed).forEach { lens ->
            val charts = charts(lens)
            println("event=598a.overview.charts lens=${if (lens == narrowed) "narrowed" else "everything"} $charts")
            charts.lifecycle shouldBe counted(lens)
            charts.maturity.sumOf { it.total } shouldBe charts.lifecycle.objects
            withClue("#598b: every row was stamped just now, so each admitted object is new and each admitted draft active") {
                charts.activity.weeks.sumOf { it.created } shouldBe charts.lifecycle.objects
                charts.activity.activelyDeveloped shouldBe charts.lifecycle.withDrafts
                if (lens == narrowed) charts.activity.activelyDeveloped shouldBe 0L
            }
            CatalogLifecycle.PIPELINES.forEach { bucket ->
                withClue(bucket) {
                    service.page(workspace, lens, null, 0, PAGE, bucket).total.toLong() shouldBe bucket.countIn(charts.lifecycle)
                }
            }
        }
        val unlensed = charts(ReadLens.Everything)
        unlensed.maturity.map { it.draftOpen to it.noDraft } shouldBe listOf(2L to 2L, 2L to 2L, 0L to 1L, 0L to 0L, 0L to 0L)
        unlensed.activity.activelyDeveloped shouldBe 4L
        unlensed.activity.weeks.sumOf { it.released } shouldBe 6L
    }

    /**
     * #599 — every family's home reads ITS OWN chart group from a real statement. All five families
     * are seeded into one workspace, each with a different number of fresh draft-only objects (the
     * family's ordinal + 1), so a family that read another's rows — an artifact repository ignoring
     * its kind, a `chartsFor` arm pointing at the wrong repository — counts the wrong number. Each
     * family also holds one stale draft (written before the window), one object released twice, one
     * released with a draft and one discarded; every object was created now.
     */
    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `each family's chart group is its own seeded lifecycle, weeks and actively developed`(family: NavigationFamily) {
        NavigationFamily.entries.forEach(::seedFamily)
        val now = Instant.now()
        val fresh = family.ordinal + 1L
        val charts = familyCharts(family, ReadLens.Everything, now)
        println("event=599.overview.charts family=$family lens=everything $charts")

        charts.lifecycle shouldBe Inventory(draftOnly = fresh + 1, releasedOnly = 1, releasedWithDraft = 1, discardedOnly = 1)
        charts.maturity.map { it.draftOpen to it.noDraft } shouldBe listOf(fresh + 1 to 1L, 1L to 0L, 0L to 1L, 0L to 0L, 0L to 0L)
        withClue("the twelve weeks sum to the seeded creations and releases (a discarded version is no release)") {
            charts.activity.weeks.sumOf { it.created } shouldBe charts.lifecycle.objects
            charts.activity.weeks.sumOf { it.released } shouldBe SEEDED_RELEASES
            withClue("every object was created in the week that holds now") {
                charts.activity.weeks
                    .last()
                    .created shouldBe charts.lifecycle.objects
            }
        }
        withClue("actively developed = the drafts written inside the $ACTIVE_DAYS-day window; the stale draft is not") {
            charts.activity.activelyDeveloped shouldBe fresh + 1
            charts.activity.windowDays shouldBe ACTIVE_DAYS
        }

        val narrowed = familyCharts(family, ReadLens.Only(setOf("f/released", "f/both", "f/draft_0")), now)
        println("event=599.overview.charts family=$family lens=narrowed $narrowed")
        withClue("a narrowed lens admits RELEASED rows only: two released objects, no draft, nobody developing") {
            narrowed.lifecycle shouldBe Inventory(draftOnly = 0, releasedOnly = 2, releasedWithDraft = 0, discardedOnly = 0)
            narrowed.activity.activelyDeveloped shouldBe 0L
            narrowed.activity.weeks.sumOf { it.released } shouldBe SEEDED_RELEASES
        }
    }

    private fun familyCharts(
        family: NavigationFamily,
        lens: ReadLens,
        now: Instant,
    ) = (inventory.chartsFor(family, familyWorkspace, familyView(family, lens), now, ACTIVE_DAYS) as ChartsResult.Available).charts

    private fun familyView(
        family: NavigationFamily,
        lens: ReadLens,
    ): LensedView =
        LensedView(
            pipelines = if (family == NavigationFamily.PIPELINES) lens else ReadLens.NOTHING,
            templates = if (family == NavigationFamily.TEMPLATES) lens else ReadLens.NOTHING,
            parameterSets = if (family == NavigationFamily.PARAMETER_SETS) lens else ReadLens.NOTHING,
            visualizations = if (family == NavigationFamily.VISUALIZATIONS) lens else ReadLens.NOTHING,
            dashboards = if (family == NavigationFamily.DASHBOARDS) lens else ReadLens.NOTHING,
        )

    private fun seedFamily(family: NavigationFamily) {
        repeat(family.ordinal + 1) { seedObject(family, "f/draft_$it", listOf("DRAFT")) }
        seedObject(family, "f/stale_draft", listOf("DRAFT"), draftWrittenDaysAgo = ACTIVE_DAYS + 1L)
        seedObject(family, "f/released", listOf("RELEASED", "RELEASED"))
        seedObject(family, "f/both", listOf("RELEASED", "DRAFT"))
        seedObject(family, "f/discarded", listOf("DISCARDED"))
    }

    /** One object of [family] in the family workspace, its versions stamped now (a draft's write [draftWrittenDaysAgo] back). */
    private fun seedObject(
        family: NavigationFamily,
        name: String,
        statuses: List<String>,
        draftWrittenDaysAgo: Long = 0,
    ) {
        val id = UUID.randomUUID()
        val owner = if (family == NavigationFamily.PIPELINES) "owner_id" else "created_by"
        val current = statuses.indexOfLast { it == "RELEASED" }.takeIf { it >= 0 }?.plus(1)
        jdbc.update(
            "INSERT INTO ${family.table} (id, workspace_id, name, display_name, current_version, $owner) " +
                "VALUES (:id, :workspace, :name, :name, :current, :actor)",
            mapOf("id" to id, "workspace" to familyWorkspace, "name" to name, "current" to current, "actor" to actor),
        )
        val contentColumns = if (family == NavigationFamily.TEMPLATES) "body, dialect" else "body_json"
        val content = if (family == NavigationFamily.TEMPLATES) "'select 1', 'POSTGRES'" else "CAST('{}' AS jsonb)"
        val versions = family.table.removeSuffix("s") + "_versions"
        statuses.forEachIndexed { index, status ->
            jdbc.update(
                "INSERT INTO $versions (${family.foreignKey}, version, $contentColumns, status, body_hash, created_by, " +
                    "released_at, released_by, discarded_at, discarded_by, created_at, updated_at) " +
                    "VALUES (:id, :version, $content, :status, :hash, :actor, " +
                    "CASE WHEN :status <> 'DRAFT' THEN NOW() END, CASE WHEN :status <> 'DRAFT' THEN :actor END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN NOW() END, CASE WHEN :status = 'DISCARDED' THEN :actor END, " +
                    "NOW() - make_interval(days => :ago), NOW() - make_interval(days => :ago))",
                mapOf(
                    "id" to id,
                    "version" to index + 1,
                    "status" to status,
                    "hash" to "a".repeat(64),
                    "actor" to actor,
                    "ago" to if (status == "DRAFT") draftWrittenDaysAgo.toInt() else 0,
                ),
            )
        }
    }

    private fun charts(lens: ReadLens) =
        (inventory.chartsFor(NavigationFamily.PIPELINES, workspace, view(lens), Instant.now(), ACTIVE_DAYS) as ChartsResult.Available)
            .charts

    private data class Row(
        val bucket: LifecycleBucket,
        val listed: Long,
        val counted: Long,
    )

    private fun equalityTable(
        lens: ReadLens,
        counted: Inventory,
    ): List<Row> =
        CatalogLifecycle.PIPELINES.map { bucket ->
            Row(bucket, service.page(workspace, lens, null, 0, PAGE, bucket).total.toLong(), bucket.countIn(counted))
        }

    private fun names(
        lens: ReadLens,
        bucket: LifecycleBucket,
    ): Set<String> =
        service
            .page(workspace, lens, null, 0, PAGE, bucket)
            .items
            .map { it.name }
            .toSet()

    private fun counted(lens: ReadLens): Inventory =
        (inventory.forFamily(NavigationFamily.PIPELINES, workspace, view(lens)) as InventoryResult.Available).inventory

    private fun view(pipelines: ReadLens): LensedView =
        if (pipelines == ReadLens.Everything) {
            LensedView.EVERYTHING
        } else {
            LensedView(
                pipelines = pipelines,
                templates = ReadLens.NOTHING,
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
            "INSERT INTO pipelines (id, workspace_id, name, display_name, current_version, owner_id) " +
                "VALUES (:id, :workspace, :name, :name, :current, :actor)",
            mapOf("id" to id, "workspace" to workspaceId, "name" to name, "current" to current, "actor" to actor),
        )
        statuses.forEachIndexed { index, status ->
            jdbc.update(
                "INSERT INTO pipeline_versions (pipeline_id, version, body_json, status, body_hash, created_by, " +
                    "released_at, released_by, discarded_at, discarded_by) " +
                    "VALUES (:id, :version, CAST('{}' AS jsonb), :status, :hash, :actor, " +
                    "CASE WHEN :status <> 'DRAFT' THEN NOW() END, CASE WHEN :status <> 'DRAFT' THEN :actor END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN NOW() END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN :actor END)",
                mapOf("id" to id, "version" to index + 1, "status" to status, "hash" to "a".repeat(64), "actor" to actor),
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    class OverviewConfiguration {
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
        const val PAGE = 100
        const val ACTIVE_DAYS = 14

        /** Per family: `f/released`'s two releases and `f/both`'s one. */
        const val SEEDED_RELEASES = 3L
    }
}
