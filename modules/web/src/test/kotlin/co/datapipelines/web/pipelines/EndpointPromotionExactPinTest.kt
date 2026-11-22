package co.datapipelines.web.pipelines

import co.datapipelines.application.endpoints.CurrentVersion
import co.datapipelines.application.endpoints.EndpointBodyContract
import co.datapipelines.application.endpoints.EndpointKeyBinding
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.EndpointPinValidator
import co.datapipelines.application.endpoints.EndpointPromotionImport
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * #1040 (published-mutation-apis §7) — a POST, PUT or DELETE route travels EXACTLY pinned, to a receiver that advertises
 * it can import it so, or the whole transfer is refused before anything is sent or applied.
 *
 * Sender: a method entry carries its method, the exact pipeline version and that version's body hash, the pinned
 * version's body mode and the names of the keys holding an exact grant of that method on that route — and no GET tree
 * `bindings`; a GET entry beside it is the legacy entry, unchanged. A receiver advertising nothing (every build before
 * #1040), or one missing the route's method or body mode, refuses the batch naming the routes.
 *
 * Receiver: a method entry without its pin, hash or a known body mode, or carrying GET `bindings`, refuses the batch
 * before any import or bind; a valid one is imported on the sender's exact pin and its grants bound with its method.
 */
class EndpointPromotionExactPinTest {
    private val publishing = mockk<EndpointPublishService>(relaxed = true)
    private val importer = mockk<EndpointPromotionImport>(relaxed = true)
    private val endpointKeys = mockk<EndpointKeyService>(relaxed = true)
    private val endpoints = mockk<PublishedEndpointRepository>()
    private val bindings = mockk<EndpointKeyBindingRepository>()
    private val apiKeys = mockk<ApiKeyRepository>()
    private val pipelines = mockk<PipelineRepository>()

    // STRICT: with no capabilities advertised the sender must refuse before reading any pin.
    private val pins = mockk<EndpointPinValidator>()
    private val inventory = mockk<PromotionInventoryService>()

    private val promotion = EndpointPromotion(publishing, importer, endpointKeys, endpoints, bindings, apiKeys, pipelines, pins)

    private val service =
        PromotionReceiveService(
            inventory,
            mockk<PipelineImportService>(relaxed = true),
            mockk<co.datapipelines.web.templates.TemplateImportService>(relaxed = true),
            mockk(relaxed = true),
            TransactionTemplate(mockk(relaxed = true)),
            false,
            promotion,
            mockk(relaxed = true),
            mockk<co.datapipelines.web.parameters.ParameterSetPromotion>(),
            mockk<co.datapipelines.web.visualizations.VisualizationPromotion>(),
            mockk<co.datapipelines.web.visualizations.DashboardPromotion>(),
            co.datapipelines.visualization.VisualizationConfig(),
        )

    // ---------------------------------------------------------------------------------------
    // Sender
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a capable receiver gets the method entry with its exact pin, hash, body mode and grants, beside the unchanged GET entry`() {
        val get = published("/lending/v1/applications", EndpointMethod.GET, version = 2)
        val post = published("/lending/v1/applications", EndpointMethod.POST, version = 3)
        stubSender(listOf(get, post))
        every { bindings.findByWorkspace(WORKSPACE_ID) } returns
            listOf(
                EndpointKeyBinding("/lending/v1/applications", READ_KEY.id, WORKSPACE_ID, ACTOR, Instant.EPOCH),
                EndpointKeyBinding(
                    "/lending/v1/applications",
                    WRITE_KEY.id,
                    WORKSPACE_ID,
                    ACTOR,
                    Instant.EPOCH,
                    method = EndpointMethod.POST,
                    endpointId = post.id,
                ),
            )
        every { apiKeys.findById(READ_KEY.id) } returns READ_KEY
        every { apiKeys.findById(WRITE_KEY.id) } returns WRITE_KEY
        every { pins.bodyContract(WORKSPACE_ID, PIPELINE, 3) } returns EndpointBodyContract(EndpointBodyContract.Mode.REQUIRED, emptyList())

        val entries = promotion.entriesFor(WORKSPACE_ID, listOf(PIPELINE), EndpointPromotion.CAPABILITIES)

        val (getEntry, postEntry) = entries.partition { it.method == null }.let { it.first.single() to it.second.single() }
        assertSoftly {
            // The GET entry is the legacy entry: no method, no pin fields, its GET tree grant by name only.
            getEntry shouldBe
                PromotionWire.EndpointEntry(
                    path = "/lending/v1/applications",
                    pipeline = PIPELINE,
                    timeoutSeconds = 30,
                    description = "",
                    bindings = listOf("reader"),
                )
            postEntry.method shouldBe "POST"
            postEntry.pipelineVersion shouldBe 3
            postEntry.pipelineBodyHash shouldBe "hash-3"
            postEntry.requestBody shouldBe "required"
            postEntry.grants shouldBe listOf("writer")
            // A method route never carries GET tree grants: the reader's GET binding is not the POST route's.
            postEntry.bindings shouldBe emptyList()
        }
    }

    @Test
    fun `a receiver that advertises nothing refuses a method route before any pin is read or anything is sent`() {
        stubSender(listOf(published("/lending/v1/applications", EndpointMethod.PUT, version = 3)))

        val refused = shouldThrow<DatapipelinesException> { promotion.entriesFor(WORKSPACE_ID, listOf(PIPELINE), null) }

        assertSoftly(refused) {
            code shouldBe PipelineErrorCodes.Endpoint.PROMOTION_METHOD_UNSUPPORTED
            message.orEmpty() shouldContain "PUT /lending/v1/applications"
            details["unsupported_routes"] shouldBe listOf(mapOf("method" to "PUT", "path" to "/lending/v1/applications"))
            details["target_advertises"] shouldBe false
        }
        verify(exactly = 0) { pins.bodyContract(any(), any(), any()) }
    }

    @Test
    fun `a receiver missing the route's method or its body mode refuses the batch, naming only the routes it cannot take`() {
        val delete = published("/lending/v1/applications/{id}", EndpointMethod.DELETE, version = 3)
        val post = published("/lending/v1/applications", EndpointMethod.POST, version = 3)
        stubSender(listOf(delete, post))
        every { pins.bodyContract(WORKSPACE_ID, PIPELINE, 3) } returns EndpointBodyContract(EndpointBodyContract.Mode.NONE, emptyList())
        val noDelete = EndpointPromotion.CAPABILITIES.copy(methods = listOf("POST", "PUT"))

        val refused = shouldThrow<DatapipelinesException> { promotion.entriesFor(WORKSPACE_ID, listOf(PIPELINE), noDelete) }

        refused.details["unsupported_routes"] shouldBe listOf(mapOf("method" to "DELETE", "path" to "/lending/v1/applications/{id}"))

        val noBodyless = EndpointPromotion.CAPABILITIES.copy(requestBodyModes = listOf("optional", "required"))
        val refusedMode = shouldThrow<DatapipelinesException> { promotion.entriesFor(WORKSPACE_ID, listOf(PIPELINE), noBodyless) }
        refusedMode.details["unsupported_routes"] shouldBe
            listOf(
                mapOf("method" to "POST", "path" to "/lending/v1/applications"),
                mapOf("method" to "DELETE", "path" to "/lending/v1/applications/{id}"),
            )
    }

    @Test
    fun `a receiver that does not take exact pins or method grants is incapable whatever methods it lists`() {
        val capabilities = EndpointPromotion.CAPABILITIES
        assertSoftly {
            capabilities.admits("POST", "required") shouldBe true
            capabilities.copy(exactPin = false).admits("POST", "required") shouldBe false
            capabilities.copy(methodGrants = false).admits("POST", "required") shouldBe false
            capabilities.admits("PATCH", "required") shouldBe false
            capabilities.admits("POST", "streamed") shouldBe false
        }
    }

    // ---------------------------------------------------------------------------------------
    // Receiver
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an exact method entry is imported on the sender's pin and its grants bound with its method`() {
        stubReceiver()

        service.apply(batch(exactEntry()), peer)

        verify(exactly = 1) {
            importer.importExact(
                WORKSPACE_ID,
                ACTOR,
                "/lending/v1/applications",
                EndpointMethod.POST,
                EndpointPromotionImport.ExactPin(PIPELINE, 3, "hash-3", 30, ""),
            )
        }
        verify(exactly = 1) { endpointKeys.bind(any(), WRITE_KEY.id, "/lending/v1/applications", EndpointMethod.POST) }
        // Never the legacy path: no current-pin import, no GET tree grant.
        verify(exactly = 0) { importer.importPromoted(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { endpointKeys.bind(any(), any(), any()) }
    }

    @Test
    fun `a method entry that cannot be imported exactly refuses the whole batch before anything is imported or bound`() {
        stubReceiver()
        val broken =
            listOf(
                exactEntry().copy(pipelineVersion = null),
                exactEntry().copy(pipelineBodyHash = " "),
                exactEntry().copy(requestBody = "streamed"),
                exactEntry().copy(bindings = listOf("writer")),
                exactEntry().copy(method = "PATCH"),
            )

        broken.forEach { entry ->
            val refused = shouldThrow<DatapipelinesException> { service.apply(batch(validGetEntry(), entry), peer) }
            refused.code shouldBe PipelineErrorCodes.Endpoint.PROMOTION_METHOD_UNSUPPORTED
            refused.details["unsupported_routes"] shouldBe listOf(mapOf("path" to "/lending/v1/applications"))
        }
        // The valid GET entry beside each broken one never landed either: the refusal is whole.
        verify(exactly = 0) { importer.importExact(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { importer.importPromoted(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { endpointKeys.bind(any(), any(), any(), any()) }
    }

    @Test
    fun `a grant naming a key the target does not have refuses the batch like a missing binding`() {
        stubReceiver()
        every { apiKeys.findByWorkspaceAndName(WORKSPACE_ID, "absent-writer") } returns emptyList()

        val refused =
            shouldThrow<DatapipelinesException> { service.apply(batch(exactEntry().copy(grants = listOf("absent-writer"))), peer) }

        refused.code shouldBe PipelineErrorCodes.Endpoint.PROMOTION_KEY_MISSING
        refused.details["missing_api_keys"] shouldBe listOf("absent-writer")
        verify(exactly = 0) { importer.importExact(any(), any(), any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------------

    private fun stubSender(routes: List<PublishedEndpoint>) {
        val record = record()
        every { pipelines.findByName(WORKSPACE_ID, PIPELINE) } returns record
        every { pipelines.findVersionDetail(WORKSPACE_ID, record.id, any()) } answers {
            val version = thirdArg<Int>()
            PipelineVersionDetail(record.id, version, PipelineVersionStatus.RELEASED, "hash-$version", Instant.EPOCH, ACTOR)
        }
        every { endpoints.findLegacy(WORKSPACE_ID) } returns emptyList()
        every { endpoints.findByWorkspace(WORKSPACE_ID) } returns routes
        every { bindings.findByWorkspace(WORKSPACE_ID) } returns emptyList()
    }

    private fun stubReceiver() {
        every { inventory.contextFor(WORKSPACE_NAME) } returns WorkspaceContext(WORKSPACE_ID, WORKSPACE_NAME)
        every { apiKeys.findByWorkspaceAndName(WORKSPACE_ID, "writer") } returns listOf(WRITE_KEY)
        every { apiKeys.findByWorkspaceAndName(WORKSPACE_ID, "reader") } returns listOf(READ_KEY)
        every { publishing.get(any(), any()) } returns null
    }

    private fun exactEntry() =
        PromotionWire.EndpointEntry(
            path = "/lending/v1/applications",
            pipeline = PIPELINE,
            timeoutSeconds = 30,
            method = "POST",
            pipelineVersion = 3,
            pipelineBodyHash = "hash-3",
            requestBody = "required",
            grants = listOf("writer"),
        )

    private fun validGetEntry() =
        PromotionWire.EndpointEntry(path = "/lending/v1/home", pipeline = PIPELINE, timeoutSeconds = 30, bindings = listOf("reader"))

    private fun batch(vararg entries: PromotionWire.EndpointEntry) =
        PromotionWire.Batch(sourceEnv = "dev", keyFingerprint = "abcd1234", workspace = WORKSPACE_NAME, endpoints = entries.toList())

    private val peer =
        AuthenticatedPrincipal(
            userId = ACTOR,
            email = "system@datapipelines.test",
            displayName = "System",
            authMethod = AuthMethod.PROMOTION,
            keyRole = KeyRole.PROMOTION_RECEIVER,
        )

    private fun published(
        path: String,
        method: EndpointMethod,
        version: Int,
    ) = PublishedEndpoint.of(
        id = UUID.randomUUID(),
        workspaceId = WORKSPACE_ID,
        pathPattern = path,
        isEnabled = true,
        createdBy = ACTOR,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        current = CurrentVersion(1, PipelineVersionStatus.RELEASED, EndpointVersionBody(PIPELINE, version, 30, "")),
        method = method,
    )

    private fun record() =
        PipelineRecord(
            id = PIPELINE_ID,
            name = PIPELINE,
            displayName = PIPELINE,
            description = "",
            ownerId = ACTOR,
            currentVersion = 3,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private companion object {
        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val PIPELINE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-0000000000f1")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        const val WORKSPACE_NAME = "default"
        const val PIPELINE = "lending/applications"
        val READ_KEY = key("dpk_RRRRRRRRRRRR", "reader")
        val WRITE_KEY = key("dpk_WWWWWWWWWWWW", "writer")

        fun key(
            id: String,
            name: String,
        ) = ApiKey(
            id = id,
            userId = ACTOR,
            name = name,
            keyHash = "hash",
            isRevoked = false,
            createdAt = Instant.EPOCH,
            lastUsedAt = null,
            expiresAt = null,
            workspaceId = WORKSPACE_ID,
            workspaceName = WORKSPACE_NAME,
            kind = ApiKeyKind.ENDPOINT,
        )
    }
}
