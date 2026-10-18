package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.RecentSource
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.web.SharedPostgres
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.test.annotation.DirtiesContext
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * #556 A.5 — the Pipelines overview's Recently updated ([FamilyRecent] over `RecentSource`) against
 * the shared migrated PostgreSQL, through UiConfig's production factories, beside the inventory and
 * the browser header count it must agree with ([FamilyInventoryE2eTest]'s fixture shape).
 *
 * The modification time is the GREATEST of four columns (`RecentSource`'s KDoc), so each column is
 * moved on its own and must bring its object to the top; a narrowed lens reads RELEASED rows and
 * `released_at` only, so a hidden draft's write neither reorders nor reveals; a discarded-only
 * object has no workspace page and is never listed.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [FamilyRecentE2eTest.RecentConfiguration::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FamilyRecentE2eTest {
    @Autowired private lateinit var recent: FamilyRecent

    @Autowired private lateinit var inventory: FamilyInventory

    @Autowired private lateinit var navCounts: NavCounts

    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate
    private val workspace = UUID.randomUUID()
    private val otherWorkspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    @BeforeEach
    fun seedContext() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'Recent', 'google', :email)",
            mapOf("id" to actor, "email" to "$actor@recent.test"),
        )
        listOf(workspace, otherWorkspace).forEach { id ->
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name) VALUES (:id, :name, 'Recent')",
                mapOf(
                    "id" to id,
                    "name" to "recent_$id",
                ),
            )
        }
    }

    @AfterEach
    fun cleanOwnRows() {
        val params = mapOf("ids" to listOf(workspace, otherWorkspace))
        jdbc.update("DELETE FROM pipelines WHERE workspace_id IN (:ids)", params)
        jdbc.update("DELETE FROM workspaces WHERE id IN (:ids)", params)
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to actor))
    }

    @Test
    fun `newest first over the four modification columns, discarded-only never listed, other workspaces never read`() {
        fixture()
        // Every row starts at one old instant, so only the column a step moves can reorder the list.
        age("pipelines", "updated_at")
        listOf("created_at", "updated_at", "released_at", "discarded_at").forEach { age("pipeline_versions", it) }
        names() shouldContainExactly listOf("both", "discarded_draft", "draft", "released")

        touch("UPDATE pipeline_versions SET updated_at = NOW() WHERE pipeline_id = :id AND status = 'DRAFT'", "draft")
        withClue("an in-place draft write moves only the version row's updated_at") { names().first() shouldBe "draft" }
        touch("UPDATE pipeline_versions SET released_at = NOW() + INTERVAL '1 minute' WHERE pipeline_id = :id", "released")
        withClue("a release moves released_at") { names().first() shouldBe "released" }
        touch("UPDATE pipelines SET updated_at = NOW() + INTERVAL '2 minutes' WHERE id = :id", "both")
        withClue("a switch or restore moves the parent's updated_at") { names().first() shouldBe "both" }
        touch(
            "UPDATE pipeline_versions SET discarded_at = NOW() + INTERVAL '3 minutes' WHERE pipeline_id = :id AND version = 1",
            "discarded_draft",
        )
        withClue("a discard of one version moves discarded_at, and the object keeps a live draft") {
            names().first() shouldBe "discarded_draft"
        }
        withClue("the discarded-only object has no page to link, whatever its clock says") {
            touch("UPDATE pipelines SET updated_at = NOW() + INTERVAL '1 hour' WHERE id = :id", "discarded")
            ("discarded" in names()) shouldBe false
        }
        entries().first { it.name == "both" }.hasDraft shouldBe true
        entries().first { it.name == "released" }.hasDraft shouldBe false
        entries().forEach { it.href shouldBe "/pipelines/${idOf(it.name)}" }
        withClue("the other workspace lists its own same-named release, never this workspace's row") {
            val other =
                (
                    recent.forFamily(
                        NavigationFamily.PIPELINES,
                        otherWorkspace,
                        LensedView.EVERYTHING,
                    ) as RecentResult.Available
                ).entries
            other.map { it.name } shouldContainExactly listOf("released")
            (other.single().href == "/pipelines/${idOf("released")}") shouldBe false
        }
    }

    @Test
    fun `at most five, newest first, the name breaking a tie`() {
        repeat(RecentSource.DEFAULT_LIMIT + 2) { insert("bulk_$it", listOf("RELEASED")) }
        age("pipelines", "updated_at")
        listOf("created_at", "released_at").forEach { age("pipeline_versions", it) }

        names() shouldContainExactly (0 until RecentSource.DEFAULT_LIMIT).map { "bulk_$it" }
    }

    /**
     * The hidden-draft case (record `:349`): a narrowed lens admits `both` and `released`; `both`'s
     * DRAFT is hidden, so it lists without a draft, at its release time, and a write to that draft —
     * or to the parent — never moves it; `draft` (no release) is absent.
     */
    @Test
    fun `a narrowed lens lists releases at their release time and a hidden draft moves nothing`() {
        fixture()
        age("pipelines", "updated_at")
        listOf("created_at", "updated_at", "released_at", "discarded_at").forEach { age("pipeline_versions", it) }
        val lens = lens(ReadLens.Only(setOf("both", "released", "draft", "discarded")))
        touch("UPDATE pipeline_versions SET released_at = NOW() WHERE pipeline_id = :id", "released")

        touch("UPDATE pipeline_versions SET updated_at = NOW() + INTERVAL '1 hour' WHERE pipeline_id = :id AND status = 'DRAFT'", "both")
        touch("UPDATE pipelines SET updated_at = NOW() + INTERVAL '1 hour' WHERE id = :id", "both")

        val promoter = entries(lens)
        promoter.map { it.name } shouldContainExactly listOf("released", "both")
        promoter.map { it.hasDraft } shouldContainExactly listOf(false, false)
        withClue("the viewer sees the same rows the other way round, the draft included") {
            entries().map { it.name }.take(2) shouldContainExactly listOf("both", "released")
            entries().first().hasDraft shouldBe true
            ("draft" in names()) shouldBe true
        }
    }

    @Test
    fun `an empty lens reads nothing, an unknown lens is unavailable, a failed read is unavailable`() {
        fixture()
        val source = CountingSource(requireNotNull(jdbc.jdbcTemplate.dataSource))
        val counted =
            FamilyRecent(PipelineRepository(NamedParameterJdbcTemplate(source)), DashboardRepository(NamedParameterJdbcTemplate(source)))

        counted.forFamily(NavigationFamily.PIPELINES, workspace, lens(ReadLens.NOTHING)) shouldBe RecentResult.Available(emptyList())
        source.reads shouldBe 0
        val unknown = LensedView(ReadLens.Everything, ReadLens.Everything, LensedView.Unavailable("target", "Target unavailable"))
        counted.forFamily(NavigationFamily.PIPELINES, workspace, unknown) shouldBe RecentResult.Unavailable("Target unavailable")
        source.reads shouldBe 0
        counted.forFamily(NavigationFamily.PIPELINES, workspace, LensedView.EVERYTHING).shouldBeInstanceOf<RecentResult.Available>()
        source.reads shouldBe 1
        source.fail = true
        counted.forFamily(NavigationFamily.PIPELINES, workspace, LensedView.EVERYTHING) shouldBe
            RecentResult.Unavailable("Recently updated could not be read.")
        NavigationFamily.entries.filter { it != NavigationFamily.PIPELINES && it != NavigationFamily.DASHBOARDS }.forEach { family ->
            counted.forFamily(family, workspace, LensedView.EVERYTHING) shouldBe null
        }
    }

    /** Fact 6's parity: on one snapshot the browser header's count is the inventory's A + B + C. */
    @Test
    fun `the browser header count is the inventory without its discarded-only objects`() {
        fixture()
        navCounts.invalidate(workspace)
        val counted =
            (
                inventory.forFamily(
                    NavigationFamily.PIPELINES,
                    workspace,
                    LensedView.EVERYTHING,
                ) as InventoryResult.Available
            ).inventory

        counted shouldBe Inventory(draftOnly = 2, releasedOnly = 1, releasedWithDraft = 1, discardedOnly = 1)
        navCounts.forWorkspace(workspace).pipelines shouldBe (counted.objects - counted.discardedOnly).toInt()
    }

    /** `discarded_draft` (v1 DISCARDED, v2 DRAFT) is the draft-only bucket's second shape. */
    private fun fixture() {
        insert("draft", listOf("DRAFT"))
        insert("released", listOf("RELEASED"))
        insert("both", listOf("RELEASED", "DRAFT"))
        insert("discarded", listOf("DISCARDED"))
        insert("discarded_draft", listOf("DISCARDED", "DRAFT"))
        insert("released", listOf("RELEASED"), otherWorkspace)
    }

    private fun entries(view: LensedView = LensedView.EVERYTHING): List<RecentEntry> =
        (recent.forFamily(NavigationFamily.PIPELINES, workspace, view) as RecentResult.Available).entries

    private fun names(): List<String> = entries().map { it.name }

    private fun lens(pipelines: ReadLens): LensedView =
        LensedView(
            pipelines = pipelines,
            templates = ReadLens.NOTHING,
            parameterSets = ReadLens.NOTHING,
            visualizations = ReadLens.NOTHING,
            dashboards = ReadLens.NOTHING,
        )

    /** Sets [column] of every row of this workspace in [table] (where non-null) to one old instant. */
    private fun age(
        table: String,
        column: String,
    ) {
        val scope =
            if (table == "pipelines") {
                "workspace_id = :workspace"
            } else {
                "pipeline_id IN (SELECT id FROM pipelines WHERE workspace_id = :workspace)"
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
                "SELECT id FROM pipelines WHERE workspace_id = :workspace AND name = :name",
                mapOf("workspace" to workspace, "name" to name),
                UUID::class.java,
            ),
        )

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

    private class CountingSource(
        source: DataSource,
    ) : DelegatingDataSource(source) {
        var reads = 0
        var fail = false

        override fun getConnection(): Connection {
            reads++
            if (fail) throw SQLException("Injected recent read failure")
            return super.getConnection()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class RecentConfiguration {
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
            UiConfig().familyRecent(PipelineRepository(jdbc), DashboardRepository(jdbc))

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
