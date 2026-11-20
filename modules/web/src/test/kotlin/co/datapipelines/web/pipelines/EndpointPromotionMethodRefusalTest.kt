package co.datapipelines.web.pipelines

import co.datapipelines.application.endpoints.CurrentVersion
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * #1037 — a promotion batch REFUSES to carry a mutation route, whole, before anything is sent.
 *
 * The wire (§19.5) has no method field until #1040's method-aware transfer, so a non-GET route
 * that travelled would arrive as a GET entity with a GET tree grant — reinterpreted into the
 * one thing the method identity exists to prevent. The sender therefore throws
 * `endpoint.promotion.method_unsupported` (409) for the ENTIRE batch the moment one non-GET
 * route rides the promoted pipelines; the operator's own routes are named (method and path —
 * this is the sender's workspace, not a redaction case), and the GET routes of the same batch
 * do not travel either: the refusal is whole, never partial.
 *
 * The fence: a mutation route over a pipeline the batch does NOT promote never blocks it — it
 * was never travelling.
 */
class EndpointPromotionMethodRefusalTest {
    private val endpoints = mockk<PublishedEndpointRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val bindings = mockk<EndpointKeyBindingRepository> { every { findByWorkspace(WORKSPACE_ID) } returns emptyList() }
    private val promotion = EndpointPromotion(mockk(), mockk(), mockk(), endpoints, bindings, mockk(), pipelines)

    @Test
    fun `a non-GET route over a promoted pipeline refuses the whole batch with its method and path`() {
        every { pipelines.findByName(WORKSPACE_ID, "payments") } returns record("payments")
        every { endpoints.findLegacy(WORKSPACE_ID) } returns emptyList()
        every { endpoints.findByWorkspace(WORKSPACE_ID) } returns
            listOf(
                published("/finance/v1/revenue/{region}", "payments", EndpointMethod.GET),
                published("/finance/v1/payments", "payments", EndpointMethod.POST),
            )

        val thrown =
            assertThrows<DatapipelinesException> {
                promotion.entriesFor(WORKSPACE_ID, listOf("payments"))
            }

        assertSoftly(thrown) {
            code shouldBe PipelineErrorCodes.Endpoint.PROMOTION_METHOD_UNSUPPORTED
            message shouldContain "POST /finance/v1/payments"
            // The batch's GET route is named nowhere as travelling — the refusal is whole.
            message shouldNotContain "/finance/v1/revenue"
            details["unsupported_routes"] shouldBe
                listOf(mapOf("method" to "POST", "path" to "/finance/v1/payments"))
        }
    }

    @Test
    fun `a non-GET route over a pipeline the batch does not promote never blocks it`() {
        every { pipelines.findByName(WORKSPACE_ID, "payments") } returns record("payments")
        every { endpoints.findLegacy(WORKSPACE_ID) } returns emptyList()
        every { endpoints.findByWorkspace(WORKSPACE_ID) } returns
            listOf(
                published("/finance/v1/revenue/{region}", "payments", EndpointMethod.GET),
                published("/billing/v1/charge", "billing", EndpointMethod.POST),
            )

        val entries = promotion.entriesFor(WORKSPACE_ID, listOf("payments"))

        entries.map { it.path } shouldBe listOf("/finance/v1/revenue/{region}")
    }

    @Test
    fun `a batch of GET routes only is unchanged`() {
        every { pipelines.findByName(WORKSPACE_ID, "payments") } returns record("payments")
        every { endpoints.findLegacy(WORKSPACE_ID) } returns emptyList()
        every { endpoints.findByWorkspace(WORKSPACE_ID) } returns
            listOf(published("/finance/v1/revenue/{region}", "payments", EndpointMethod.GET))

        val entries = promotion.entriesFor(WORKSPACE_ID, listOf("payments"))

        entries.map { it.path } shouldContainExactly listOf("/finance/v1/revenue/{region}")
    }

    private fun record(name: String) =
        PipelineRecord(
            id = UUID.randomUUID(),
            name = name,
            displayName = name,
            description = "",
            ownerId = UUID.randomUUID(),
            currentVersion = 1,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun published(
        pathPattern: String,
        pipelineName: String,
        method: EndpointMethod,
    ) = PublishedEndpoint.of(
        id = UUID.randomUUID(),
        workspaceId = WORKSPACE_ID,
        pathPattern = pathPattern,
        method = method,
        isEnabled = true,
        createdBy = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        current = CurrentVersion(1, PipelineVersionStatus.RELEASED, EndpointVersionBody(pipelineName, 1, 30, "")),
    )

    private companion object {
        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000001037")
    }
}
