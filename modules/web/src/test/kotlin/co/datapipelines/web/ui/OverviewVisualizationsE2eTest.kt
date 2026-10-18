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
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.SharedPostgres
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
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
import java.time.Instant
import java.util.UUID

/** #581 — real PostgreSQL: inventory, filtered catalog, recent adapter and header on the same rows. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [OverviewVisualizationsE2eTest.Config::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OverviewVisualizationsE2eTest {
    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate
    private val workspace = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val repository by lazy { VisualizationRepository(jdbc) }
    private val service by lazy {
        VisualizationService(repository, mockk(), DashboardRepository(jdbc), AuthoringGuard(true), mockk())
    }
    private val browse by lazy { VisualizationBrowseModel(service) }
    private val inventory by lazy {
        UiConfig().familyInventory(
            PipelineRepository(jdbc),
            TemplateRepository(jdbc),
            DashboardRepository(jdbc),
            repository,
            ParameterSetRepository(jdbc),
        )
    }
    private val recent by lazy {
        UiConfig().familyRecent(
            PipelineRepository(jdbc),
            TemplateRepository(jdbc),
            DashboardRepository(jdbc),
            repository,
            ParameterSetRepository(jdbc),
        )
    }

    @BeforeEach
    fun seed() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'Overview', 'google', :email)",
            mapOf("id" to actor, "email" to "$actor@overview.test"),
        )
        listOf(workspace, other).forEach { id ->
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name) VALUES (:id, :name, 'Overview')",
                mapOf(
                    "id" to id,
                    "name" to "overview_$id",
                ),
            )
        }
    }

    @AfterEach
    fun cleanup() {
        val params = mapOf("ids" to listOf(workspace, other))
        jdbc.update("DELETE FROM visualizations WHERE workspace_id IN (:ids)", params)
        jdbc.update("DELETE FROM workspaces WHERE id IN (:ids)", params)
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to actor))
    }

    @Test
    fun `the four-object acceptance fixture counts objects and several releases once`() {
        fixture()
        counted(ReadLens.Everything) shouldBe Inventory(1, 1, 1, 1)
        val header =
            UiConfig().navCounts(
                PipelineRepository(jdbc),
                TemplateRepository(jdbc),
                DashboardRepository(jdbc),
                repository,
                ParameterSetRepository(jdbc),
            )
        header.invalidate(workspace)
        header.forWorkspace(workspace).visualizations shouldBe 3
        println("event=581.parity header=3 objects=4 discarded=1")
        equality(ReadLens.Everything)
        equality(ReadLens.Only(setOf("a/draft", "b/released", "c/both", "d/discarded")))
        repository.lifecyclePage(workspace, ReadLens.Everything, null, 0, PAGE, LifecycleBucket.DISCARDED_ONLY).total shouldBe 1
        val id = repository.findRecordByName(workspace, "d/discarded")?.id ?: error("fixture absent")
        service.findWorking(workspace, ReadLens.Everything, id) shouldBe null
    }

    @Test
    fun `filtered catalog follows inventory even with a draft pointer over an older release`() {
        fixture()
        insert("e/pointer_draft", listOf("RELEASED", "DRAFT"), current = 2)
        val lens = ReadLens.Only(setOf("c/both", "e/pointer_draft", "a/draft"))
        equality(ReadLens.Everything)
        equality(lens)
        counted(lens) shouldBe Inventory(0, 2, 0, 0)
        service.listAll(workspace, ReadLens.Everything).map { it.record.name }.contains("e/pointer_draft") shouldBe true
        service.listAll(workspace, lens).map { it.record.name } shouldBe listOf("c/both")
        println("event=581.unfiltered pointer_draft viewer=true promoter=false inventory_promoter=true")
        val filtered = service.lifecyclePage(workspace, lens, null, 0, PAGE, LifecycleBucket.RELEASED_ONLY)
        filtered.items.map { it.detail.version } shouldBe listOf(1, 1)
        val hidden = lens(ReadLens.Only(setOf("b/released")))
        entries(hidden).map { it.name } shouldBe listOf("b/released")
        val model = ExtendedModelMap()
        browse.fillList(model, workspace, hidden, null, 0, LifecycleBucket.WITH_DRAFTS)
        model["total"] shouldBe 0
        entries().map { it.name }.contains("a/draft") shouldBe true
        service
            .lifecyclePage(workspace, ReadLens.Everything, null, 0, PAGE, LifecycleBucket.WITH_DRAFTS)
            .items
            .map { it.record.name }
            .contains("a/draft") shouldBe true
        service.lifecyclePage(workspace, lens, null, 0, PAGE, LifecycleBucket.WITH_DRAFTS).total shouldBe 0
    }

    @Test
    fun `recent ordering covers draft edits release discard restore imports and release-only lens time`() {
        val draft = insert("a/draft", listOf("DRAFT"))
        val released = insert("b/released", listOf("RELEASED"))
        val both = insert("c/both", listOf("RELEASED", "DRAFT"))
        val discarded = insert("d/discarded", listOf("DISCARDED"))
        val partial = insert("e/partial", listOf("DISCARDED", "DRAFT"))
        insert("foreign/object", listOf("RELEASED"), workspaceId = other)
        entries().map { it.name } shouldBe listOf("a/draft", "b/released", "c/both", "e/partial")
        touch("UPDATE visualization_versions SET updated_at = :at WHERE visualization_id = :id AND status = 'DRAFT'", draft, 10)
        entries().first().name shouldBe "a/draft"
        touch("UPDATE visualization_versions SET released_at = :at WHERE visualization_id = :id", released, 20)
        entries().first().name shouldBe "b/released"
        touch("UPDATE visualization_versions SET updated_at = :at WHERE visualization_id = :id AND status = 'DRAFT'", both, 30)
        entries().first().name shouldBe "c/both"
        val promoter = lens(ReadLens.Only(setOf("a/draft", "b/released", "c/both", "d/discarded", "e/partial")))
        entries(promoter).map { it.name } shouldBe listOf("b/released", "c/both")
        entries(promoter).all { !it.hasDraft } shouldBe true
        touch("UPDATE visualizations SET updated_at = :at WHERE id = :id", both, 40)
        entries().first().modifiedAt shouldBe AT.plusSeconds(40)
        touch("UPDATE visualization_versions SET discarded_at = :at WHERE visualization_id = :id AND version = 1", partial, 50)
        entries().first().name shouldBe "e/partial"
        touch("UPDATE visualizations SET updated_at = :at WHERE id = :id", discarded, 60)
        entries().none { it.name == "d/discarded" } shouldBe true
        entries().forEach { it.href shouldBe "/visualizations/${repository.findRecordByName(workspace, it.name)?.id}" }
        entries(promoter).map { it.name } shouldBe listOf("b/released", "c/both")
    }

    @Test
    fun `filtered search composes with pagination literal metacharacters workspace and an empty lens`() {
        repeat(PAGE) { insert("many/item_$it", listOf("DRAFT")) }
        insert("many/itemX", listOf("DRAFT"))
        insert("many/item_0", listOf("DRAFT"), workspaceId = other)
        val bucket = LifecycleBucket.WITH_DRAFTS
        val first = service.lifecyclePage(workspace, ReadLens.Everything, "item_", 0, 25, bucket)
        val next = service.lifecyclePage(workspace, ReadLens.Everything, "item_", 25, 25, bucket)
        first.total shouldBe PAGE
        first.items.size shouldBe 25
        next.items.size shouldBe PAGE - 25
        (first.items.map { it.record.id }.toSet() intersect next.items.map { it.record.id }.toSet()).isEmpty() shouldBe true
        service.lifecyclePage(workspace, ReadLens.NOTHING, null, 0, PAGE, bucket).total shouldBe 0
        repository.recent(workspace, ReadLens.NOTHING) shouldBe emptyList()
        repository.recent(workspace, ReadLens.Everything).size shouldBe 5
    }

    private fun fixture() {
        insert("a/draft", listOf("DRAFT"))
        insert("b/released", listOf("RELEASED", "RELEASED", "RELEASED"))
        insert("c/both", listOf("RELEASED", "DRAFT"))
        insert("d/discarded", listOf("DISCARDED"))
    }

    private fun lens(value: ReadLens) = LensedView(ReadLens.NOTHING, ReadLens.NOTHING, visualizations = value)

    private fun counted(value: ReadLens): Inventory =
        (inventory.forFamily(NavigationFamily.VISUALIZATIONS, workspace, lens(value)) as InventoryResult.Available).inventory

    private fun entries(view: LensedView = LensedView.EVERYTHING): List<RecentEntry> =
        (recent.forFamily(NavigationFamily.VISUALIZATIONS, workspace, view) as RecentResult.Available).entries

    private fun equality(value: ReadLens) {
        val count = counted(value)
        LifecycleBucket.entries.forEach { bucket ->
            val model = ExtendedModelMap()
            browse.fillList(model, workspace, lens(value), null, 0, bucket)
            withClue("${bucket.wire} lens=$value") {
                (model["total"] as Int).toLong() shouldBe bucket.countIn(count)
                (model["visualizations"] as List<*>).size.toLong() shouldBe bucket.countIn(count)
            }
            val label = if (value.isEverything) "viewer" else "promoter"
            println("event=581.equality lens=$label ${bucket.wire}=${model["total"]}/${bucket.countIn(count)}")
        }
    }

    private fun touch(
        sql: String,
        id: UUID,
        seconds: Long,
    ) {
        jdbc.update(sql, mapOf("id" to id, "at" to java.sql.Timestamp.from(AT.plusSeconds(seconds))))
    }

    private fun insert(
        name: String,
        statuses: List<String>,
        workspaceId: UUID = workspace,
        current: Int? = statuses.indexOfLast { it == "RELEASED" }.takeIf { it >= 0 }?.plus(1),
    ): UUID {
        val id = UUID.randomUUID()
        val params =
            mapOf(
                "id" to id,
                "workspace" to workspaceId,
                "name" to name,
                "current" to current,
                "actor" to actor,
                "at" to java.sql.Timestamp.from(AT),
            )
        jdbc.update(
            "INSERT INTO visualizations (id, workspace_id, name, display_name, current_version, created_by, created_at, updated_at) " +
                "VALUES (:id, :workspace, :name, :name, :current, :actor, :at, :at)",
            params,
        )
        statuses.forEachIndexed { index, status ->
            jdbc.update(
                "INSERT INTO visualization_versions (visualization_id, version, body_json, status, body_hash, created_by, " +
                    "created_at, released_at, released_by, discarded_at, discarded_by) " +
                    "VALUES (:id, :version, CAST(:body AS jsonb), :status, :hash, :actor, :at, " +
                    "CASE WHEN :status <> 'DRAFT' THEN CAST(:at AS timestamptz) END, " +
                    "CASE WHEN :status <> 'DRAFT' THEN CAST(:actor AS uuid) END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN CAST(:at AS timestamptz) END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN CAST(:actor AS uuid) END)",
                params +
                    mapOf(
                        "version" to index + 1,
                        "status" to status,
                        "hash" to "a".repeat(64),
                        "body" to BodyCodec.Visualization.write(VisualizationUiFixtures.bodyOf()),
                    ),
            )
        }
        return id
    }

    @Configuration(proxyBeanMethods = false)
    class Config {
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
        val AT: Instant = Instant.parse("2026-10-01T12:00:00Z")
        const val PAGE = 30
    }
}
