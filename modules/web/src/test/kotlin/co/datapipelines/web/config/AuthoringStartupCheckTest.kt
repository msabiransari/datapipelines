package co.datapipelines.web.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import co.datapipelines.application.dashboards.RefreshPlanner
import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.ActionScope
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardLayout
import co.datapipelines.visualization.DashboardObjectType
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.visualization.DashboardRuntimeKey
import co.datapipelines.visualization.DashboardSource
import co.datapipelines.visualization.InputMapping
import co.datapipelines.visualization.ListedDashboard
import co.datapipelines.visualization.ParameterBinding
import co.datapipelines.visualization.VisualizationOccurrence
import co.datapipelines.web.capturingLogEvents
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * The deployment-posture boot checks (versioning §5.5 / configuration.md §7, 039 C2+C5+C8):
 * the receiver-also-authors WARN (driven through the seam — the promotion half of the
 * combination is reserved config, so the check is currently ONE-SIDED and the seam answers
 * "no key" in production until promotion ships), and the refusal when an authoring-disabled
 * deployment holds existing drafts, naming them. #762 adds the WARN naming saved dashboards whose static invocation upper bounds
 * exceed the smaller refresh ceiling.
 */
class AuthoringStartupCheckTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()

    // #300: required — the set-draft check is reachable by construction; no null collaborator exists.
    private val parameterSets = mockk<co.datapipelines.parameters.ParameterSetRepository>()

    // #10 L1b: required like the set repository; no drafts unless a case says so.
    private val visualizations =
        mockk<co.datapipelines.visualization.VisualizationRepository> { every { findAllDraftNames() } returns emptyList() }
    private val dashboards =
        mockk<co.datapipelines.visualization.DashboardRepository> {
            every { findAllDraftNames() } returns emptyList()
            every { findAllListed() } returns emptyList()
        }

    // #762: the two ceilings a saved dashboard is judged against at boot — the executor's 16 and one refresh's 16.
    private val slots = ExecutionSlots(maxPerUser = 4, maxPerInstance = 16)
    private val runtime = DashboardRuntimeConfig()

    private fun environment(authoring: String?): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "test",
                    if (authoring == null) emptyMap() else mapOf(AuthoringGuard.CONFIG_KEY to authoring),
                ),
            )
        }

    /** The check over this class's repositories; [slots] defaults to the executor's 16. */
    private fun startupCheck(
        environment: StandardEnvironment,
        slots: ExecutionSlots = this.slots,
    ) = AuthoringStartupCheck(environment, pipelines, templates, parameterSets, visualizations, dashboards, slots, runtime)

    @Test
    fun `authoring enabled with no promotion key is the quiet default`() {
        val check = startupCheck(environment("true"))

        shouldNotThrow<Exception> { check.check() }
        verify(exactly = 0) { pipelines.findAllDraftPipelineNames() }
    }

    @Test
    fun `a promotion key on an authoring-enabled deployment WARNs but starts`() {
        // The WARN's logic, driven through the seam the promotion round wires. In
        // production the seam currently answers "no key" (reserved config), so this only
        // fires once promotion ships — the check is one-sided by design, not by omission.
        val check =
            AuthoringStartupCheck(
                environment("true"),
                pipelines,
                templates,
                parameterSets,
                visualizations,
                dashboards,
                slots,
                runtime,
                promotionServerKeyPresent = { true },
            )

        shouldNotThrow<Exception> { check.check() }
        verify(exactly = 0) { pipelines.findAllDraftPipelineNames() }
    }

    @Test
    fun `a promotion key on an authoring-DISABLED deployment is the consistent receiver - no warn`() {
        val check =
            AuthoringStartupCheck(
                environment("false"),
                pipelines,
                templates,
                parameterSets,
                visualizations,
                dashboards,
                slots,
                runtime,
                promotionServerKeyPresent = { true },
            )
        every { pipelines.findAllDraftPipelineNames() } returns emptyList()
        every { templates.findAllDraftTemplateNames() } returns emptyList()
        every { parameterSets.findAllDraftParameterSetNames() } returns emptyList()

        shouldNotThrow<Exception> { check.check() }
    }

    @Test
    fun `authoring disabled with no drafts starts`() {
        every { pipelines.findAllDraftPipelineNames() } returns emptyList()
        every { templates.findAllDraftTemplateNames() } returns emptyList()
        every { parameterSets.findAllDraftParameterSetNames() } returns emptyList()

        shouldNotThrow<Exception> {
            startupCheck(environment("false")).check()
        }
    }

    @Test
    fun `authoring disabled with existing drafts refuses startup, naming them`() {
        // C8: a receiver holding drafts means someone authored there — found at boot,
        // not at the next promotion's 409.
        every { pipelines.findAllDraftPipelineNames() } returns listOf("monthly_revenue", "churn_rollup")
        every { templates.findAllDraftTemplateNames() } returns listOf("fetch_orders.sql")
        every { parameterSets.findAllDraftParameterSetNames() } returns emptyList()

        val refused =
            shouldThrow<IllegalStateException> {
                startupCheck(environment("false")).check()
            }

        refused.message shouldContain "monthly_revenue"
        refused.message shouldContain "churn_rollup"
        refused.message shouldContain "fetch_orders.sql"
        (refused.message?.contains("datapipelines.deployment.authoring-enabled") ?: false) shouldBe true
    }

    // ---- C14 (#194 lane D): parameter-set drafts join the refusal -----------------------

    @Test
    fun `authoring disabled with a parameter-set draft refuses startup, naming it`() {
        every { pipelines.findAllDraftPipelineNames() } returns emptyList()
        every { templates.findAllDraftTemplateNames() } returns emptyList()
        every { parameterSets.findAllDraftParameterSetNames() } returns listOf("acme/sales/region_filters")

        val refused =
            shouldThrow<IllegalStateException> {
                startupCheck(environment("false")).check()
            }

        refused.message shouldContain "acme/sales/region_filters"
        refused.message shouldContain "parameter set"
    }

    @Test
    fun `authoring enabled never asks the parameter-set repository for drafts`() {
        shouldNotThrow<Exception> {
            startupCheck(environment("true")).check()
        }
        verify(exactly = 0) { parameterSets.findAllDraftParameterSetNames() }
    }

    // ---- #10 L1b: visualization and dashboard drafts join the refusal ----------------------

    @Test
    fun `authoring disabled with a visualization or a dashboard draft refuses startup, naming both`() {
        every { pipelines.findAllDraftPipelineNames() } returns emptyList()
        every { templates.findAllDraftTemplateNames() } returns emptyList()
        every { parameterSets.findAllDraftParameterSetNames() } returns emptyList()
        every { visualizations.findAllDraftNames() } returns listOf("finance/visualizations/monthly_revenue")
        every { dashboards.findAllDraftNames() } returns listOf("finance/dashboards/revenue_overview")

        val refused =
            shouldThrow<IllegalStateException> {
                startupCheck(environment("false")).check()
            }

        refused.message shouldContain "visualization: finance/visualizations/monthly_revenue"
        refused.message shouldContain "dashboard: finance/dashboards/revenue_overview"
    }

    @Test
    fun `authoring enabled never asks the visualization or dashboard repository for drafts`() {
        shouldNotThrow<Exception> {
            startupCheck(environment("true")).check()
        }
        verify(exactly = 0) { visualizations.findAllDraftNames() }
        verify(exactly = 0) { dashboards.findAllDraftNames() }
    }

    @Test
    fun `a null parameter-set repository keeps the pre-C14 behaviour`() {
        every { pipelines.findAllDraftPipelineNames() } returns emptyList()
        every { templates.findAllDraftTemplateNames() } returns emptyList()
        every { parameterSets.findAllDraftParameterSetNames() } returns emptyList()

        shouldNotThrow<Exception> {
            startupCheck(environment("false")).check()
        }
    }

    // ---- #762: saved dashboards with possible refresh refusals are named at boot ---------

    @Test
    fun `a saved dashboard needing more distinct executions than the smaller ceiling is named once, with its numbers`() {
        // The executor's 4 is the smaller ceiling; one refresh may run 16. Two sources share one execution.
        every { dashboards.findAllListed() } returns
            listOf(
                ListedDashboard("acme", "acme/dashboards/explorer", 3, board(distinct = 5, shared = 1)),
                ListedDashboard("acme", "acme/dashboards/pulse", 1, board(distinct = 4)),
                ListedDashboard("other", "other/dashboards/broken", 2, null),
            )

        val warnings =
            boot(ExecutionSlots(maxPerUser = 4, maxPerInstance = 4)).filter { it.level == Level.WARN }.map { it.formattedMessage }

        warnings.size shouldBe 1
        warnings.single() shouldContain "event=config.dashboards_above_refresh_ceiling count=1 ceiling=4 " +
            "key=${ExecutionSlots.INSTANCE_CEILING_KEY}"
        warnings.single() shouldContain "acme/acme/dashboards/explorer@3 (5)"
        warnings.single() shouldNotContain "pulse"
        warnings.single() shouldNotContain "broken"
    }

    @Test
    fun `the boot warning is an upper bound - equal evaluated bindings and target subsets can still fit`() {
        val pipeline = ArtifactRef("acme/pipelines/shared", 1)
        val body =
            board(distinct = 2).copy(
                sources =
                    listOf(
                        DashboardSource("s1", pipeline, mapOf("x" to ParameterBinding(parameter = "a"))),
                        DashboardSource("s2", pipeline, mapOf("x" to ParameterBinding(parameter = "b"))),
                    ),
            )
        every { dashboards.findAllListed() } returns listOf(ListedDashboard("acme", "acme/dashboards/shared", 1, body))

        val warning = boot(ExecutionSlots(4, 1)).single { it.level == Level.WARN }.formattedMessage
        warning shouldContain "all-source invocation upper bound"
        warning shouldContain "Equal evaluated bindings or fewer targets"
        warning shouldNotContain "every refresh"
        val one = com.fasterxml.jackson.databind.node.IntNode.valueOf(1)
        val two = com.fasterxml.jackson.databind.node.IntNode.valueOf(2)
        val planner = RefreshPlanner()
        planner.plan(body, ActionScope.ALL, emptyList(), mapOf("a" to one, "b" to one)).invocations.size shouldBe 1
        planner.plan(body, ActionScope.TARGETS, listOf("v_s1"), mapOf("a" to one, "b" to two)).invocations.size shouldBe 1
    }

    @Test
    fun `a dashboard at the ceiling is silent - and the per-refresh bound is named when it is the smaller`() {
        every { dashboards.findAllListed() } returns listOf(ListedDashboard("acme", "acme/dashboards/pulse", 1, board(distinct = 16)))

        boot(slots).filter { it.level == Level.WARN }.shouldBeEmpty()

        every { dashboards.findAllListed() } returns listOf(ListedDashboard("acme", "acme/dashboards/pulse", 1, board(distinct = 9)))
        val named =
            capturingLogEvents(logger()) {
                AuthoringStartupCheck(
                    environment("true"),
                    pipelines,
                    templates,
                    parameterSets,
                    visualizations,
                    dashboards,
                    ExecutionSlots(maxPerUser = 4, maxPerInstance = 40),
                    DashboardRuntimeConfig(maxExecutionsPerRefresh = 8),
                ).check()
            }.single { it.level == Level.WARN }.formattedMessage

        named shouldContain "ceiling=8 key=${DashboardRuntimeKey.MAX_EXECUTIONS_PER_REFRESH.path}"
        named shouldContain "acme/acme/dashboards/pulse@1 (9)"
    }

    private fun boot(slots: ExecutionSlots): List<ILoggingEvent> =
        capturingLogEvents(logger()) { startupCheck(environment("true"), slots).check() }

    private fun logger(): Logger = LoggerFactory.getLogger(AuthoringStartupCheck::class.java) as Logger

    /** A board whose [distinct] executions are read by one visualization each, plus [shared] sources repeating the first. */
    private fun board(
        distinct: Int,
        shared: Int = 0,
    ): DashboardBody {
        val sources =
            (1..distinct).map { DashboardSource("s$it", ArtifactRef("acme/pipelines/p$it", 1)) } +
                (1..shared).map { DashboardSource("again$it", ArtifactRef("acme/pipelines/p1", 1)) }
        return DashboardBody(
            displayName = "board",
            sources = sources,
            visualizations =
                sources.map {
                    VisualizationOccurrence(
                        name = "v_${it.name}",
                        type = DashboardObjectType.VISUALIZATION,
                        visualization = ArtifactRef("acme/visualizations/chart", 1),
                        inputs = mapOf("rows" to InputMapping(it.name)),
                    )
                },
            layout = DashboardLayout(),
        )
    }
}
