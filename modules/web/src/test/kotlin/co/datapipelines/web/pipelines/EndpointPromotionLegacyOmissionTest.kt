package co.datapipelines.web.pipelines

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointRow
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * #286 item 3 — a promotion batch OMITS a legacy endpoint row (#274: a stored path today's grammar
 * refuses — the target would refuse it too), and says so. Before #274 the batch threw on such a
 * row; after it the row was left out without a word. The sender now logs the omission with the
 * COUNT over the promoted pipelines — never a path — so an operator knows the endpoint did not
 * travel; the fix stays the one the console offers (unpublish, republish at a legal path).
 *
 * The line is a WARN on [EndpointPromotion]'s own logger; the promotion RESULT is the wire the
 * promotion services own and does not change here.
 */
class EndpointPromotionLegacyOmissionTest {
    private val endpoints = mockk<PublishedEndpointRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val bindings = mockk<EndpointKeyBindingRepository> { every { findByWorkspace(WORKSPACE_ID) } returns emptyList() }
    private val promotion = EndpointPromotion(mockk(), mockk(), endpoints, bindings, mockk(), pipelines)

    private val logger = LoggerFactory.getLogger(EndpointPromotion::class.java) as Logger
    private val captured = ListAppender<ILoggingEvent>().also { it.start() }

    init {
        logger.addAppender(captured)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(captured)
    }

    @Test
    fun `a legacy row over a promoted pipeline is omitted from the batch and the omission is logged with its count, never its path`() {
        val promoted = UUID.randomUUID()
        val other = UUID.randomUUID()
        every { pipelines.findByName(WORKSPACE_ID, "lending_home") } returns record("lending_home", promoted)
        every { endpoints.findByWorkspace(WORKSPACE_ID) } returns emptyList()
        every { endpoints.findLegacy(WORKSPACE_ID) } returns
            listOf(legacy("/lending/home", promoted), legacy("/trade/rates", other))

        val entries = promotion.entriesFor(WORKSPACE_ID, listOf("lending_home"))

        entries.shouldBeEmpty()
        val warned = captured.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        withClue(warned) {
            warned.size shouldBe 1
            // Only the row over the PROMOTED pipeline counts; the other pipeline's is not this batch's.
            warned.single() shouldContain "event=endpoint.promotion_legacy_omitted count=1"
            warned.single() shouldNotContain "/lending/home"
        }
    }

    @Test
    fun `no legacy row over the promoted pipelines - nothing is logged`() {
        val promoted = UUID.randomUUID()
        every { pipelines.findByName(WORKSPACE_ID, "lending_home") } returns record("lending_home", promoted)
        every { endpoints.findByWorkspace(WORKSPACE_ID) } returns emptyList()
        every { endpoints.findLegacy(WORKSPACE_ID) } returns listOf(legacy("/trade/rates", UUID.randomUUID()))

        promotion.entriesFor(WORKSPACE_ID, listOf("lending_home"))

        captured.list.filter { it.level == Level.WARN }.shouldBeEmpty()
    }

    private fun record(
        name: String,
        id: UUID,
    ) = PipelineRecord(
        id = id,
        name = name,
        displayName = name,
        description = "",
        ownerId = UUID.randomUUID(),
        currentVersion = 1,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun legacy(
        path: String,
        pipelineId: UUID,
    ) = EndpointRow.Legacy(
        id = UUID.randomUUID(),
        workspaceId = WORKSPACE_ID,
        pathPattern = path,
        pipelineId = pipelineId,
        reason = "Path has 2 segment(s); an endpoint is at least 3.",
        enabled = false,
    )

    private companion object {
        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000286")
    }
}
