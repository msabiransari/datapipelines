package co.datapipelines.web.visualizations

import co.datapipelines.application.lens.LensedView
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
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.EVERYTHING_LENS
import co.datapipelines.web.api.ApiErrorCatalog
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.JsonNode
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
 * The transfer routes' REST edge (the `ParameterSetsControllerTest` export/import shape): the lens BEFORE the
 * export (a hidden visualization is the family's 404, the transfer never asked), the #291 body read on import
 * (a malformed body is the family's 400, never the service's business), and the #332 audit rows — asserted on a
 * REAL recording sink, because a strict mock would make a missing emission unobservable.
 */
class VisualizationTransferControllerTest {
    private val transfer = mockk<ArtifactTransferService>()
    private val visualizations = mockk<VisualizationService>()
    private val recorder = RecordingAuditSink()
    private val controller = VisualizationTransferController(transfer, visualizations, EVERYTHING_LENS, recorder)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val id = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `export hands the caller's lens to the working read and exports the release`() {
        authenticate()
        every { visualizations.findWorking(workspaceId, LensedView.EVERYTHING.visualizations, id) } returns released()
        every { transfer.exportVisualization(workspaceId, id) } returns envelope()

        val response = controller.export(id)

        assertAll(
            {
                response.data
                    .path("visualization")
                    .path("id")
                    .asText() shouldBe id.toString()
            },
            {
                response.data
                    .path("manifest")
                    .path("visualization_version")
                    .asInt() shouldBe 1
            },
            {
                recorder.single(AUDIT_EXPORTED).second shouldBe
                    mapOf("workspace_id" to workspaceId.toString(), "visualization_id" to id.toString(), "version" to 1)
            },
        )
    }

    @Test
    fun `a lens-hidden visualization exports as an absent one - the transfer is never asked`() {
        authenticate()
        every { visualizations.findWorking(workspaceId, LensedView.EVERYTHING.visualizations, id) } returns null

        val error = shouldThrow<ApiException> { controller.export(id) }

        assertAll(
            { error.code shouldBe VisualizationErrorCodes.NOT_FOUND },
            { ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.NOT_FOUND },
            { verify(exactly = 0) { transfer.exportVisualization(any(), any()) } },
            { recorder.events shouldBe emptyList() },
        )
    }

    @Test
    fun `import reads the envelope through the request mapper - a malformed body is the family's 400, the transfer never asked`() {
        authenticate()

        val error = shouldThrow<ApiException> { controller.import("""{"visualization": {"id": """) }

        assertAll(
            { error.code shouldBe VisualizationErrorCodes.BODY_INVALID },
            { error.details[ApiErrors.REASON] shouldBe ApiErrors.MALFORMED_JSON },
            { verify(exactly = 0) { transfer.importVisualization(any(), any(), any()) } },
            { recorder.events shouldBe emptyList() },
        )
    }

    @Test
    fun `import lands through the transfer and audits imported_with_evidence from the manifest verbatim`() {
        authenticate()
        val envelope = envelope()
        val imported =
            ArtifactImported(
                ArtifactVersionDetail(id, 1, PipelineVersionStatus.RELEASED, "hash-v1", CREATED, userId),
                created = true,
                unchanged = false,
            )
        every { transfer.importVisualization(workspaceId, any(), userId) } returns imported

        val response = controller.import(envelope.toString())

        assertAll(
            { response.data["id"] shouldBe id.toString() },
            { response.data["import_created"] shouldBe true },
            {
                recorder.single(AUDIT_IMPORTED).second.let {
                    it["visualization_id"] shouldBe id.toString()
                    it["version"] shouldBe 1
                    // The manifest says `evidence: null` until L4 — the audit records FALSE, verbatim.
                    it["imported_with_evidence"] shouldBe false
                }
            },
        )
    }

    @Test
    fun `an envelope whose manifest carries an evidence summary audits imported_with_evidence true`() {
        authenticate()
        val envelope = envelope()
        (envelope.get("manifest") as com.fasterxml.jackson.databind.node.ObjectNode).putObject("evidence").put("verdict", "PASS")
        every { transfer.importVisualization(workspaceId, any(), userId) } returns
            ArtifactImported(
                ArtifactVersionDetail(id, 1, PipelineVersionStatus.RELEASED, "hash-v1", CREATED, userId),
                created = false,
                unchanged = true,
            )

        controller.import(envelope.toString())

        recorder.single(AUDIT_IMPORTED).second["imported_with_evidence"] shouldBe true
    }

    private fun released() =
        ArtifactVersion(
            ArtifactRecord(id, workspaceId, NAME, "Monthly revenue", "", 1, CREATED, CREATED, userId),
            ArtifactVersionDetail(id, 1, PipelineVersionStatus.RELEASED, "hash-v1", CREATED, userId),
            VisualizationReader().readOrThrow(ArtifactJson.mapper.readTree(DOCUMENT)).body,
        )

    /** The §12 envelope: the artifact node, an empty bundle, the manifest — `evidence: null` until L4. */
    private fun envelope(): com.fasterxml.jackson.databind.node.ObjectNode {
        val root = ArtifactJson.mapper.createObjectNode()
        val payload = root.putObject("visualization")
        payload
            .put("id", id.toString())
            .put("name", NAME)
            .put("version", 1)
            .put("body_hash", "hash-v1")
        payload.set<JsonNode>("body", ArtifactJson.mapper.readTree(DOCUMENT))
        root.putArray("templates")
        root.putObject("manifest").put("visualization_version", 1).putNull("evidence")
        return root
    }

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
        const val NAME = "finance/visualizations/monthly_revenue"
        val CREATED: Instant = Instant.parse("2026-09-29T00:00:00Z")
        val AUDIT_EXPORTED = VisualizationTransferController.AUDIT_EXPORTED
        val AUDIT_IMPORTED = VisualizationTransferController.AUDIT_IMPORTED

        /** The §3.1 worked document — the same fixture the lifecycle controller test binds. */
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
