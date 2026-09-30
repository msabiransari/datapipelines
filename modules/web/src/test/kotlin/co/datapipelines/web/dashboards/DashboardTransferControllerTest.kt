package co.datapipelines.web.dashboards

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.visualization.ArtifactImported
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactTransferService
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardImport
import co.datapipelines.visualization.DashboardReader
import co.datapipelines.visualization.DashboardService
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
 * The dashboard transfer routes' REST edge — [co.datapipelines.web.visualizations.VisualizationTransferControllerTest]'s
 * twin: the lens BEFORE the export, the #291 body read on import, the audit rows (the dashboard's carry the bundled
 * visualizations' count) on a REAL recording sink.
 */
class DashboardTransferControllerTest {
    private val transfer = mockk<ArtifactTransferService>()
    private val dashboards = mockk<DashboardService>()
    private val recorder = RecordingAuditSink()
    private val controller = DashboardTransferController(transfer, dashboards, co.datapipelines.web.EVERYTHING_LENS, recorder)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val id = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `export hands the caller's lens to the working read and exports the release`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, VIEW, id) } returns released()
        every { transfer.exportDashboard(workspaceId, id) } returns envelope()

        val response = controller.export(id)

        assertAll(
            {
                val data = response.data
                data.path("dashboard").path("id").asText() shouldBe id.toString()
                data.path("manifest").path("dashboard_version").asInt() shouldBe 1
            },
            {
                recorder.single(AUDIT_EXPORTED).second shouldBe
                    mapOf("workspace_id" to workspaceId.toString(), "dashboard_id" to id.toString(), "version" to 1)
            },
        )
    }

    @Test
    fun `a lens-hidden dashboard exports as an absent one - the transfer is never asked`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, VIEW, id) } returns null

        val error = shouldThrow<ApiException> { controller.export(id) }

        assertAll(
            { error.code shouldBe DashboardErrorCodes.NOT_FOUND },
            { ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.NOT_FOUND },
            { verify(exactly = 0) { transfer.exportDashboard(any(), any()) } },
            { recorder.events shouldBe emptyList() },
        )
    }

    @Test
    fun `import reads the envelope through the request mapper - a malformed body is the family's 400, the transfer never asked`() {
        authenticate()

        val error = shouldThrow<ApiException> { controller.import("""{"dashboard": {"id": """) }

        assertAll(
            { error.code shouldBe DashboardErrorCodes.BODY_INVALID },
            { error.details[ApiErrors.REASON] shouldBe ApiErrors.MALFORMED_JSON },
            { verify(exactly = 0) { transfer.importDashboard(any(), any(), any()) } },
            { recorder.events shouldBe emptyList() },
        )
    }

    @Test
    fun `import lands through the transfer and audits the dashboard and its bundled visualizations`() {
        authenticate()
        val envelope = envelope()
        val detail = { artifactId: UUID ->
            ArtifactVersionDetail(artifactId, 1, PipelineVersionStatus.RELEASED, "hash-v1", CREATED, userId)
        }
        every { transfer.importDashboard(workspaceId, any(), userId) } returns
            DashboardImport(
                ArtifactImported(detail(id), created = true, unchanged = false),
                listOf(ArtifactImported(detail(UUID.randomUUID()), created = true, unchanged = false)),
            )

        val response = controller.import(envelope.toString())

        val dashboard = response.data["dashboard"] as Map<*, *>
        val bundled = response.data["visualizations"] as List<*>
        assertAll(
            { dashboard["id"] shouldBe id.toString() },
            { bundled.size shouldBe 1 },
            {
                recorder.single(AUDIT_IMPORTED).second.let {
                    it["dashboard_id"] shouldBe id.toString()
                    it["version"] shouldBe 1
                    it["visualizations"] shouldBe 1
                    it["imported_with_evidence"] shouldBe false
                }
            },
        )
    }

    private fun released(): ArtifactVersion<co.datapipelines.visualization.DashboardBody> =
        ArtifactVersion(
            ArtifactRecord(id, workspaceId, NAME, "Revenue overview", "", 1, CREATED, CREATED, userId),
            ArtifactVersionDetail(id, 1, PipelineVersionStatus.RELEASED, "hash-v1", CREATED, userId),
            DashboardReader().readOrThrow(ArtifactJson.mapper.readTree(DASHBOARD_DOCUMENT)).body,
        )

    /** The §12 envelope: the artifact node, an empty bundle, the manifest — `evidence: null` until L4. */
    private fun envelope(): com.fasterxml.jackson.databind.node.ObjectNode {
        val root = ArtifactJson.mapper.createObjectNode()
        // The payload the REAL sender emits: the body + the nine lifecycle fields (the transfer's own builder).
        root.set<com.fasterxml.jackson.databind.JsonNode>("dashboard", ArtifactTransferService.payloadOf(released()))
        root.putArray("visualizations")
        root.putObject("manifest").put("dashboard_version", 1).putNull("evidence")
        return root
    }

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                AuthMethod.OIDC,
                keyId = null,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    /** The in-memory sink the audit assertions read — the house rule over a strict mock. */
    private class RecordingAuditSink : co.datapipelines.auth.AuditEventSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            events += event to details
        }

        fun single(event: String): Pair<String, Map<String, Any?>> {
            kotlin.check(events.size == 1) { "expected exactly one event, got ${events.size}" }
            return events.single().also { kotlin.check(it.first == event) { "expected $event, got ${it.first}" } }
        }
    }

    private companion object {
        val VIEW = co.datapipelines.pipeline.ReadLens.Everything
        const val NAME = "finance/dashboards/revenue_overview"
        val CREATED: Instant = Instant.parse("2026-09-29T00:00:00Z")
        val AUDIT_EXPORTED = DashboardTransferController.AUDIT_EXPORTED
        val AUDIT_IMPORTED = DashboardTransferController.AUDIT_IMPORTED

        /** The implementation spec's §3.2 worked dashboard — the name added, the pin left as the fixture holds it. */
        val DASHBOARD_DOCUMENT =
            """
            {"name": "$NAME", "display_name": "Revenue overview", "description": "",
             "parameter_set": {"name": "finance/parameters/reporting_period", "version": 1},
             "sources": [
               {"name": "revenue_source", "pipeline": {"name": "finance/pipelines/monthly_revenue", "version": 7},
                "parameters": {"year": {"parameter": "year"}, "currency": {"value": "USD"}}}
             ],
             "visualizations": [
               {"name": "revenue_chart", "type": "visualization",
                "visualization": {"name": "finance/visualizations/monthly_revenue", "version": 3},
                "inputs": {"revenue": {"source": "revenue_source"}}, "timeout_seconds": 120}
             ],
             "groups": [{"name": "overview_group", "type": "group", "members": ["year", "revenue_chart", "refresh_button"]}],
             "actions": [{"name": "refresh_overview", "type": "refresh", "scope": "targets", "targets": ["revenue_chart"], "initial": true}],
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
