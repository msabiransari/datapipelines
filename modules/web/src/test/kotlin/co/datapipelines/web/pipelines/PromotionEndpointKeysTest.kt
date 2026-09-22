package co.datapipelines.web.pipelines

import co.datapipelines.application.endpoints.EndpointKeyBinding
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.Scope
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
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
 *
 * The sender's half (#199): the batch carries the SOURCE workspace's bindings only, read with the
 * workspace predicate in the query — a neighbour's key bound at an equal node never rides along.
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
    private val endpoints = mockk<PublishedEndpointRepository>()

    // STRICT on purpose (#199): the sender may read bindings ONLY through the workspace-scoped
    // query. Any other read — the old `findAll()` shape, or a successor of it — is unstubbed and
    // fails the sender case with "no answer found", which is the guard against re-widening it.
    private val bindings = mockk<EndpointKeyBindingRepository>()
    private val pipelines = mockk<PipelineRepository>()

    private val promotion = EndpointPromotion(publishing, endpointKeys, endpoints, bindings, apiKeys, pipelines)

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
            promotion,
            mockk(relaxed = true),
        )

    @Test
    fun `a batch carries only the source workspace's bindings, never a neighbour's key bound at the same node`() {
        // #199. Only the exact path_pattern is globally unique (V11); path TREES may overlap, so
        // workspace B may legitimately bind at an ancestor of its own tree that equals A's
        // published pattern. B's key name must not ride into A's batch: on the target it would
        // either bind the target's key of that name to A's path, or refuse A's whole batch for a
        // name A never chose. The read is filtered by workspace in SQL; this pins that the
        // sender consults nothing else.
        val pipelineId = UUID.randomUUID()
        val record = record("lending_home", pipelineId)
        every { pipelines.findByName(WORKSPACE_ID, "lending_home") } returns record
        every { pipelines.findById(WORKSPACE_ID, pipelineId) } returns record
        every { endpoints.findByWorkspace(WORKSPACE_ID) } returns listOf(published("/lending/v1/home", pipelineId))
        every { bindings.findByWorkspace(WORKSPACE_ID) } returns
            listOf(binding("/lending/v1/home", "dpk_AAAAAAAAAAAA", WORKSPACE_ID))
        every { apiKeys.findById("dpk_AAAAAAAAAAAA") } returns key("dpk_AAAAAAAAAAAA", "ci-lending")
        every { apiKeys.findById("dpk_BBBBBBBBBBBB") } returns
            key("dpk_BBBBBBBBBBBB", "neighbour", workspaceId = FOREIGN_WORKSPACE_ID)

        val entries = promotion.entriesFor(WORKSPACE_ID, listOf("lending_home"))

        assertAll(
            { entries.map { it.path } shouldBe listOf("/lending/v1/home") },
            { entries.single().bindings shouldBe listOf("ci-lending") },
            // Neither a foreign name in the batch nor a foreign key id resolved on the way there.
            { verify(exactly = 0) { apiKeys.findById("dpk_BBBBBBBBBBBB") } },
        )

        // And on the target: a batch that names only A's key is accepted and binds only it —
        // no refusal for "neighbour" (a name A never chose), no widening to a key of that name.
        stubContext()
        every { apiKeys.findByWorkspaceAndName(WORKSPACE_ID, "ci-lending") } returns listOf(key("dpk_AAAAAAAAAAAA", "ci-lending"))
        every { apiKeys.findByWorkspaceAndName(WORKSPACE_ID, "neighbour") } returns emptyList()
        every { publishing.get(any(), any()) } returns null

        service.apply(batch("dev", entries))

        assertAll(
            { verify(exactly = 1) { endpointKeys.bind(any(), "dpk_AAAAAAAAAAAA", "/lending/v1/home") } },
            { verify(exactly = 1) { endpointKeys.bind(any(), any(), any()) } },
        )
    }

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
        batch(
            "dev",
            listOf(
                PromotionWire.EndpointEntry(
                    path = "/lending/home",
                    pipeline = "lending_home",
                    timeoutSeconds = 30,
                    bindings = keyNames.toList(),
                ),
            ),
        )

    private fun batch(
        sourceEnv: String,
        entries: List<PromotionWire.EndpointEntry>,
    ) = PromotionWire.Batch(
        sourceEnv = sourceEnv,
        keyFingerprint = "abcd1234",
        workspace = WORKSPACE_NAME,
        endpoints = entries,
    )

    private fun key(
        id: String,
        name: String,
        workspaceId: UUID = WORKSPACE_ID,
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
        workspaceId = workspaceId,
        workspaceName = WORKSPACE_NAME,
        kind = ApiKeyKind.ENDPOINT,
    )

    private fun record(
        name: String,
        id: UUID,
    ) = PipelineRecord(
        id = id,
        name = name,
        displayName = name,
        description = "",
        ownerId = ACTOR,
        currentVersion = 1,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun published(
        pathPattern: String,
        pipelineId: UUID,
    ) = PublishedEndpoint.of(
        id = UUID.randomUUID(),
        workspaceId = WORKSPACE_ID,
        pathPattern = pathPattern,
        pipelineId = pipelineId,
        timeoutSeconds = 30,
        description = "",
        isEnabled = true,
        createdBy = ACTOR,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun binding(
        pathPrefix: String,
        apiKeyId: String,
        workspaceId: UUID,
    ) = EndpointKeyBinding(pathPrefix, apiKeyId, workspaceId, ACTOR, Instant.EPOCH)

    private companion object {
        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val FOREIGN_WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000002")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        const val WORKSPACE_NAME = "default"
    }
}
