package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.CurrentPipelineVersion
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.DashboardValidator
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.SharedPostgres
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.Proxy
import java.util.UUID
import javax.sql.DataSource

/**
 * #330's acceptance, at the JDBC boundary: a lensed principal's request that touches neither family issues ZERO
 * dashboard reads; one that does issues at most ONE statement for the pins — the pins-and-sources projection,
 * once for both arms. The dashboard reads are real statements against the module's migrated [SharedPostgres];
 * the other families are mocks, so every statement the counter sees is a dashboard read. Red if the view builds
 * the dashboard arms eagerly (the pre-#330 view read every current released dashboard's BODY per lensed
 * request) or runs the derivation once per arm.
 */
class PromotableViewsStatementCountTest {
    private val workspace = UUID.randomUUID()
    private val counting = CountingDataSource(SharedPostgres.dataSource())

    private val dashboards =
        DashboardService(
            repository = DashboardRepository(NamedParameterJdbcTemplate(counting)),
            validator = DashboardValidator({ _, _ -> null }, { _, _ -> null }, { _, _ -> null }),
            visualizations = mockk<VisualizationService>(),
            sets = { _, _ -> null },
            authoring = AuthoringGuard(true),
            transactions = TransactionTemplate(DataSourceTransactionManager(SharedPostgres.dataSource())),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a lensed request that touches neither family runs ZERO dashboard reads`() {
        // The counted window is the REQUEST: viewFor itself included — the pre-#330 view read the
        // dashboards inside viewFor, and a window that started after it would measure nothing.
        counting.statements = 0
        val view = views().viewFor(promoter())

        view.pipelines shouldBe ReadLens.Only(emptySet())
        view.templates shouldBe ReadLens.Only(emptySet())
        view.parameterSets shouldBe ReadLens.Only(emptySet())
        counting.statements shouldBe 0
    }

    @Test
    fun `a request that touches the families derives BOTH arms from ONE projection statement`() {
        val pipelines = mockk<PipelineRepository>()
        every { pipelines.findCurrentVersions(workspace) } returns
            listOf(CurrentPipelineVersion(UUID.randomUUID(), "ops/pipelines/a", "A", 1, "h"))

        counting.statements = 0
        val view = views(pipelines = pipelines).viewFor(promoter())

        view.visualizations shouldBe ReadLens.Only(emptySet())
        view.dashboards shouldBe ReadLens.Only(emptySet())
        counting.statements shouldBe 1
    }

    @Test
    fun `an unreachable target fails closed with no dashboard read at all`() {
        val client = mockk<PromotionTargetClient>()
        every { client.cachedInventory("ops") } returns PromotionTargetClient.CachedInventory.Unreachable("connect_refused", "x")
        every { client.targetBaseUrl } returns "https://uat.example.test"

        counting.statements = 0
        val view = views(client = client).viewFor(promoter())

        view.visualizations shouldBe ReadLens.NOTHING
        view.dashboards shouldBe ReadLens.NOTHING
        view.unavailable?.reason shouldBe "connect_refused"
        counting.statements shouldBe 0
    }

    // ---- fixtures --------------------------------------------------------------------------------------

    private fun views(
        pipelines: PipelineRepository =
            mockk<PipelineRepository>().also {
                every { it.findCurrentVersions(workspace) } returns emptyList()
            },
        client: PromotionTargetClient = defaultClient(),
    ): PromotableViews {
        val templates = mockk<TemplateRepository>()
        every { templates.findCurrentVersions(workspace) } returns emptyList()
        val sets = mockk<co.datapipelines.parameters.ParameterSetRepository>()
        every { sets.findCurrentVersions(workspace) } returns emptyList()
        return PromotableViews(pipelines, templates, client, sets, dashboards, mockk<VisualizationService>())
    }

    private fun defaultClient(): PromotionTargetClient {
        val client = mockk<PromotionTargetClient>()
        every { client.cachedInventory("ops") } returns
            PromotionTargetClient.CachedInventory.Present(PromotionWire.Inventory("uat", false, "ops"))
        return client
    }

    private fun promoter() =
        AuthenticatedPrincipal(
            UUID.randomUUID(),
            "p@e.test",
            "P",
            AuthMethod.OIDC,
            workspace = WorkspaceContext(workspace, "ops", role = WorkspaceRole.PROMOTER),
        )

    /** The statement counter at the JDBC boundary — the house `CountingDataSource` shape, no library. */
    private class CountingDataSource(
        private val target: DataSource,
    ) : DelegatingDataSource(target) {
        var statements = 0

        override fun getConnection(): java.sql.Connection = wrap(target.connection)

        private fun wrap(connection: java.sql.Connection): java.sql.Connection =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                val result = method.invoke(connection, *(args ?: emptyArray()))
                if (result is java.sql.PreparedStatement) statement(result) else result
            } as java.sql.Connection

        private fun statement(ps: java.sql.PreparedStatement): java.sql.PreparedStatement =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(java.sql.PreparedStatement::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch" -> statements++
                }
                method.invoke(ps, *(args ?: emptyArray()))
            } as java.sql.PreparedStatement
    }
}
