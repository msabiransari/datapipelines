package co.datapipelines.web.pipelines

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * [PromotionController] — the two receiver routes' unit contract beside the
 * two-deployment E2E that exercises them over HTTP: `inventory` delegates with the
 * TRIMMED workspace name (the sender sends names, and whitespace is not identity),
 * and `push` hands the batch to the receive service in the order given. The URL-space
 * key gate (`PromotionServerKeyFilter`) is that filter's own suite's subject.
 */
class PromotionControllerTest {
    private val inventoryService = mockk<PromotionInventoryService>()
    private val receiveService = mockk<PromotionReceiveService>()
    private val controller = PromotionController(inventoryService, receiveService)

    @Test
    fun `inventory returns the service's answer`() {
        val inventory =
            PromotionWire.Inventory(
                deployment = "prod",
                authoringEnabled = false,
                workspace = "acme",
                pipelines = listOf(PromotionWire.Entry("p", 1, "h")),
                templates = emptyList(),
                datasources = listOf("pg"),
            )
        every { inventoryService.inventoryOf("acme") } returns inventory

        val response = controller.inventory(workspace = "  acme  ")

        response.data shouldBe inventory
        verify(exactly = 1) { inventoryService.inventoryOf("acme") }
    }

    @Test
    fun `push applies the batch through the receive service`() {
        val batch =
            PromotionWire.Batch(
                workspace = "acme",
                sourceEnv = "staging",
                keyFingerprint = "sha256:abc",
            )
        val applied =
            PromotionWire.Applied(
                workspace = "acme",
                sourceEnv = "staging",
                templates = 0,
                pipelines = 0,
            )
        every { receiveService.apply(batch) } returns applied

        val response = controller.push(batch)

        response.data shouldBe applied
        verify(exactly = 1) { receiveService.apply(batch) }
    }
}
