package co.datapipelines.web.visualizations

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactFolder
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactValidationException
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.PointerMove
import co.datapipelines.visualization.Purged
import co.datapipelines.visualization.Switched
import co.datapipelines.visualization.VersionMoved
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationReleased
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.api.ApiErrorCatalog
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The visualization controller over a mocked service and the REAL reader (the `ParameterSetsControllerTest`
 * shape): the envelope around each §13.22 refusal, the #291 body read (a malformed body is the family's 400, never
 * the service's business), the reader's refusal before the service, the hash precondition's placement, the
 * session-only verbs, the switch body judged before the lookup, and — the O3 rule — every read handed the
 * CALLER's lens, with a promoter's hidden visualization the family's 404 and no draft pointer under a narrowing view.
 */
class VisualizationsControllerTest {
    private val service = mockk<VisualizationService>()
    private val reader = VisualizationReader()
    private val audit = RecordingAudit()
    private val controller = VisualizationsController(service, reader, co.datapipelines.web.EVERYTHING_LENS, audit)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val id = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    // ---- create / update ------------------------------------------------------------------------------

    @Test
    fun `create reads the document with the reader and lands version 1 DRAFT through the service`() {
        authenticate()
        val document = slot<co.datapipelines.visualization.VisualizationDocument>()
        every { service.create(workspaceId, capture(document), userId, WriteSurface.SESSION) } returns draft()

        val response = controller.create(DOCUMENT)

        assertAll(
            { document.captured.name shouldBe NAME },
            { response.data.get("id").asText() shouldBe id.toString() },
            { response.data.get("status").asText() shouldBe "DRAFT" },
            {
                response.data
                    .get("draft")
                    .get("body_hash")
                    .asText() shouldBe "hash-v1"
            },
            {
                response.data
                    .get("renderer")
                    .get("kind")
                    .asText() shouldBe "plotly"
            },
        )
    }

    @Test
    fun `a malformed body is the family's 400 - malformed_json - and the service is never asked`() {
        authenticate()
        val error = shouldThrow<ApiException> { controller.create("""{"name": "acme/v/x", """) }

        assertAll(
            { error.code shouldBe VisualizationErrorCodes.BODY_INVALID },
            { error.details[ApiErrors.REASON] shouldBe ApiErrors.MALFORMED_JSON },
            { ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.BAD_REQUEST },
        )
        verify(exactly = 0) { service.create(any(), any(), any(), any()) }
    }

    @Test
    fun `a body the reader refuses is its 400 with every failure - before the service`() {
        authenticate()
        val body = ArtifactJson.mapper.readTree(DOCUMENT) as com.fasterxml.jackson.databind.node.ObjectNode
        body.put("colour", "red")

        val error = shouldThrow<ArtifactValidationException> { controller.create(body.toString()) }

        error.code shouldBe VisualizationErrorCodes.BODY_INVALID
        error.result.failures
            .single()
            .details["reason"] shouldBe "unknown_key"
        verify(exactly = 0) { service.create(any(), any(), any(), any()) }
    }

    @Test
    fun `update requires If-Match BEFORE the body is parsed - a malformed body never masks the missing header`() {
        authenticate()
        val error = shouldThrow<ApiException> { controller.update(id, null, "not json") }

        error.details[ApiErrors.REASON] shouldBe "precondition_missing"
        verify(exactly = 0) { service.write(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `update writes the draft at the If-Match hash`() {
        authenticate()
        every { service.write(workspaceId, id, any(), "hash-v0", userId, WriteSurface.SESSION) } returns draft()

        controller
            .update(id, "hash-v0", DOCUMENT)
            .data
            .get("version")
            .asInt() shouldBe 1
    }

    // ---- reads: the caller's lens, never Everything -----------------------------------------------------

    @Test
    fun `get answers the working version with the draft pointer under the whole view`() {
        authenticate()
        every { service.findWorking(workspaceId, ReadLens.Everything, id) } returns draft()

        val data = controller.get(id).data

        data.get("draft").get("version").asInt() shouldBe 1
    }

    @Test
    fun `a promoter's hidden visualization is the family's 404 - the service was asked with HER lens`() {
        authenticate()
        val narrowing = ReadLens.Only(setOf("acme/visualizations/other"))
        every { service.findWorking(workspaceId, narrowing, id) } returns null

        val error = shouldThrow<ApiException> { narrowed(narrowing).get(id) }

        error.code shouldBe VisualizationErrorCodes.NOT_FOUND
        ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.NOT_FOUND
        verify(exactly = 0) { service.findWorking(any(), ReadLens.Everything, any()) }
    }

    @Test
    fun `under a narrowing lens a visible visualization carries no draft pointer`() {
        authenticate()
        val narrowing = ReadLens.Only(setOf(NAME))
        every { service.findWorking(workspaceId, narrowing, id) } returns released()

        narrowed(narrowing).get(id).data.has("draft") shouldBe false
    }

    @Test
    fun `one version by number - a miss is the 404 naming the version`() {
        authenticate()
        every { service.findVersion(workspaceId, ReadLens.Everything, id, 2) } returns null

        val error = shouldThrow<ApiException> { controller.getVersion(id, 2) }

        error.code shouldBe VisualizationErrorCodes.NOT_FOUND
        error.details["version"] shouldBe 2
    }

    @Test
    fun `the versions listing - metadata only, and an artifact with none visible is the 404`() {
        authenticate()
        every { service.listVersions(workspaceId, ReadLens.Everything, id) } returns listOf(detail(1, PipelineVersionStatus.DRAFT))
        controller.versions(id).data.single()["status"] shouldBe "DRAFT"

        every { service.listVersions(workspaceId, ReadLens.Everything, id) } returns emptyList()
        shouldThrow<ApiException> { controller.versions(id) }.code shouldBe VisualizationErrorCodes.NOT_FOUND
    }

    @Test
    fun `browse answers the level with a lens-true total and has_more`() {
        authenticate()
        every { service.listChildFolders(workspaceId, ReadLens.Everything, "acme") } returns
            listOf(ArtifactFolder("acme/visualizations", "visualizations", 3))
        every { service.listChildren(workspaceId, ReadLens.Everything, "acme", 0, 1) } returns listOf(released())
        every { service.countChildren(workspaceId, ReadLens.Everything, "acme") } returns 2

        val data = controller.browse("acme", 0, 1).data

        assertAll(
            {
                (data["folders"] as List<*>).single() shouldBe
                    mapOf("path" to "acme/visualizations", "segment" to "visualizations", "visualization_count" to 3)
            },
            { (data["visualizations"] as List<*>).size shouldBe 1 },
            { data["total"] shouldBe 2 },
            { data["has_more"] shouldBe true },
        )
    }

    @Test
    fun `the flat listing pages every visualization the lens admits, with its total`() {
        authenticate()
        every { service.listAll(workspaceId, ReadLens.Everything, 0, 50) } returns listOf(released())
        every { service.countAll(workspaceId, ReadLens.Everything) } returns 1

        val paged = controller.list(null, null).data

        paged.items.single()["name"] shouldBe NAME
        paged.pagination.total shouldBe 1L
        paged.pagination.hasMore shouldBe false
    }

    // ---- lifecycle verbs ------------------------------------------------------------------------------

    @Test
    fun `release passes the consent flag and names the templates it cascaded`() {
        authenticate()
        every { service.release(workspaceId, id, "hash-v1", userId, releasePinnedTemplates = true) } returns
            VisualizationReleased(released(), listOf(TemplateRef("finance/transforms/revenue_bars", 2)))

        val data = controller.release(id, "hash-v1", releasePinnedTemplates = true).data

        data.get("status").asText() shouldBe "RELEASED"
        data
            .get("templates_released")
            .single()
            .get("template_id")
            .asText() shouldBe "finance/transforms/revenue_bars"
        // #332 — the release audit, the auditRelease twin: the cascaded template's event first,
        // naming the visualization release it rode on, then the visualization's own naming it.
        audit.events shouldBe listOf("template.version.released", "visualization.version.released")
        audit.details.first()["template_id"] shouldBe "finance/transforms/revenue_bars"
        audit.details.first()["version"] shouldBe 2
        audit.details.first()["cascade_from_visualization_id"] shouldBe id.toString()
        audit.details.first()["cascade_from_version"] shouldBe 1
        audit.details.last()["visualization_id"] shouldBe id.toString()
        audit.details.last()["visualization_name"] shouldBe NAME
        audit.details.last()["version"] shouldBe 1
        audit.details.last()["templates_released"] shouldBe
            listOf(mapOf("template_id" to "finance/transforms/revenue_bars", "version" to 2))
    }

    @Test
    fun `release's refusal surfaces unchanged - the evidence gate's tests_missing until L4`() {
        authenticate()
        every { service.release(workspaceId, id, "hash-v1", userId, releasePinnedTemplates = false) } throws
            DatapipelinesException(VisualizationErrorCodes.RELEASE_TESTS_MISSING, "no evidence", mapOf("reason" to "gate_not_installed"))

        val error = shouldThrow<DatapipelinesException> { controller.release(id, "hash-v1") }

        error.code shouldBe VisualizationErrorCodes.RELEASE_TESTS_MISSING
        ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.CONFLICT
    }

    @Test
    fun `the human verbs refuse an API key - session only - before the service`() {
        authenticate(AuthMethod.API_KEY)
        val refusals =
            listOf(
                { controller.purgeDraft(id, "hash-v1") },
                { controller.discardVersion(id, 1) },
                { controller.restoreVersion(id, 1) },
                { controller.purgeVersion(id, 1) },
                { controller.switchCurrent(id, """{"version": 1}""") },
                { controller.delete(id) },
            ).map { verb -> shouldThrow<DatapipelinesException> { verb() }.code }

        refusals.toSet() shouldBe setOf(PipelineErrorCodes.Auth.SESSION_REQUIRED)
        verify(exactly = 0) { service.purgeDraft(any(), any(), any()) }
        verify(exactly = 0) { service.purgeEntity(any(), any()) }
    }

    @Test
    fun `a session reaches every human verb through the service`() {
        authenticate()
        // #372 — the audit rows' pre-reads are BODY-FREE and ride the caller's lens (Everything for this principal).
        every { service.auditIdentity(workspaceId, ReadLens.Everything, id) } returns (NAME to 1)
        every { service.auditVersionIdentity(workspaceId, ReadLens.Everything, id, 1) } returns (NAME to 1)
        every { service.auditVersionIdentity(workspaceId, ReadLens.Everything, id, 2) } returns (NAME to 2)
        every { service.purgeDraft(workspaceId, id, "hash-v1") } returns Purged.Version
        every { service.discardVersion(workspaceId, id, 1, userId) } returns
            VersionMoved(detail(1, PipelineVersionStatus.DISCARDED), PointerMove(before = 1, after = null))
        every { service.restoreVersion(workspaceId, id, 1) } returns
            VersionMoved(detail(1, PipelineVersionStatus.RELEASED), PointerMove(before = null, after = 1))
        every { service.purgeVersion(workspaceId, id, 2) } returns Purged.Version
        every { service.purgeEntity(workspaceId, id) } returns Purged.Entity
        every { service.switchCurrent(workspaceId, id, 1) } returns Switched(NAME, PointerMove(before = 2, after = 1))

        controller.purgeDraft(id, "hash-v1")
        controller.discardVersion(id, 1).data["status"] shouldBe "DISCARDED"
        controller.restoreVersion(id, 1).data["status"] shouldBe "RELEASED"
        controller.purgeVersion(id, 2)
        controller.delete(id)
        controller.switchCurrent(id, """{"version": 1}""").data["current_version"] shouldBe 1

        verify(exactly = 1) { service.purgeEntity(workspaceId, id) }
        // #332 — every verb audited, in the order they ran, the pipelines mould's event per verb.
        audit.events shouldBe
            listOf(
                "visualization.version.purged",
                "visualization.version.discarded",
                "visualization.version.restored",
                "visualization.version.purged",
                "visualization.purged",
                "visualization.current_switched",
            )
        // #372 — the rows carry the pipelines mould's full shape; the exact key SET of each row is pinned,
        // so a missing key cannot pass, and the values come from the verb's result, not a re-read.
        audit.details.map { it["visualization_name"] } shouldBe listOf(NAME, NAME, NAME, NAME, NAME, NAME)
        audit.details.map { it["version"] } shouldBe listOf(1, 1, 1, 2, 1, null)
        audit.details.map { it["scope"] } shouldBe listOf("version", null, null, "version", "entity", null)
        audit.details.map { it["current_version_before"] } shouldBe listOf(null, 1, null, null, null, null)
        audit.details.map { it["current_version_after"] } shouldBe listOf(null, null, 1, null, null, null)
        audit.details.map { it["from"] } shouldBe listOf(null, null, null, null, null, 2)
        audit.details.map { it["to"] } shouldBe listOf(null, null, null, null, null, 1)
        val withPointer =
            setOf("visualization_id", "visualization_name", "version", "workspace_id", "current_version_before", "current_version_after")
        audit.details.map { it.keys } shouldBe
            listOf(
                setOf("visualization_id", "visualization_name", "version", "workspace_id", "scope"),
                withPointer,
                withPointer,
                setOf("visualization_id", "visualization_name", "version", "workspace_id", "scope"),
                setOf("visualization_id", "visualization_name", "version", "workspace_id", "scope"),
                setOf("visualization_id", "visualization_name", "workspace_id", "from", "to"),
            )
    }

    @Test
    fun `the switch body is judged before the lookup - missing and wrong-typed version are the family's 400`() {
        authenticate()
        val missing = shouldThrow<ApiException> { controller.switchCurrent(id, "{}") }
        val wrong = shouldThrow<ApiException> { controller.switchCurrent(id, """{"version": "2"}""") }

        assertAll(
            { missing.code shouldBe VisualizationErrorCodes.BODY_INVALID },
            { missing.details["reason"] shouldBe ApiErrors.REASON_MISSING },
            { wrong.details["reason"] shouldBe ApiErrors.REASON_WRONG_TYPE },
        )
        verify(exactly = 0) { service.switchCurrent(any(), any(), any()) }
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    private fun narrowed(lens: ReadLens) =
        VisualizationsController(
            service,
            reader,
            PromoterLens { LensedView(ReadLens.NOTHING, ReadLens.NOTHING, visualizations = lens) },
            audit,
        )

    private fun authenticate(method: AuthMethod = AuthMethod.OIDC) {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                method,
                keyId = if (method == AuthMethod.API_KEY) "dpk_test" else null,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun body(): VisualizationBody = reader.readOrThrow(ArtifactJson.mapper.readTree(DOCUMENT)).body

    private fun record(current: Int?) = ArtifactRecord(id, workspaceId, NAME, "Monthly revenue", "", current, CREATED, CREATED, userId)

    private fun detail(
        version: Int,
        status: PipelineVersionStatus,
    ) = ArtifactVersionDetail(id, version, status, "hash-v$version", CREATED, userId)

    private fun draft() = ArtifactVersion(record(null), detail(1, PipelineVersionStatus.DRAFT), body())

    private fun released() = ArtifactVersion(record(1), detail(1, PipelineVersionStatus.RELEASED), body())

    /** The recording fake the #332 assertions read: the events in order, and the details beside each. */
    private class RecordingAudit : co.datapipelines.auth.AuditEventSink {
        val events = mutableListOf<String>()
        val details = mutableListOf<Map<String, Any?>>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            events.add(event)
            this.details.add(details)
        }
    }

    private companion object {
        const val NAME = "finance/visualizations/monthly_revenue"
        val CREATED: Instant = Instant.parse("2026-09-29T00:00:00Z")

        /** The implementation spec's §3.1 worked document (the DECIMAL fixture a number, dashboards.md §2.1). */
        val DOCUMENT =
            """
            {"name": "$NAME", "display_name": "Monthly revenue", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "transform": {"template": {"name": "finance/transforms/revenue_bars", "version": 2}, "inputs": {"rows": "revenue"}},
             "config": {"data": [{"type": "bar", "x": "${'$'}.x", "y": "${'$'}.y"}], "layout": {"title": {"text": "Revenue"}}},
             "bindings": {"data[0].x": "month_labels", "data[0].y": "amounts"},
             "presentation": {"title": "Monthly revenue", "tokens": {"series": "categorical"}},
             "tests": {"cases": [{"name": "twelve months", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}, {"kind": "trace_count", "equals": 1}]}]}}
            """.trimIndent()
    }
}
