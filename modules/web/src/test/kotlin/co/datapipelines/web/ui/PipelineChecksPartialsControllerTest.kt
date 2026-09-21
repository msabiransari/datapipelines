package co.datapipelines.web.ui

import co.datapipelines.application.checks.PipelineCheckRun
import co.datapipelines.application.checks.PipelineCheckRunRepository
import co.datapipelines.application.checks.PipelineCheckRunner
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.CheckExpectation
import co.datapipelines.pipeline.CheckRunOutcome
import co.datapipelines.pipeline.CheckRunVerdict
import co.datapipelines.pipeline.CheckRunVia
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

/**
 * The checks partials' HTTP contract (140, ui-screens §4.3b/§4.3d), through standalone MockMvc
 * in the [PipelineLifecycleDialogControllerTest] shape: the read-only GET renders the one
 * partial from definitions + latest runs; the run POST commissions the fresh run on the shared
 * runner — `via = ui`, the session principal, the request's correlation id — and renders the
 * SAME partial from the fresh outcomes, the release footer riding only when the dialog asked
 * for it. Not-found is the house 404's Shape C, and an API key is refused
 * `auth.session.required` on BOTH routes (the REST twins are the key surface, rest-api §5.16).
 */
class PipelineChecksPartialsControllerTest {
    private val pipelines = mockk<PipelineService>()
    private val checkRunner = mockk<PipelineCheckRunner>()
    private val checkRuns = mockk<PipelineCheckRunRepository>()

    private lateinit var mvc: MockMvc

    private fun authenticate(method: AuthMethod) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId = USER,
                    email = "probe@test",
                    displayName = "Probe",
                    scopes = setOf(Scope.EXECUTE),
                    authMethod = method,
                    workspace = WorkspaceContext(WORKSPACE, "probe"),
                ),
                null,
                emptyList(),
            )
        mvc =
            MockMvcBuilders
                .standaloneSetup(PipelineChecksPartialsController(pipelines, checkRunner, checkRuns, co.datapipelines.web.EVERYTHING_LENS))
                .setControllerAdvice(UiExceptionHandler())
                .build()
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `GET renders the partial from the version's definitions and their latest runs`() {
        authenticate(AuthMethod.OIDC)
        every { pipelines.findRecord(WORKSPACE, any(), PIPELINE) } returns record()
        every { pipelines.findExecutable(WORKSPACE, record(), 3) } returns executable()
        every { checkRuns.latestPerCheck(PIPELINE, 3) } returns
            listOf(
                runRow("share_matches", CheckRunVerdict.PASS),
                runRow("count_in_range", CheckRunVerdict.FAIL),
            )

        mvc
            .perform(get("/partials/pipelines/$PIPELINE/versions/3/checks").header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(view().name("partials/pipeline-checks"))
            .andExpect(model().attribute("hasChecks", true))
            .andExpect(model().attribute("allPass", false))
            .andExpect(model().attribute("anyFailing", true))
            .andExpect(model().attribute("failingIds", listOf("count_in_range")))
            .andExpect(model().attribute("releaseFooter", false))
            .andExpect(model().attribute("version", 3))
    }

    @Test
    fun `POST run commissions the fresh run - via ui, the session actor, the correlation id - and renders the same partial`() {
        authenticate(AuthMethod.OIDC)
        every { pipelines.findRecord(WORKSPACE, any(), PIPELINE) } returns record()
        every { pipelines.findExecutable(WORKSPACE, record(), 3) } returns executable()
        val via = slot<CheckRunVia>()
        val actor = slot<UUID>()
        val correlation = slot<String>()
        every {
            checkRunner.run(WORKSPACE, PIPELINE, 3, emptyMap(), capture(via), capture(actor), capture(correlation))
        } returns
            listOf(
                outcome("share_matches", CheckRunVerdict.PASS),
                outcome("count_in_range", CheckRunVerdict.PASS),
            )

        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/versions/3/checks/run")
                    .param("footer", "release")
                    .header("HX-Request", "true"),
            ).andExpect(status().isOk)
            .andExpect(view().name("partials/pipeline-checks"))
            .andExpect(model().attribute("allPass", true))
            .andExpect(model().attribute("anyFailing", false))
            .andExpect(model().attribute("releaseFooter", true))

        via.captured shouldBe CheckRunVia.UI
        actor.captured shouldBe USER
        // The MDC slot is empty under MockMvc, so the fallback minted one — some id, never none.
        correlation.captured.isNotBlank() shouldBe true
    }

    @Test
    fun `POST run without the footer parameter carries no release footer`() {
        authenticate(AuthMethod.OIDC)
        every { pipelines.findRecord(WORKSPACE, any(), PIPELINE) } returns record()
        every { pipelines.findExecutable(WORKSPACE, record(), 3) } returns executable()
        every { checkRunner.run(WORKSPACE, PIPELINE, 3, emptyMap(), any(), any(), any()) } returns
            listOf(outcome("share_matches", CheckRunVerdict.FAIL))

        mvc
            .perform(post("/partials/pipelines/$PIPELINE/versions/3/checks/run").header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("releaseFooter", false))
            .andExpect(model().attribute("anyFailing", true))
            .andExpect(model().attribute("failingIds", listOf("share_matches")))
    }

    @Test
    fun `POST run on an unknown pipeline is the house 404, Shape C`() {
        authenticate(AuthMethod.OIDC)
        every { checkRunner.run(WORKSPACE, PIPELINE, 3, emptyMap(), any(), any(), any()) } returns null

        val body =
            mvc
                .perform(post("/partials/pipelines/$PIPELINE/versions/3/checks/run").header("HX-Request", "true"))
                .andExpect(status().isNotFound)
                .andExpect(header().string("HX-Retarget", "#toast"))
                .andReturn()
                .response.contentAsString

        body shouldContain "pipeline.execution.not_found"
    }

    @Test
    fun `GET on an unknown pipeline is the house 404`() {
        authenticate(AuthMethod.OIDC)
        every { pipelines.findRecord(WORKSPACE, any(), PIPELINE) } returns null

        mvc
            .perform(get("/partials/pipelines/$PIPELINE/versions/3/checks").header("HX-Request", "true"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `an API key is refused auth session required on BOTH routes`() {
        authenticate(AuthMethod.API_KEY)
        val postBody =
            mvc
                .perform(post("/partials/pipelines/$PIPELINE/versions/3/checks/run").header("HX-Request", "true"))
                // The catalog's explicit row: auth.session.required is 403, not the family 401.
                .andExpect(status().isForbidden)
                .andReturn()
                .response.contentAsString
        postBody shouldContain "auth.session.required"

        mvc
            .perform(get("/partials/pipelines/$PIPELINE/versions/3/checks").header("HX-Request", "true"))
            .andExpect(status().isForbidden)
    }

    // ------------------------------------------------------------------ fixtures

    private fun record() =
        PipelineRecord(
            id = PIPELINE,
            name = "nyc/mobility/probe",
            displayName = "probe",
            description = "",
            ownerId = USER,
            currentVersion = 2,
            createdAt = T0,
            updatedAt = T0,
        )

    private fun executable(): PipelineService.ExecutablePipeline {
        val body =
            """{"name":"nyc/mobility/probe","display_name":"probe","description":"d",""" +
                """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fq",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}],""" +
                """"checks":[""" +
                """{"id":"share_matches","name":"Share matches","datasource":"h2-checks","sql":"SELECT 74.62 AS share",""" +
                """"expected":{"kind":"value","value":74.62,"tolerance":0.01}},""" +
                """{"id":"count_in_range","name":"Count in range","datasource":"h2-checks","sql":"SELECT 5 AS n",""" +
                """"expected":{"kind":"range","min":1,"max":10}}]}"""
        return PipelineService.ExecutablePipeline(record(), 3, body, PipelineDeserializer().readOrThrow(body))
    }

    private fun runRow(
        checkId: String,
        verdict: CheckRunVerdict,
    ) = PipelineCheckRun(
        id = UUID.randomUUID(),
        pipelineId = PIPELINE,
        version = 3,
        checkId = checkId,
        ranAt = T0,
        ranBy = USER,
        via = CheckRunVia.UI,
        parametersJson = "{}",
        observedJson = null,
        verdict = verdict,
        message = null,
        correlationId = null,
        durationMs = 4,
    )

    private fun outcome(
        checkId: String,
        verdict: CheckRunVerdict,
    ) = CheckRunOutcome(
        checkId = checkId,
        name = checkId,
        expected = CheckExpectation(kind = "value", value = 1.0),
        observed = if (verdict == CheckRunVerdict.ERROR) null else "1",
        verdict = verdict,
        message = null,
        ranAt = T0,
    )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val PIPELINE: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val T0: Instant = Instant.parse("2026-09-08T10:00:00Z")
    }
}
