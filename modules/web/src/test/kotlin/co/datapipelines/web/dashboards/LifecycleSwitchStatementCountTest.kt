package co.datapipelines.web.dashboards

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetConsumers
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetValidator
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.TemplateReleaser
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardLayout
import co.datapipelines.visualization.DashboardReader
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.DashboardSource
import co.datapipelines.visualization.DashboardValidator
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.parameters.ParameterSetsController
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
 * #372's statement budget at the JDBC boundary, the 332 `PromotableViewsStatementCountTest` way: the switch
 * handlers make STRICTLY FEWER statements than the re-read shape they replaced. The reads run against the
 * module's migrated [SharedPostgres] through a counting data source; everything else is faked, so every
 * statement counted is the handler's own. The switch answer (name + pointer pair) comes from the verb's own
 * statements — before this lane the parameter-set handler read the record before the switch AND the artifact
 * handlers read the version after it: 5 statements each; now the verb's one statement answers the pair: 4.
 *
 * The falsification this test exists for: restore either handler's re-read (the parameter-set pre-read
 * `findRecord`, or the artifact post-switch `findVersion`) and this count is 5 — red.
 */
class LifecycleSwitchStatementCountTest {
    private val workspace = UUID.randomUUID()
    private val counting = CountingDataSource(SharedPostgres.dataSource())

    private val dashboards =
        DashboardService(
            repository = DashboardRepository(NamedParameterJdbcTemplate(counting)),
            validator = mockk<DashboardValidator>(),
            visualizations = mockk<VisualizationService>(),
            sets = { _, _ -> null },
            authoring = AuthoringGuard(true),
            transactions = TransactionTemplate(DataSourceTransactionManager(SharedPostgres.dataSource())),
        )

    private val sets =
        ParameterSetService(
            ParameterSetRepository(NamedParameterJdbcTemplate(counting)),
            mockk<ParameterSetValidator>(),
            AuthoringGuard(true),
            TemplateVersionStatuses { _, _, _ -> null },
            object : ParameterSetConsumers {
                override fun liveVersionPins(
                    workspaceId: UUID,
                    setName: String,
                    version: Int,
                ) = emptyList<co.datapipelines.pipeline.DashboardPin>()

                override fun anyVersionPins(
                    workspaceId: UUID,
                    setName: String,
                ) = emptyList<co.datapipelines.pipeline.DashboardPin>()
            },
            TemplateReleaser.NONE,
            TransactionTemplate(DataSourceTransactionManager(SharedPostgres.dataSource())),
        )

    @BeforeEach
    fun seedOneReleasedMemberPerFamily() {
        // Seeded on the UNCOUNTED data source: the counter measures the request, never the fixture.
        val jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        val author = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name) VALUES (:id, :name, 'Ops')",
            mapOf("id" to workspace, "name" to "ops-$workspace"),
        )
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'P', 'google', :sub)",
            mapOf("id" to author, "email" to "p$author@example.com", "sub" to "sub-$author"),
        )
        DashboardRepository(jdbc).create(
            workspace,
            dashboardId,
            "ops/dashboards/board",
            DashboardBody(
                displayName = "board",
                sources = listOf(DashboardSource("a", ArtifactRef("ops/pipelines/a", 1))),
                visualizations = emptyList(),
                layout = DashboardLayout(),
            ),
            author,
            CreateLifecycle.RELEASED,
            WriteSurface.MCP,
        )
        ParameterSetRepository(jdbc).create(
            workspace,
            setId,
            "ops/sets/filters",
            ParameterSetJson.mapper.treeToValue(
                ParameterSetJson.mapper.readTree(
                    // {"display_name","description","parameters":[{ "name","label","type","kind","cardinality","required",
                    //   "source":{"constants":[{ "value","display_value","is_default" }]} }]}
                    // The set's stored body: one labelled SELECT parameter over constants.
                    """{"display_name":"Filters","description":"","parameters":[{"name":"country","label":"Country","type":"STRING",""" +
                        """"kind":"SELECT","cardinality":"SINGLE","required":true,"source":{"constants":[""" +
                        """{"value":"USA","display_value":"United States","is_default":true}]}}]}""",
                ),
                ParameterSetBody::class.java,
            ),
            author,
            CreateLifecycle.RELEASED,
            WriteSurface.MCP,
        )
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `the dashboard switch handler answers from the verb's own statements - four, not the re-read shape's five`() {
        authenticate()
        counting.statements = 0

        val controller = DashboardsController(dashboards, DashboardReader(), co.datapipelines.web.EVERYTHING_LENS, RecordingAudit())
        val response =
            controller
                .switchCurrent(
                    dashboardId,
                    """{"version": 1}""",
                )

        response.data["current_version"] shouldBe 1
        counting.statements shouldBe 4
    }

    @Test
    fun `the parameter-set switch handler answers from the verb's own statements - four, not the re-read shape's five`() {
        authenticate()
        counting.statements = 0

        val controller =
            ParameterSetsController(
                sets,
                ParameterSetRepository(NamedParameterJdbcTemplate(counting)),
                mockk<co.datapipelines.parameters.ParameterEvaluator>(),
                mockk<co.datapipelines.web.parameters.ParameterSetTransferService>(),
                ParametersConfig(),
                co.datapipelines.web.EVERYTHING_LENS,
                RecordingAudit(),
            )
        val response =
            controller.switchCurrent(
                setId,
                ParameterSetJson.mapper.readTree("""{"version": 1}"""),
            )

        response.data["current_version"] shouldBe 1
        counting.statements shouldBe 4
    }

    // ---- fixtures --------------------------------------------------------------------------------------

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "p@e.test",
                "P",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspace, "ops"),
            )
        SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    /** The recording fake the #332 assertions read — in memory, so no audit statement is ever counted. */
    private class RecordingAudit : co.datapipelines.auth.AuditEventSink {
        val events = mutableListOf<String>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            events.add(event)
        }
    }

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

    // One test instance per method: each test seeds and counts its own rows.
    private val dashboardId: UUID = UUID.randomUUID()
    private val setId: UUID = UUID.randomUUID()
}
