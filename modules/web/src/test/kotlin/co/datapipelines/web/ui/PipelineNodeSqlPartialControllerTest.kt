package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateRenderException
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * The node-SQL partial (pipeline-editor.md §8): SQL is not stored in pipeline nodes —
 * contract §2.3 puts it in template entities — so the controller resolves the node's
 * PINNED {id, version} and renders it against the pipeline's own parameter context.
 * The states are the contract: bound, sampled (required parameters unsupplied → the
 * §12.6 dry-render sample context, labelled), rejected (a supplied override failed
 * §6.3 coercion — named, never rendered), child-pipeline, template-missing,
 * render-failed, node-missing.
 */
class PipelineNodeSqlPartialControllerTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templateEngines = mockk<WorkspaceTemplateEngines>()
    private val templates = mockk<TemplateRepository>()
    private val engine = mockk<TemplateEngine>()
    private val controller =
        PipelineNodeSqlPartialController(
            pipelines,
            templateEngines,
            templates,
            co.datapipelines.web.pipelineServiceOver(pipelines),
            co.datapipelines.web.EVERYTHING_LENS,
        )

    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val bodyJson =
        """
        {
          "schema_version": 1,
          "name": "fixture",
          "display_name": "Fixture",
          "description": "",
          "settings": {"tempdb": {"engine": "H2"}},
          "parameters": {
            "start_date": {"type": "DATE", "required": true},
            "limit": {"type": "INTEGER", "required": false, "default": 100}
          },
          "nodes": [
            {
              "id": "trips_by_day",
              "type": "DQL",
              "source": "sample-trips",
              "template": {"id": "test/trips_by_day.sql", "version": 1},
              "output": {"target": "tempdb", "table": "day_counts"},
              "depends_on": []
            },
            {
              "id": "top_days",
              "type": "DQL",
              "source": "tempdb",
              "template": {"id": "test/top_days.sql", "version": 2},
              "depends_on": ["trips_by_day"]
            },
            {
              "id": "run_child",
              "type": "PIPELINE",
              "pipeline": {"name": "child_pipe", "version": 3},
              "depends_on": []
            }
          ]
        }
        """.trimIndent()

    @BeforeEach
    fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "a@b.c",
                "A",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
        // No draft: the panel's E5 default (draft-if-exists) falls to the released current
        // version, which is what every state test below exercises.
        // 178: the lens gate resolves the index row before the resolver runs.
        every { pipelines.findById(any(), pipelineId) } returns record()
        every { pipelines.findDraftDetail(any(), pipelineId) } returns null
        every { pipelines.findCurrentVersionDetail(any(), pipelineId) } returns versionDetail(1)
        every { pipelines.findVersionBody(any(), pipelineId, 1) } returns bodyJson
        every { templateEngines.engineFor(workspaceId) } returns engine
        every { templates.lookupVersion(workspaceId, "test/trips_by_day.sql", 1) } returns version("test/trips_by_day.sql", 1)
        every { templates.lookupVersion(workspaceId, "test/top_days.sql", 2) } returns version("test/top_days.sql", 2)
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a fully supplied context renders bound, with no sampled parameters`() {
        every { engine.render(TemplateRef("test/trips_by_day.sql", 1), any(), any()) } returns "SELECT 1"
        val model = ExtendedModelMap()

        controller.nodeSql(pipelineId, "trips_by_day", """{"start_date":"2023-01-01"}""", model)

        model.getAttribute("state") shouldBe "rendered"
        model.getAttribute("sql") shouldBe "SELECT 1"
        model.getAttribute("dialect") shouldBe "POSTGRES"
        model.getAttribute("templateId") shouldBe "test/trips_by_day.sql"
        model.getAttribute("templateVersion") shouldBe 1
        model.getAttribute("sampledParameters") shouldBe emptyList<String>()
    }

    @Test
    fun `the pinned version is rendered, never the latest`() {
        // The node pins trips_by_day.sql@1 while the registry also holds @2.
        every { engine.render(TemplateRef("test/trips_by_day.sql", 1), any(), any()) } returns "SELECT v1"
        every { templates.lookupVersion(workspaceId, "test/trips_by_day.sql", 2) } returns version("test/trips_by_day.sql", 2)
        val model = ExtendedModelMap()

        controller.nodeSql(pipelineId, "trips_by_day", null, model)

        verify { engine.render(TemplateRef("test/trips_by_day.sql", 1), any(), any()) }
        verify(exactly = 0) { engine.render(TemplateRef("test/trips_by_day.sql", 2), any(), any()) }
    }

    @Test
    fun `a wire-form override reaches the binder and a malformed one is reported, not rendered`() {
        // §6.3: INTEGER is a NUMBER on the wire; a string is rejected, never converted.
        val model = ExtendedModelMap()

        controller.nodeSql(pipelineId, "top_days", """{"limit":"5"}""", model)

        model.getAttribute("state") shouldBe "parameter-rejected"
        (model.getAttribute("failures") as List<*>).toString() shouldContain "limit"
        verify(exactly = 0) { engine.render(any(), any(), any()) }
    }

    @Test
    fun `an unparseable parameters document is rejected, never a 500`() {
        val model = ExtendedModelMap()

        controller.nodeSql(pipelineId, "top_days", "not-json", model)

        model.getAttribute("state") shouldBe "parameter-rejected"
        verify(exactly = 0) { engine.render(any(), any(), any()) }
    }

    @Test
    fun `an unsupplied REQUIRED parameter falls back to sample values and says so`() {
        // ParameterBinder.bind rejects; sampleContext() is the documented dry-render context.
        every { engine.render(TemplateRef("test/trips_by_day.sql", 1), any(), any()) } returns "SELECT sampled"
        val model = ExtendedModelMap()

        controller.nodeSql(pipelineId, "trips_by_day", null, model)

        model.getAttribute("state") shouldBe "rendered"
        model.getAttribute("sampledParameters") shouldBe listOf("start_date")
    }

    @Test
    fun `a PIPELINE node has no template by contract and renders the child-pipeline state`() {
        // Node.fromJson gives a PIPELINE node TemplateRef("", 0) — NOT null.
        val model = ExtendedModelMap()

        controller.nodeSql(pipelineId, "run_child", null, model)

        model.getAttribute("state") shouldBe "child-pipeline"
        model.getAttribute("childName") shouldBe "child_pipe"
        model.getAttribute("childVersion") shouldBe 3
        verify(exactly = 0) { engine.render(any(), any(), any()) }
    }

    @Test
    fun `a pinned template absent from the registry renders the template-missing state`() {
        every { templates.lookupVersion(workspaceId, "test/trips_by_day.sql", 1) } returns null
        val model = ExtendedModelMap()

        controller.nodeSql(pipelineId, "trips_by_day", null, model)

        model.getAttribute("state") shouldBe "template-missing"
        model.getAttribute("templateId") shouldBe "test/trips_by_day.sql"
        model.getAttribute("templateVersion") shouldBe 1
        verify(exactly = 0) { engine.render(any(), any(), any()) }
    }

    @Test
    fun `a render failure renders the render-failed state with the engine's message`() {
        every {
            engine.render(TemplateRef("test/trips_by_day.sql", 1), any(), any())
        } throws TemplateRenderException("undefined variable: nope", TemplateRef("test/trips_by_day.sql", 1))
        val model = ExtendedModelMap()

        controller.nodeSql(pipelineId, "trips_by_day", null, model)

        model.getAttribute("state") shouldBe "render-failed"
        (model.getAttribute("message") as String) shouldContain "undefined variable"
    }

    @Test
    fun `an unknown node id renders the node-missing state`() {
        val model = ExtendedModelMap()

        controller.nodeSql(pipelineId, "no_such_node", null, model)

        model.getAttribute("state") shouldBe "node-missing"
        model.getAttribute("nodeId") shouldBe "no_such_node"
    }

    @Test
    fun `an unknown pipeline id is the house 404, never the 500 (184)`() {
        every { pipelines.findById(any(), pipelineId) } returns null
        val mvc = mvcFor(controller)

        // The non-htmx shape: the shared not-found page, status carried.
        mvc
            .perform(get("/partials/pipelines/$pipelineId/nodes/trips_by_day/sql"))
            .andExpect(status().isNotFound)
            .andExpect(view().name("error/404"))

        // The htmx shape: §5.1 Shape C, the code — and never the exception's message.
        val body =
            mvc
                .perform(get("/partials/pipelines/$pipelineId/nodes/trips_by_day/sql").header("HX-Request", "true"))
                .andExpect(status().isNotFound)
                .andExpect(header().string("HX-Retarget", "#toast"))
                .andReturn()
                .response.contentAsString
        body shouldContain PipelineErrorCodes.Execution.NOT_FOUND
        body shouldNotContain pipelineId.toString()
    }

    @Test
    fun `a pipeline the promoter lens hides is the SAME 404 as an absent one (178, 184)`() {
        val lensed =
            PipelineNodeSqlPartialController(
                pipelines,
                templateEngines,
                templates,
                co.datapipelines.web.pipelineServiceOver(pipelines),
                co.datapipelines.application.lens.PromoterLens {
                    co.datapipelines.application.lens.LensedView(
                        ReadLens.NOTHING,
                        ReadLens.NOTHING,
                    )
                },
            )
        val absentId = UUID.randomUUID()
        every { pipelines.findById(any(), absentId) } returns null
        val mvc = mvcFor(lensed)

        fun probe(id: UUID): String =
            mvc
                .perform(get("/partials/pipelines/$id/nodes/trips_by_day/sql").header("HX-Request", "true"))
                .andExpect(status().isNotFound)
                .andReturn()
                .response.contentAsString

        // findById still answers for the hidden id — the LENS is what hides it — and the two
        // answers are identical once the per-request correlation id is stripped.
        withoutCorrelation(probe(pipelineId)) shouldBe withoutCorrelation(probe(absentId))
    }

    @Test
    fun `a draft-only pipeline under a narrowing lens is the same 404 - the resolver's not-found is mapped too (184)`() {
        // The lens ADMITS the pipeline's name, so findRecord passes; the resolver then finds no
        // RELEASED version (a draft-only pipeline's current_version is NULL, and the narrowing
        // lens never reads the draft branch) and throws — the controller's mapping, not the
        // service's, is what answers 404 here.
        val narrowing =
            PipelineNodeSqlPartialController(
                pipelines,
                templateEngines,
                templates,
                co.datapipelines.web.pipelineServiceOver(pipelines),
                co.datapipelines.application.lens.PromoterLens {
                    co.datapipelines.application.lens.LensedView(
                        ReadLens.Only(setOf("test/nyc")),
                        ReadLens.Only(setOf("test/nyc")),
                    )
                },
            )
        every { pipelines.findCurrentVersionDetail(any(), pipelineId) } returns null

        mvcFor(narrowing)
            .perform(get("/partials/pipelines/$pipelineId/nodes/trips_by_day/sql").header("HX-Request", "true"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `the E5 default - when a DRAFT exists the panel renders the DRAFT body`() {
        // 037 E5: the resolver's version rule (draft-if-exists) is THE panel's rule too — the
        // editor authors the draft (035 D4), so showing the released body under it would have
        // a human debug stale SQL. v2's body renames the node, so the pinned template's render
        // proves which body the panel read.
        val draftBody = bodyJson.replace("\"id\": \"trips_by_day\"", "\"id\": \"trips_draft\"")
        every { pipelines.findDraftDetail(any(), pipelineId) } returns versionDetail(2)
        every { pipelines.findVersionBody(any(), pipelineId, 2) } returns draftBody
        val model = ExtendedModelMap()

        controller.nodeSql(pipelineId, "trips_by_day", null, model)

        // v2's body has no trips_by_day node — the draft was read, hence node-missing (not a
        // v1 render), and a version-1 body lookup never happened.
        model.getAttribute("state") shouldBe "node-missing"
        io.mockk.verify(exactly = 0) { pipelines.findVersionBody(any(), pipelineId, 1) }
    }

    /** The HTTP-level harness, [PipelineChecksPartialsControllerTest]'s shape: the advice is the mapping under test. */
    private fun mvcFor(controller: PipelineNodeSqlPartialController): MockMvc =
        MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(UiExceptionHandler())
            .build()

    private fun withoutCorrelation(body: String): String = body.replace(CORRELATION_ID, "")

    private fun record() =
        co.datapipelines.pipeline.PipelineRecord(
            id = pipelineId,
            name = "test/nyc",
            displayName = "NYC",
            description = "",
            ownerId = UUID.randomUUID(),
            currentVersion = 1,
            createdAt = java.time.Instant.EPOCH,
            updatedAt = java.time.Instant.EPOCH,
        )

    private fun versionDetail(v: Int) =
        co.datapipelines.pipeline.PipelineVersionDetail(
            pipelineId = pipelineId,
            version = v,
            status = co.datapipelines.pipeline.PipelineVersionStatus.RELEASED,
            bodyHash = "hash-$v",
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = UUID.randomUUID(),
            releasedAt = Instant.parse("2026-08-01T00:00:00Z"),
        )

    private fun version(
        id: String,
        v: Int,
    ) = TemplateVersion(
        id = id,
        version = v,
        dialect = Dialect.POSTGRES,
        isLibrary = false,
        imports = emptyList(),
        body = "SELECT 1",
        createdAt = Instant.parse("2026-08-01T00:00:00Z"),
        createdBy = UUID.randomUUID(),
    )

    private companion object {
        /** `PromoterLensSweepTest`'s fingerprint rule: the per-request correlation id is not content. */
        val CORRELATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
