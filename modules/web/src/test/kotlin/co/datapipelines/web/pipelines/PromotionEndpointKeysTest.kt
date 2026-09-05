package co.datapipelines.web.pipelines

import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.Scope
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.templates.TemplateImportService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * §10.5's discipline applied to endpoint bindings (074): a batch that binds a key name the target
 * does not have is refused **for the whole batch, before anything is pushed**.
 *
 * The property under test is the "before anything is pushed" half, not just the refusal. A
 * receiver that published the endpoints and only then discovered the missing key would leave the
 * target half-promoted with URLs nobody can call — which is exactly what the datasource
 * pre-validation exists to prevent, and why this mirrors it rather than checking per entry.
 */
class PromotionEndpointKeysTest {
    private val inventory = mockk<PromotionInventoryService>()
    private val pipelineImport = mockk<PipelineImportService>(relaxed = true)
    private val templateImport = mockk<TemplateImportService>(relaxed = true)
    private val userService = mockk<UserService>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val publishing = mockk<EndpointPublishService>(relaxed = true)
    private val endpointKeys = mockk<EndpointKeyService>(relaxed = true)
    private val apiKeys = mockk<ApiKeyRepository>()

    private val service =
        PromotionReceiveService(
            inventory,
            pipelineImport,
            templateImport,
            userService,
            auditLogger,
            // A template with no real manager: this suite never reaches a commit, and the two
            // paths it does reach are "refuse before the transaction" and "apply inside it".
            TransactionTemplate(mockk(relaxed = true)),
            false,
            EndpointPromotion(publishing, endpointKeys, mockk(relaxed = true), mockk(relaxed = true), apiKeys, mockk(relaxed = true)),
        )

    @Test
    fun `a batch binding a key the target does not have is refused, naming every missing key`() {
        stubContext()
        every { apiKeys.findByWorkspaceAndName(WORKSPACE_ID, any()) } returns emptyList()

        val refused = shouldThrow<DatapipelinesException> { service.apply(batch("ci-lending", "ci-trade")) }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.PROMOTION_KEY_MISSING },
            { refused.message.orEmpty() shouldContain "ci-lending" },
            { refused.message.orEmpty() shouldContain "ci-trade" },
            // Reported ONCE for the batch, both names together — not one round trip per key.
            { (refused.details["missing_api_keys"] as List<*>).size shouldBe 2 },
        )
    }

    @Test
    fun `the refusal happens before anything is published`() {
        // The half that matters: a target left with URLs nobody can call is worse than a refusal.
        stubContext()
        every { apiKeys.findByWorkspaceAndName(WORKSPACE_ID, any()) } returns emptyList()

        shouldThrow<DatapipelinesException> { service.apply(batch("absent")) }

        verify(exactly = 0) { publishing.publish(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { endpointKeys.bind(any(), any(), any()) }
    }

    @Test
    fun `a batch whose key names all exist publishes and binds them`() {
        stubContext()
        every { apiKeys.findByWorkspaceAndName(WORKSPACE_ID, "ci-lending") } returns listOf(key("dpk_AAAAAAAAAAAA", "ci-lending"))
        every { publishing.get(any(), any()) } returns null

        service.apply(batch("ci-lending"))

        verify(exactly = 1) { publishing.publish(any(), "/lending/home", "lending_home", 30, "") }
        verify(exactly = 1) { endpointKeys.bind(any(), "dpk_AAAAAAAAAAAA", "/lending/home") }
    }

    @Test
    fun `an ambiguous key name binds every key carrying it, rather than guessing`() {
        // `api_keys.name` has no uniqueness constraint, so a name can belong to several people in
        // one workspace. The batch asked for "the key called X"; silently picking one would be a
        // guess about whose credential to widen.
        stubContext()
        every { apiKeys.findByWorkspaceAndName(WORKSPACE_ID, "shared") } returns
            listOf(key("dpk_AAAAAAAAAAAA", "shared"), key("dpk_BBBBBBBBBBBB", "shared"))
        every { publishing.get(any(), any()) } returns null

        service.apply(batch("shared"))

        assertAll(
            { verify(exactly = 1) { endpointKeys.bind(any(), "dpk_AAAAAAAAAAAA", "/lending/home") } },
            { verify(exactly = 1) { endpointKeys.bind(any(), "dpk_BBBBBBBBBBBB", "/lending/home") } },
        )
    }

    private fun stubContext() {
        every { inventory.contextFor(WORKSPACE_NAME) } returns WorkspaceContext(WORKSPACE_ID, WORKSPACE_NAME)
        every { userService.systemActor() } returns
            User(
                id = ACTOR,
                email = "system@datapipelines.test",
                displayName = "System",
                provider = "system",
                providerSubject = "system",
                isActive = true,
                isAdmin = true,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                lastLoginAt = null,
            )
    }

    private fun batch(vararg keyNames: String) =
        PromotionWire.Batch(
            sourceEnv = "dev",
            keyFingerprint = "abcd1234",
            workspace = WORKSPACE_NAME,
            endpoints =
                listOf(
                    PromotionWire.EndpointEntry(
                        path = "/lending/home",
                        pipeline = "lending_home",
                        timeoutSeconds = 30,
                        bindings = keyNames.toList(),
                    ),
                ),
        )

    private fun key(
        id: String,
        name: String,
    ) = ApiKey(
        id = id,
        userId = ACTOR,
        name = name,
        keyHash = "hash",
        scopes = setOf(Scope.READ),
        isRevoked = false,
        createdAt = Instant.EPOCH,
        lastUsedAt = null,
        expiresAt = null,
        workspaceId = WORKSPACE_ID,
        workspaceName = WORKSPACE_NAME,
        kind = ApiKeyKind.ENDPOINT,
    )

    private companion object {
        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        const val WORKSPACE_NAME = "default"
    }
}
