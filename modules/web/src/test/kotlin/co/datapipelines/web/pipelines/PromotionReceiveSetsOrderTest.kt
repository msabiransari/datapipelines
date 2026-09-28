package co.datapipelines.web.pipelines

import co.datapipelines.application.checks.PipelineCheckRunner
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.web.parameters.ParameterSetPromotion
import co.datapipelines.web.templates.TemplateImportService
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate

/**
 * The §8.3 push ORDER on the receiver (the record's, #194 lane D): templates, then parameter
 * sets, then pipelines — a set's pins must be stored before the set's validation resolves them,
 * and a set import that ran before the templates would refuse `parameter.import.missing_template`
 * on a pin the SAME batch brings. The order is the guard; this test is its falsification lever.
 */
class PromotionReceiveSetsOrderTest {
    private val calls = mutableListOf<String>()
    private val inventory = mockk<PromotionInventoryService>()
    private val pipelineImportService = mockk<PipelineImportService>()
    private val templateImportService = mockk<TemplateImportService>()
    private val parameterSetPromotion = mockk<ParameterSetPromotion>()
    private val peer =
        AuthenticatedPrincipal(
            userId = UUID,
            email = "dpk_peer@keys.invalid",
            displayName = "peer",
            authMethod = AuthMethod.PROMOTION,
            keyId = "dpk_PEER00000001",
            keyKind = ApiKeyKind.SERVER,
            keyRole = KeyRole.PROMOTION_RECEIVER,
        )

    private val service =
        PromotionReceiveService(
            inventory,
            pipelineImportService,
            templateImportService,
            mockk<AuditLogger>(relaxed = true),
            // Run the callback directly; the ORDER is what this suite pins.
            TransactionTemplate(NoopTransactionManager),
            authoringEnabled = false,
            endpointPromotion = mockk<EndpointPromotion>(relaxed = true),
            checkRunner = mockk<PipelineCheckRunner>(relaxed = true),
            parameterSetPromotion,
        )

    @Test
    fun `a batch applies templates, then parameter sets, then pipelines`() {
        every { inventory.contextFor("acme") } returns
            co.datapipelines.auth.WorkspaceContext(UUID, "acme")
        every { templateImportService.import(any(), UUID, UUID) } answers {
            calls += "templates"
            emptyList()
        }
        every { parameterSetPromotion.apply(any(), UUID, UUID) } answers {
            calls += "sets"
            mockk()
        }
        every { pipelineImportService.import(any(), UUID, UUID) } answers {
            calls += "pipelines"
            mockk()
        }

        val batch =
            PromotionWire.Batch(
                sourceEnv = "dev",
                keyFingerprint = "fp",
                workspace = "acme",
                templates = listOf(NODE),
                parameterSets = listOf(NODE),
                pipelines = listOf(NODE),
            )
        service.apply(batch, peer)

        calls shouldBe listOf("templates", "sets", "pipelines")
    }

    private companion object {
        val UUID: java.util.UUID = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555")

        /** A real empty node — the receiver's pipeline gate parses each entry. */
        val NODE: JsonNode =
            com.fasterxml.jackson.databind.node.JsonNodeFactory
                .instance
                .objectNode()

        /** No real transaction: the callback runs inline, the ORDER is what this suite pins. */
        private object NoopTransactionManager : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

            override fun commit(status: TransactionStatus) = Unit

            override fun rollback(status: TransactionStatus) = Unit
        }
    }
}
