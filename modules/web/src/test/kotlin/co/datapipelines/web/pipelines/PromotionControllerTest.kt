package co.datapipelines.web.pipelines

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

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
    private val peer =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "dpk_peer@keys.invalid",
            displayName = "prod-receiver",
            authMethod = AuthMethod.PROMOTION,
            keyId = "dpk_PEER00000001",
            keyKind = ApiKeyKind.SERVER,
            keyRole = KeyRole.PROMOTION_RECEIVER,
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

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
        every { receiveService.apply(batch, peer) } returns applied
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(peer, null, emptyList())

        val response = controller.push(batch)

        response.data shouldBe applied
        // #215 C4: the batch is applied AS the authenticated peer — its identity is what the
        // received rows are attributed to.
        verify(exactly = 1) { receiveService.apply(batch, peer) }
    }
}
