package co.datapipelines.web.incoming

import co.datapipelines.application.dependencies.DashboardPipelineConsumers
import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.RegistryIncomingRows
import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.application.templates.TemplateUsage
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.mcp.McpArguments
import co.datapipelines.mcp.McpToolContext
import co.datapipelines.mcp.TemplatesUsedByTool
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionConsumers
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateService
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.anonymousActors
import co.datapipelines.web.pipelineBrowseModelOver
import co.datapipelines.web.release.ReleaseConfiguration
import co.datapipelines.web.ui.TemplateWorkspaceModel
import co.datapipelines.web.ui.UsageView
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * #877 §C — every existing read caller receives the COMPLETE answer through its adapter over `findIncoming`. The fixture
 * holds more references than one inverse page (256): 390 working-version node pins of the template (three per pipeline
 * version — several from one dependent version) and 260 dashboard-source pins of the child pipeline (two per dashboard
 * version), beside drafts, a discarded version of a live entity, sets and visualizations. Three read surfaces render
 * their whole answer — `templates_used_by` (the MCP tool's payload), the template Used-by view (the versions' in-use
 * labels and the Used-by facts) and the pipeline Usage view (parents and dashboards) — under three views: everything, a
 * MIXED view (half the pinners outside it) and a HIDDEN-ONLY view (every pinner outside it).
 *
 * The expectations are the PRE-ADAPTATION answers: rendered at base `32f6e3356` (the domain scans behind the
 * `TemplateUsage` / `DashboardPipelineConsumers` constructors of that commit) with this seed and these renderers, and
 * committed under `incoming-complete-answers/` — the capture is in the lane's evidence. Same ordered references, same
 * fields, same visible counts, same RELEASED-only behavior under a narrowing view, and no new key: any difference is red.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncomingCompleteAnswerTest {
    private val dataSource = SharedPostgres.dataSource()
    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val seed = CompleteAnswerSeed(jdbc)
    private val pipelines = PipelineRepository(jdbc)
    private val templates = TemplateRepository(jdbc)
    private val wiring = wiring()

    /** The three views each surface renders under. */
    enum class View(
        val lensed: LensedView,
    ) {
        EVERYTHING(LensedView.EVERYTHING),
        MIXED(
            LensedView(
                pipelines = ReadLens.Only(names("acme/p/p", PIPELINES, even = true) + CHILD),
                templates = ReadLens.Everything,
                parameterSets = ReadLens.Only(names("acme/s/s", SETS, even = true)),
                visualizations = ReadLens.Only(names("acme/v/v", VISUALIZATIONS, even = true)),
                dashboards = ReadLens.Only(names("acme/d/d", PIPELINES, even = true)),
            ),
        ),
        HIDDEN_ONLY(
            LensedView(
                pipelines = ReadLens.Only(setOf(CHILD)),
                templates = ReadLens.Everything,
                parameterSets = ReadLens.Only(emptySet()),
                visualizations = ReadLens.Only(emptySet()),
                dashboards = ReadLens.Only(emptySet()),
            ),
        ),
    }

    @BeforeAll
    fun fixture() {
        seed.reset()
        seed.template(LIBRARY, 1, RELEASED)
        seed.template(LIBRARY, 2, RELEASED)
        seed.pipeline(CHILD, 1, RELEASED)
        seed.pipeline(CHILD, 2, RELEASED)
        repeat(PIPELINES) { i ->
            val name = "acme/p/p%03d".format(i)
            seed.pipeline(name, 1, RELEASED, templates = List(3) { LIBRARY to 1 }, children = listOf(CHILD to 1))
            if (i < 10) seed.pipeline(name, 2, DRAFT, templates = listOf(LIBRARY to 2), children = listOf(CHILD to 2, CHILD to 2))
            if (i in 10..14) seed.pipeline(name, 2, DISCARDED, templates = listOf(LIBRARY to 1), children = listOf(CHILD to 1))
            seed.dashboard("acme/d/d%03d".format(i), 1, RELEASED, pipelines = listOf(CHILD to 1, CHILD to 1))
            if (i < 10) seed.dashboard("acme/d/d%03d".format(i), 2, DRAFT, pipelines = listOf(CHILD to 2))
        }
        repeat(SETS) { i ->
            seed.parameterSet("acme/s/s%02d".format(i), 1, RELEASED, selectors = listOf(LIBRARY to 1, LIBRARY to 1))
            if (i < 5) seed.parameterSet("acme/s/s%02d".format(i), 2, DRAFT, selectors = listOf(LIBRARY to 1))
        }
        repeat(VISUALIZATIONS) { i ->
            seed.visualization("acme/v/v%02d".format(i), 1, RELEASED, transform = LIBRARY to 1)
            if (i < 3) seed.visualization("acme/v/v%02d".format(i), 2, DRAFT, transform = LIBRARY to 2)
        }
    }

    @Test
    fun `the fixture holds more references than one inverse page - the complete answer is not one page`() {
        val usedBy = wiring.usage.usedBy(seed.workspaceId, LensedView.EVERYTHING, LIBRARY, 1)
        usedBy.pipelineReferences.size shouldBeGreaterThan PAGE
        val dashboards = (1..2).flatMap { wiring.dashboards.liveVersionPins(seed.workspaceId, CHILD, it) }
        dashboards.size shouldBeGreaterThan PAGE / 2 // one pin per dashboard VERSION from 260 source rows
    }

    @Test
    fun `templates_used_by answers what the pre-adaptation code answered - under every view`() {
        View.entries.forEach { view -> withClue(view) { usedByTool(view) shouldBe expected("templates_used_by", view) } }
    }

    @Test
    fun `the template Used-by view answers what the pre-adaptation code answered - under every view`() {
        View.entries.forEach { view -> withClue(view) { templateView(view) shouldBe expected("template_used_by_view", view) } }
    }

    @Test
    fun `the pipeline Usage view answers what the pre-adaptation code answered - under every view`() {
        View.entries.forEach { view -> withClue(view) { pipelineUsage(view) shouldBe expected("pipeline_usage_view", view) } }
    }

    /** The `TemplateUsage` and the dashboard port the surfaces read, built as the production beans build them. */
    private class Wiring(
        val usage: TemplateUsage,
        val dashboards: PipelineVersionConsumers,
    )

    private fun wiring(): Wiring {
        val config = ReleaseConfiguration()
        val ceiling = PostgresStatementCeiling(dataSource)
        val incoming =
            RegistryIncomingRows(FamilyRegistry(listOf(config.pipelineDependencyFamily(ceiling), config.templateDependencyFamily(ceiling))))
        return Wiring(TemplateUsage(incoming, TemplateUsageService(templates, pipelines)), DashboardPipelineConsumers(incoming))
    }

    private fun usedByTool(view: View): String {
        val payload =
            TemplatesUsedByTool(wiring.usage, PromoterLens { view.lensed })
                .call(McpArguments(mapOf("id" to LIBRARY, "version" to 1)), McpToolContext(principal(), UUID(0, 1), null))
        return JSON.writeValueAsString(payload) + "\n"
    }

    private fun templateView(view: View): String {
        val resolved =
            TemplateWorkspaceModel(TemplateService(templates), wiring.usage, anonymousActors())
                .resolve(seed.workspaceId, view.lensed, LIBRARY, null)
        val used = resolved.usedBy
        return buildString {
            resolved.versions.forEach { appendLine("version ${it.version} ${it.status}: ${it.usageLabel}") }
            appendLine("summary: ${used.summary}")
            appendLine("pipelineCount: ${used.pipelineCount}")
            used.pipelines.forEach { appendLine(it) }
            used.sets.forEach { appendLine(it) }
            used.visualizations.forEach { appendLine(it) }
        }
    }

    private fun pipelineUsage(view: View): String {
        val model = ExtendedModelMap()
        pipelineBrowseModelOver(
            pipelines,
            endpoints = mockk { every { findByPipeline(any()) } returns emptyList() },
            schedules = mockk { every { listByTarget(any(), any(), any()) } returns emptyList() },
            dashboards = wiring.dashboards,
        ).fillUsage(model, seed.workspaceId, view.lensed, CompleteAnswerSeed.idOf("pipeline", CHILD), principal())
        val usage = model["usage"] as UsageView
        return buildString {
            appendLine("total: ${usage.total}")
            usage.parents.forEach { appendLine(it) }
            usage.dashboards.forEach { appendLine(it) }
            usage.endpoints.forEach { appendLine(it) }
            usage.schedules.forEach { appendLine(it) }
        }
    }

    private fun expected(
        surface: String,
        view: View,
    ): String {
        val path = "/incoming-complete-answers/$surface-${view.name.lowercase()}.txt"
        return checkNotNull(javaClass.getResource(path)) { "no captured answer at $path" }.readText()
    }

    private fun principal() =
        AuthenticatedPrincipal(
            userId = seed.userId,
            email = "seed@example.com",
            displayName = "Seed",
            authMethod = AuthMethod.API_KEY,
            keyId = "dpk_ABCDEFGHIJKL",
            workspace = WorkspaceContext(seed.workspaceId, "w_seed", WorkspaceRole.AUTHOR),
        )

    private companion object {
        const val LIBRARY = "acme/t/lib.sql"
        const val CHILD = "acme/p/child"
        const val PIPELINES = 130
        const val SETS = 20
        const val VISUALIZATIONS = 20
        const val PAGE = 256
        val JSON = jacksonObjectMapper().writerWithDefaultPrettyPrinter()

        fun names(
            prefix: String,
            count: Int,
            even: Boolean,
        ): Set<String> = (0 until count).filter { (it % 2 == 0) == even }.map { "$prefix%0${if (count > 99) 3 else 2}d".format(it) }.toSet()
    }
}
