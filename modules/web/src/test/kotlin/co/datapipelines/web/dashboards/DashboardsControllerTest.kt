package co.datapipelines.web.dashboards

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.ArtifactValidation
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardDocument
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardReader
import co.datapipelines.visualization.DashboardReleased
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.PointerMove
import co.datapipelines.visualization.Purged
import co.datapipelines.visualization.Switched
import co.datapipelines.visualization.VersionMoved
import co.datapipelines.web.api.ApiErrorCatalog
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
 * The dashboard controller over a mocked service and the REAL reader: the §13.23 envelope, the #291 body read, the
 * caller's lens on every read, the D61 consent flag, the session-only verbs — and `validate`, whose verdict is the
 * ANSWER (`200` with `valid` and every refusal in the 400's failure shape), judged on the WORKING version.
 */
class DashboardsControllerTest {
    private val service = mockk<DashboardService>()
    private val reader = DashboardReader()
    private val audit = RecordingAudit()
    private val controller = DashboardsController(service, reader, co.datapipelines.web.EVERYTHING_LENS, audit)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val id = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `create reads the document and lands version 1 DRAFT`() {
        authenticate()
        every { service.create(workspaceId, any(), userId, WriteSurface.SESSION) } returns draft()

        val data = controller.create(DOCUMENT).data

        assertAll(
            { data.get("status").asText() shouldBe "DRAFT" },
            {
                data
                    .get("sources")
                    .single()
                    .get("name")
                    .asText() shouldBe "revenue_source"
            },
        )
    }

    @Test
    fun `a malformed body is the dashboard family's 400 - never the pipeline family's code`() {
        authenticate()
        val error = shouldThrow<ApiException> { controller.update(id, "hash-v1", "{") }

        error.code shouldBe DashboardErrorCodes.BODY_INVALID
        error.details[ApiErrors.REASON] shouldBe ApiErrors.MALFORMED_JSON
        verify(exactly = 0) { service.write(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `validate judges the WORKING version and answers a valid verdict`() {
        authenticate()
        val working = draft()
        every { service.findWorking(workspaceId, ReadLens.Everything, id) } returns working
        every { service.validate(workspaceId, DashboardDocument(NAME, working.body)) } returns
            ArtifactValidation.Valid(DashboardDocument(NAME, working.body))

        val data = controller.validate(id).data

        assertAll(
            { data["valid"] shouldBe true },
            { data["failures"] shouldBe emptyList<Any>() },
            { data["version"] shouldBe 1 },
            { data["body_hash"] shouldBe "hash-v1" },
        )
    }

    @Test
    fun `validate answers every refusal in the 400's failure shape - with a 200, the request was fine`() {
        authenticate()
        val working = draft()
        every { service.findWorking(workspaceId, ReadLens.Everything, id) } returns working
        val failure =
            ValidationFailure(DashboardErrorCodes.SOURCE_NOT_RELEASED, "sources[0].pipeline", "not released", mapOf("status" to "DRAFT"))
        every { service.validate(workspaceId, any()) } returns ArtifactValidation.Invalid(ValidationResult(listOf(failure)))

        val data = controller.validate(id).data

        data["valid"] shouldBe false
        data["failures"] shouldBe
            listOf(
                mapOf(
                    "code" to DashboardErrorCodes.SOURCE_NOT_RELEASED,
                    "path" to "sources[0].pipeline",
                    "message" to "not released",
                    "details" to mapOf("status" to "DRAFT"),
                ),
            )
    }

    @Test
    fun `validate on an absent dashboard is the family's 404 - nothing is judged`() {
        authenticate()
        every { service.findWorking(workspaceId, ReadLens.Everything, id) } returns null

        shouldThrow<ApiException> { controller.validate(id) }.code shouldBe DashboardErrorCodes.NOT_FOUND
        verify(exactly = 0) { service.validate(any(), any()) }
    }

    @Test
    fun `a promoter's hidden dashboard is the family's 404 - read with HER dashboard lens`() {
        authenticate()
        val narrowing = ReadLens.Only(setOf("finance/dashboards/other"))
        every { service.findVersion(workspaceId, narrowing, id, 1) } returns null
        val lens = PromoterLens { LensedView(ReadLens.NOTHING, ReadLens.NOTHING, dashboards = narrowing) }
        val promoter = DashboardsController(service, reader, lens, audit)

        val error = shouldThrow<ApiException> { promoter.getVersion(id, 1) }

        error.code shouldBe DashboardErrorCodes.NOT_FOUND
        ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `release passes the visualization consent flag and names what it cascaded`() {
        authenticate()
        every { service.release(workspaceId, id, "hash-v1", userId, releasePinnedVisualizations = true) } returns
            DashboardReleased(released(), listOf(ArtifactRef("finance/visualizations/monthly_revenue", 3)))

        val data = controller.release(id, "hash-v1", releasePinnedVisualizations = true).data

        data
            .get("visualizations_released")
            .single()
            .get("name")
            .asText() shouldBe "finance/visualizations/monthly_revenue"
        data
            .get("visualizations_released")
            .single()
            .get("version")
            .asInt() shouldBe 3
        // #332 — the release audit, the auditRelease twin: the cascaded VISUALIZATION's own event
        // first (the 142 "who released X v3 and why" provenance: the dashboard release it rode on),
        // then the dashboard's own event naming it.
        audit.events shouldBe listOf("visualization.version.released", "dashboard.version.released")
        audit.details.first()["name"] shouldBe "finance/visualizations/monthly_revenue"
        audit.details.first()["version"] shouldBe 3
        audit.details.first()["cascade_from_dashboard_id"] shouldBe id.toString()
        audit.details.first()["cascade_from_version"] shouldBe 1
        audit.details.last()["dashboard_id"] shouldBe id.toString()
        audit.details.last()["dashboard_name"] shouldBe NAME
        audit.details.last()["version"] shouldBe 1
        audit.details.last()["visualizations_released"] shouldBe
            listOf(mapOf("name" to "finance/visualizations/monthly_revenue", "version" to 3))
    }

    @Test
    fun `the human verbs refuse an API key, and a session reaches them`() {
        authenticate(AuthMethod.API_KEY)
        shouldThrow<DatapipelinesException> { controller.delete(id) }.code shouldBe PipelineErrorCodes.Auth.SESSION_REQUIRED
        shouldThrow<DatapipelinesException> { controller.purgeDraft(id, "hash-v1") }.code shouldBe
            PipelineErrorCodes.Auth.SESSION_REQUIRED

        authenticate()
        // #372 — the audit rows' pre-reads are BODY-FREE and ride the caller's lens (Everything for this principal).
        every { service.auditIdentity(workspaceId, ReadLens.Everything, id) } returns (NAME to 1)
        every { service.auditVersionIdentity(workspaceId, ReadLens.Everything, id, 1) } returns (NAME to 1)
        every { service.auditVersionIdentity(workspaceId, ReadLens.Everything, id, 2) } returns (NAME to 2)
        every { service.purgeEntity(workspaceId, id) } returns Purged.Entity
        every { service.purgeDraft(workspaceId, id, "hash-v1") } returns Purged.Version
        every { service.discardVersion(workspaceId, id, 1, userId) } returns
            VersionMoved(detail(1, PipelineVersionStatus.DISCARDED), PointerMove(before = 1, after = null))
        every { service.restoreVersion(workspaceId, id, 1) } returns
            VersionMoved(detail(1, PipelineVersionStatus.RELEASED), PointerMove(before = null, after = 1))
        every { service.purgeVersion(workspaceId, id, 2) } returns Purged.Version
        every { service.switchCurrent(workspaceId, id, 1) } returns Switched(NAME, PointerMove(before = 2, after = 1))
        controller.delete(id)
        controller.purgeDraft(id, "hash-v1")
        controller.discardVersion(id, 1).data["status"] shouldBe "DISCARDED"
        controller.restoreVersion(id, 1).data["status"] shouldBe "RELEASED"
        controller.purgeVersion(id, 2)
        controller.switchCurrent(id, """{"version": 1}""").data["current_version"] shouldBe 1
        shouldThrow<ApiException> { controller.switchCurrent(id, """{"version": 1.5}""") }.details["reason"] shouldBe
            ApiErrors.REASON_WRONG_TYPE
        // #332 — every verb audited, in the order they ran, the pipelines mould's event per verb.
        audit.events shouldBe
            listOf(
                "dashboard.purged",
                "dashboard.version.purged",
                "dashboard.version.discarded",
                "dashboard.version.restored",
                "dashboard.version.purged",
                "dashboard.current_switched",
            )
        // #372 — the rows carry the pipelines mould's full shape; the exact key SET of each row is pinned,
        // so a missing key cannot pass, and the values come from the verb's result, not a re-read.
        audit.details.map { it["dashboard_name"] } shouldBe listOf(NAME, NAME, NAME, NAME, NAME, NAME)
        audit.details.map { it["version"] } shouldBe listOf(1, 1, 1, 1, 2, null)
        audit.details.map { it["scope"] } shouldBe listOf("entity", "version", null, null, "version", null)
        audit.details.map { it["current_version_before"] } shouldBe listOf(null, null, 1, null, null, null)
        audit.details.map { it["current_version_after"] } shouldBe listOf(null, null, null, 1, null, null)
        audit.details.map { it["from"] } shouldBe listOf(null, null, null, null, null, 2)
        audit.details.map { it["to"] } shouldBe listOf(null, null, null, null, null, 1)
        val withPointer =
            setOf("dashboard_id", "dashboard_name", "version", "workspace_id", "current_version_before", "current_version_after")
        audit.details.map { it.keys } shouldBe
            listOf(
                setOf("dashboard_id", "dashboard_name", "version", "workspace_id", "scope"),
                setOf("dashboard_id", "dashboard_name", "version", "workspace_id", "scope"),
                withPointer,
                withPointer,
                setOf("dashboard_id", "dashboard_name", "version", "workspace_id", "scope"),
                setOf("dashboard_id", "dashboard_name", "workspace_id", "from", "to"),
            )
    }

    @Test
    fun `the reads - working version, versions, browse and the flat listing - all pass the caller's lens`() {
        authenticate()
        every { service.findWorking(workspaceId, ReadLens.Everything, id) } returns released()
        every { service.listVersions(workspaceId, ReadLens.Everything, id) } returns listOf(detail(1, PipelineVersionStatus.RELEASED))
        every { service.listChildFolders(workspaceId, ReadLens.Everything, "finance") } returns emptyList()
        every { service.listChildren(workspaceId, ReadLens.Everything, "finance", 0, 50) } returns emptyList()
        every { service.countChildren(workspaceId, ReadLens.Everything, "finance") } returns 0
        every { service.listAll(workspaceId, ReadLens.Everything, 0, 50) } returns listOf(released())
        every { service.countAll(workspaceId, ReadLens.Everything) } returns 1

        assertAll(
            { controller.get(id).data.has("draft") shouldBe false },
            { controller.versions(id).data.single()["version"] shouldBe 1 },
            { controller.browse("finance", null, null).data["dashboards"] shouldBe emptyList<Any>() },
            {
                controller
                    .list(null, null)
                    .data.items
                    .single()["name"] shouldBe NAME
            },
        )
    }

    // ---- fixtures -------------------------------------------------------------------------------------

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

    private fun body(): DashboardBody = reader.readOrThrow(ArtifactJson.mapper.readTree(DOCUMENT)).body

    private fun record(current: Int?) = ArtifactRecord(id, workspaceId, NAME, "Revenue overview", "", current, CREATED, CREATED, userId)

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
        const val NAME = "finance/dashboards/revenue_overview"
        val CREATED: Instant = Instant.parse("2026-09-29T00:00:00Z")

        /** The implementation spec's §3.2 worked document. */
        val DOCUMENT =
            """
            {"name": "$NAME", "display_name": "Revenue overview", "description": "",
             "parameter_set": {"name": "finance/parameters/reporting_period", "version": 1},
             "sources": [{"name": "revenue_source", "pipeline": {"name": "finance/pipelines/monthly_revenue", "version": 7},
                          "parameters": {"year": {"parameter": "year"}, "currency": {"value": "USD"}}}],
             "visualizations": [{"name": "revenue_chart", "type": "visualization",
                                 "visualization": {"name": "finance/visualizations/monthly_revenue", "version": 3},
                                 "inputs": {"revenue": {"source": "revenue_source"}}, "timeout_seconds": 120}],
             "groups": [{"name": "overview_group", "type": "group", "members": ["year", "revenue_chart", "refresh_button"]}],
             "actions": [{"name": "refresh_overview", "type": "refresh", "scope": "targets", "targets": ["revenue_chart"],
                          "initial": true}],
             "action_controls": [{"name": "refresh_button", "type": "action_control", "action": "refresh_overview", "label": "Apply"}],
             "parameter_scopes": {"year": ["overview_group"]},
             "parameter_state": {"dashboard": {"visible": "inherit", "enabled": "inherit"},
                                 "parameters": {"currency": {"visible": "force_false"}}},
             "outgoing_overrides": {"revenue_source": {"currency": {"value": "USD"}}},
             "layout": {"parameter_set": {"position": "left"}, "parameter_placements": {"year": {"group": "overview_group"}},
                        "grid": [{"name": "revenue_chart", "x": 0, "y": 0, "w": 6, "h": 4}], "columns": 12},
             "timeouts": {"refresh_seconds": 300}}
            """.trimIndent()
    }
}
