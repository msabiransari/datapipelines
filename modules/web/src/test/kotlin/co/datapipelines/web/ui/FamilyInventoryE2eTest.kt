package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.NavigationRequest
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
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.test.annotation.DirtiesContext
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * Spring slice invoking UiConfig's production inventory factory against the shared migrated PostgreSQL.
 * Lives in web because the end-to-end module may import only app, which hides these domain types.
 * Unique workspaces are cleaned without touching another suite; the fixture owns its connection pool.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [FamilyInventoryE2eTest.InventoryConfiguration::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FamilyInventoryE2eTest {
    @Autowired private lateinit var inventory: FamilyInventory

    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate
    private val workspace = UUID.randomUUID()
    private val otherWorkspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    @BeforeEach
    fun seedContext() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'Inventory', 'google', :email)",
            mapOf("id" to actor, "email" to "$actor@inventory.test"),
        )
        listOf(workspace, otherWorkspace).forEach { id ->
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name) VALUES (:id, :name, 'Inventory')",
                mapOf("id" to id, "name" to "inventory_$id"),
            )
        }
    }

    @AfterEach
    fun cleanOwnRows() {
        val params = mapOf("ids" to listOf(workspace, otherWorkspace))
        NavigationFamily.entries.forEach { jdbc.update("DELETE FROM ${it.table} WHERE workspace_id IN (:ids)", params) }
        jdbc.update("DELETE FROM workspaces WHERE id IN (:ids)", params)
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to actor))
    }

    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `four buckets count distinct objects and catalog excludes only discarded`(family: NavigationFamily) {
        fixture(family)
        val expected = Inventory(1, 1, 1, 1)
        available(family) shouldBe expected
        expected.objects shouldBe 4L
        expected.withDrafts shouldBe 2L
        expected.withReleases shouldBe 2L
        val released = idOf(family, "released")
        version(family, released, 2, "RELEASED")
        version(family, released, 3, "RELEASED")
        withClue("three RELEASED versions still represent one object") { available(family) shouldBe expected }
        val listed = catalog(family, ReadLens.Everything)
        listed shouldBe setOf("draft", "released", "both")
        listed.size.toLong() shouldBe expected.objects - expected.discardedOnly
        available(family, workspaceId = otherWorkspace) shouldBe Inventory(1, 0, 0, 0)
        version(family, idOf(family, "draft"), 2, "DISCARDED")
        version(family, released, 4, "DISCARDED")
        version(family, idOf(family, "both"), 3, "DISCARDED")
        jdbc.update(
            "UPDATE ${family.table} SET current_version = 2 WHERE id = :id",
            mapOf("id" to idOf(family, "both")),
        )
        withClue("historical discards and a draft pointer do not change admitted version classification") {
            available(family) shouldBe expected
            available(family, view(family, ReadLens.Only(setOf("both")))) shouldBe Inventory(0, 1, 0, 0)
        }
    }

    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `each family lens hides drafts before classification and names without releases are absent`(family: NavigationFamily) {
        fixture(family)
        insert(family, "hidden_release", listOf("RELEASED"))
        val lens = ReadLens.Only(setOf("released", "both"))
        withClue("a hidden draft must classify C as B for $family") {
            available(family, view(family, lens)) shouldBe Inventory(0, 2, 0, 0)
        }
        catalog(family, lens) shouldBe setOf("released", "both")
        val allNames = ReadLens.Only(setOf("released", "both", "draft", "discarded", "absent", "' OR 1=1 --"))
        available(family, view(family, allNames)) shouldBe Inventory(0, 2, 0, 0)
        available(family, view(family, allNames), otherWorkspace) shouldBe Inventory(0, 0, 0, 0)
        catalog(family, allNames) shouldBe setOf("released", "both")
    }

    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `empty and unavailable lenses execute zero SQL while a normal aggregate uses one read`(family: NavigationFamily) {
        fixture(family)
        val source = CountingSource(requireNotNull(jdbc.jdbcTemplate.dataSource))
        val counted = service(NamedParameterJdbcTemplate(source))
        val empty = counted.forFamily(family, workspace, view(family, ReadLens.NOTHING))
        withClue("empty lens must not acquire a SQL connection") { source.reads shouldBe 0 }
        empty shouldBe InventoryResult.Available(Inventory(0, 0, 0, 0))
        val unavailable = LensedView(ReadLens.Everything, ReadLens.Everything, LensedView.Unavailable("target", "Target unavailable"))
        counted.forFamily(family, workspace, unavailable) shouldBe InventoryResult.Unavailable("Target unavailable")
        source.reads shouldBe 0
        counted.forFamily(family, workspace, LensedView.EVERYTHING) shouldBe InventoryResult.Available(Inventory(1, 1, 1, 1))
        source.reads shouldBe 1
        println("family=$family empty_sql=0 unavailable_sql=0 normal_reads=${source.reads}")
        source.fail = true
        counted.forFamily(family, workspace, LensedView.EVERYTHING) shouldBe InventoryResult.Unavailable("Inventory could not be read.")
    }

    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `three releases count once and purged objects disappear`(family: NavigationFamily) {
        insert(family, "multiple", listOf("RELEASED", "RELEASED", "RELEASED"))
        withClue("distinct objects, never version rows") { available(family) shouldBe Inventory(0, 1, 0, 0) }
        jdbc.update("DELETE FROM ${family.table} WHERE id = :id", mapOf("id" to idOf(family, "multiple")))
        available(family) shouldBe Inventory(0, 0, 0, 0)
    }

    @Test
    fun `inventory exceeds the ten page tree ceiling`() {
        val family = NavigationFamily.PIPELINES
        repeat(BEYOND_CEILING) { insert(family, "object_$it", listOf("RELEASED")) }
        available(family) shouldBe Inventory(0, BEYOND_CEILING.toLong(), 0, 0)
        catalog(family, ReadLens.Everything).size shouldBe NavigationRequest.PAGE_SIZE
    }

    private fun fixture(family: NavigationFamily) {
        insert(family, "draft", listOf("DRAFT"))
        insert(family, "released", listOf("RELEASED"))
        insert(family, "both", listOf("RELEASED", "DRAFT"))
        insert(family, "discarded", listOf("DISCARDED"))
        insert(family, "released", listOf("DRAFT"), otherWorkspace)
    }

    private fun available(
        family: NavigationFamily,
        view: LensedView = LensedView.EVERYTHING,
        workspaceId: UUID = workspace,
    ): Inventory = (inventory.forFamily(family, workspaceId, view) as InventoryResult.Available).inventory

    private fun view(
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

    private fun service(source: NamedParameterJdbcTemplate): FamilyInventory =
        FamilyInventory(
            PipelineRepository(source),
            TemplateRepository(source),
            DashboardRepository(source),
            VisualizationRepository(source),
            ParameterSetRepository(source),
        )

    private fun catalog(
        family: NavigationFamily,
        lens: ReadLens,
    ): Set<String> {
        val request = NavigationRequest(workspace, lens, "", "", null)
        return when (family) {
            NavigationFamily.PIPELINES -> PipelineRepository(jdbc).navigation(request)
            NavigationFamily.TEMPLATES -> TemplateRepository(jdbc).navigation(request)
            NavigationFamily.DASHBOARDS -> DashboardRepository(jdbc).navigation(request)
            NavigationFamily.VISUALIZATIONS -> VisualizationRepository(jdbc).navigation(request)
            NavigationFamily.PARAMETER_SETS -> ParameterSetRepository(jdbc).navigation(request)
        }.map { it.path }.toSet()
    }

    private fun idOf(
        family: NavigationFamily,
        name: String,
    ): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT id FROM ${family.table} WHERE workspace_id = :workspace AND name = :name",
                mapOf("workspace" to workspace, "name" to name),
                UUID::class.java,
            ),
        )

    private fun insert(
        family: NavigationFamily,
        name: String,
        statuses: List<String>,
        workspaceId: UUID = workspace,
    ) {
        val id = UUID.randomUUID()
        val owner = if (family == NavigationFamily.PIPELINES) "owner_id" else "created_by"
        val current = statuses.indexOfLast { it == "RELEASED" }.takeIf { it >= 0 }?.plus(1)
        jdbc.update(
            "INSERT INTO ${family.table} (id, workspace_id, name, display_name, current_version, $owner) " +
                "VALUES (:id, :workspace, :name, :name, :current, :actor)",
            mapOf("id" to id, "workspace" to workspaceId, "name" to name, "current" to current, "actor" to actor),
        )
        statuses.forEachIndexed { index, status -> version(family, id, index + 1, status) }
    }

    private fun version(
        family: NavigationFamily,
        id: UUID,
        number: Int,
        status: String,
    ) {
        val contentColumns = if (family == NavigationFamily.TEMPLATES) "body, dialect" else "body_json"
        val content = if (family == NavigationFamily.TEMPLATES) "'select 1', 'POSTGRES'" else "'{}'::jsonb"
        val versions = family.table.removeSuffix("s") + "_versions"
        jdbc.update(
            "INSERT INTO $versions (${family.foreignKey}, version, $contentColumns, status, body_hash, " +
                "created_by, released_at, released_by, discarded_at, discarded_by) " +
                "VALUES (:id, :version, $content, :status, :hash, :actor, CASE WHEN :status <> 'DRAFT' THEN NOW() END, " +
                "CASE WHEN :status <> 'DRAFT' THEN :actor END, CASE WHEN :status = 'DISCARDED' THEN NOW() END, " +
                "CASE WHEN :status = 'DISCARDED' THEN :actor END)",
            mapOf("id" to id, "version" to number, "status" to status, "hash" to "a".repeat(64), "actor" to actor),
        )
    }

    private class CountingSource(
        source: DataSource,
    ) : DelegatingDataSource(source) {
        var reads = 0
        var fail = false

        override fun getConnection(): Connection {
            reads++
            if (fail) throw SQLException("Injected inventory read failure")
            return super.getConnection()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class InventoryConfiguration {
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
        // state.mjs PAGE_CEILING=10; TreeProjection delivers NavigationRequest.PAGE_SIZE=200.
        const val BEYOND_CEILING = 10 * NavigationRequest.PAGE_SIZE + 1
    }
}
