package co.datapipelines.web.bootstrap

import co.datapipelines.application.endpoints.EndpointKeyBinding
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.SecretHasher
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.Workspace
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.config.KeyRequestBudget
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The demo endpoint seeder (#224), unit-tested against recording mocks of the three services it
 * must go THROUGH (the publish service, the key service, the key revocation) and of the reads it
 * reconciles against. What is pinned here is the DECISIONING — what gets published, minted,
 * revoked, retired and rebound, and what is deliberately left alone; the rows-in-the-database
 * proof is [co.datapipelines.integration.DemoApiKeyE2eTest]'s.
 */
class DemoEndpointSeederTest {
    private val demoId = UUID.fromString("de000000-0000-0000-0000-000000000001")
    private val systemActorId = UUID.fromString("00000000-0000-0000-0000-00000000d001")

    private val key = "dpk_DEMOPUBLIC42.THISKEYISPUBLICBYDESIGNDEMOONLYREADONLY222222222"

    private val pipelineRepository = mockk<PipelineRepository>()
    private val publishService = mockk<EndpointPublishService>()
    private val keyService = mockk<EndpointKeyService>()
    private val endpointRepository = mockk<PublishedEndpointRepository>()
    private val bindingRepository = mockk<co.datapipelines.application.endpoints.EndpointKeyBindingRepository>()
    private val keyRepository = mockk<ApiKeyRepository>()
    private val apiKeyService = mockk<ApiKeyService>()
    private val secretHasher = mockk<SecretHasher>()
    private val users = mockk<UserService>()

    private val examples = mockk<ExampleContentSeeder>()
    private val demoWorkspaceSeeder = mockk<co.datapipelines.auth.DemoWorkspaceSeeder>()

    private fun properties(demoApiKey: String? = key) = BootstrapProperties(demoApiKey = demoApiKey)

    private fun endpointsProperties(maxRequests: Int = 60) =
        EndpointsProperties(keyRequestBudget = KeyRequestBudget(windowSeconds = 60, maxRequests = maxRequests))

    private fun seeder(
        demoApiKey: String? = key,
        maxRequests: Int = 60,
    ): DemoEndpointSeeder =
        DemoEndpointSeeder(
            properties(demoApiKey),
            endpointsProperties(maxRequests),
            examples,
            demoWorkspaceSeeder,
            pipelineRepository,
            publishService,
            keyService,
            bindingRepository,
            endpointRepository,
            keyRepository,
            apiKeyService,
            secretHasher,
            users,
        )

    private fun workspace(): Workspace =
        Workspace(demoId, "demo", "Demo", isPersonal = false, createdBy = null, isDeleted = false, createdAt = Instant.now())

    private fun activeDemoWorkspace() {
        every { demoWorkspaceSeeder.ensureDemoWorkspace() } returns workspace()
    }

    private fun systemActor(): User =
        User(
            id = systemActorId,
            email = "system@system.invalid",
            displayName = "System",
            provider = "system",
            providerSubject = "system",
            isActive = true,
            isAdmin = false,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    /** Two seedable pipelines: one nyc, one lake-backed — the shipped demo's shape in miniature. */
    private fun seedable() {
        every { examples.seedablePipelines(demoId) } returns
            listOf(
                ExampleContentSeeder.SeedablePipeline("nyc/mobility/revenue_by_borough", lakeBacked = false),
                ExampleContentSeeder.SeedablePipeline("nyc/mobility/taxi_vs_rideshare", lakeBacked = true),
            )
    }

    private fun pipelineFor(name: String): PipelineRecord =
        mockk {
            every { id } returns UUID.nameUUIDFromBytes(name.toByteArray())
            every { this@mockk.name } returns name
        }

    private fun endpoint(
        path: String,
        pipeline: UUID,
        createdBy: UUID = systemActorId,
    ): PublishedEndpoint =
        PublishedEndpoint.of(
            id = UUID.nameUUIDFromBytes(path.toByteArray()),
            workspaceId = demoId,
            pathPattern = path,
            pipelineId = pipeline,
            timeoutSeconds = 30,
            description = "d",
            isEnabled = true,
            createdBy = createdBy,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun issued(plaintext: String): co.datapipelines.auth.IssuedApiKey =
        co.datapipelines.auth.IssuedApiKey(
            record =
                ApiKey(
                    id = plaintext.substringBefore('.'),
                    userId = UUID.randomUUID(),
                    name = "demo-public-key",
                    keyHash = "hash",
                    isRevoked = false,
                    createdAt = Instant.now(),
                    lastUsedAt = null,
                    expiresAt = null,
                    workspaceId = demoId,
                    workspaceName = "demo",
                    kind = ApiKeyKind.ENDPOINT,
                ),
            plaintext = plaintext,
        )

    /** The common happy-path stubs: an active demo workspace, the system actor, both pipelines present. */
    private fun happyStubs() {
        activeDemoWorkspace()
        every { users.provisionSystemActor() } returns systemActor()
        seedable()
        every { pipelineRepository.findByName(demoId, "nyc/mobility/revenue_by_borough") } returns
            pipelineFor("nyc/mobility/revenue_by_borough")
        every { pipelineRepository.findByName(demoId, "nyc/mobility/taxi_vs_rideshare") } returns
            pipelineFor("nyc/mobility/taxi_vs_rideshare")
        every { endpointRepository.findByPath(any()) } returns null
        every { endpointRepository.findByWorkspace(demoId) } returns emptyList()
        every { keyRepository.findByWorkspaceAndName(demoId, "demo-public-key") } returns emptyList()
        every { keyRepository.findById(any()) } returns null
        every { publishService.publish(any(), any(), any(), any(), any()) } returns mockk()
        every { publishService.unpublish(any(), any()) } returns true
        every { keyService.bind(any(), any(), any()) } returns true
        every { keyService.unbind(any(), any(), any()) } returns true
        every {
            keyService.issue(any(), any(), any(), any(), any(), any(), any())
        } answers { issued(arg(6)) }
    }

    @Test
    fun `seeding publishes one endpoint per seeded pipeline and mints the key bound to all of them`() {
        happyStubs()
        seeder().afterSingletonsInstantiated()

        val publishedPaths = mutableListOf<String>()
        verify(exactly = 2) { publishService.publish(any(), capture(publishedPaths), any(), any(), any()) }
        publishedPaths shouldBe listOf("/demo/nyc/mobility/revenue-by-borough", "/demo/nyc/mobility/taxi-vs-rideshare")

        verify(exactly = 1) {
            keyService.issue(
                any(),
                "demo-public-key",
                null,
                ApiKeyKind.ENDPOINT,
                listOf("/demo/nyc/mobility/revenue-by-borough", "/demo/nyc/mobility/taxi-vs-rideshare"),
                null,
                key,
            )
        }
        verify(exactly = 0) { apiKeyService.revokeWorkspaceEndpointKey(any(), any(), any()) }
    }

    @Test
    fun `an idempotent re-run publishes nothing, revokes nothing and rebinds nothing`() {
        happyStubs()
        val existingPipelineId = UUID.nameUUIDFromBytes("nyc/mobility/revenue_by_borough".toByteArray())
        val lakePipelineId = UUID.nameUUIDFromBytes("nyc/mobility/taxi_vs_rideshare".toByteArray())
        every { endpointRepository.findByPath("/demo/nyc/mobility/revenue-by-borough") } returns
            endpoint("/demo/nyc/mobility/revenue-by-borough", existingPipelineId)
        every { endpointRepository.findByPath("/demo/nyc/mobility/taxi-vs-rideshare") } returns
            endpoint("/demo/nyc/mobility/taxi-vs-rideshare", lakePipelineId)
        val managed = issued(key).record
        every { keyRepository.findByWorkspaceAndName(demoId, "demo-public-key") } returns listOf(managed)
        every { secretHasher.verify(managed.keyHash, key) } returns true
        every { bindingRepository.findByKey(managed.id) } returns
            listOf(
                binding("/demo/nyc/mobility/revenue-by-borough", managed.id),
                binding("/demo/nyc/mobility/taxi-vs-rideshare", managed.id),
            )

        seeder().afterSingletonsInstantiated()

        verify(exactly = 0) { publishService.publish(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { keyService.issue(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { apiKeyService.revokeWorkspaceEndpointKey(any(), any(), any()) }
        verify(exactly = 0) { keyService.bind(any(), any(), any()) }
        verify(exactly = 0) { keyService.unbind(any(), any(), any()) }
    }

    @Test
    fun `a changed key rotates - the old key is revoked, the configured one is minted and bound`() {
        happyStubs()
        val oldKey =
            ApiKey(
                id = "dpk_ROTATEDOLDA1",
                userId = UUID.randomUUID(),
                name = "demo-public-key",
                keyHash = "old-hash",
                isRevoked = false,
                createdAt = Instant.now(),
                lastUsedAt = null,
                expiresAt = null,
                workspaceId = demoId,
                workspaceName = "demo",
                kind = ApiKeyKind.ENDPOINT,
            )
        every { keyRepository.findByWorkspaceAndName(demoId, "demo-public-key") } returns listOf(oldKey)
        every { apiKeyService.revokeWorkspaceEndpointKey(oldKey.id, demoId, systemActorId) } returns true

        seeder(demoApiKey = key).afterSingletonsInstantiated()

        verify(exactly = 1) { apiKeyService.revokeWorkspaceEndpointKey(oldKey.id, demoId, systemActorId) }
        verify(exactly = 1) {
            keyService.issue(any(), "demo-public-key", null, ApiKeyKind.ENDPOINT, any(), null, key)
        }
    }

    @Test
    fun `the kill switch retracts the seeder's own rows and revokes the managed key`() {
        happyStubs()
        val stalePath = "/demo/nyc/mobility/revenue-by-borough"
        val foreignPath = "/demo/custom/v1/thing"
        every { endpointRepository.findByWorkspace(demoId) } returns
            listOf(endpoint(stalePath, UUID.randomUUID()), endpoint(foreignPath, UUID.randomUUID(), createdBy = UUID.randomUUID()))
        every { publishService.unpublish(any(), stalePath) } returns true
        val managed = issued(key).record
        every { keyRepository.findByWorkspaceAndName(demoId, "demo-public-key") } returns listOf(managed)
        every { apiKeyService.revokeWorkspaceEndpointKey(managed.id, demoId, systemActorId) } returns true

        seeder(demoApiKey = "").afterSingletonsInstantiated()

        // The seeder's own row goes; a super admin's hand-published /demo row does not.
        verify(exactly = 1) { publishService.unpublish(any(), stalePath) }
        verify(exactly = 0) { publishService.unpublish(any(), foreignPath) }
        verify(exactly = 1) { apiKeyService.revokeWorkspaceEndpointKey(managed.id, demoId, systemActorId) }
        verify(exactly = 0) { keyService.issue(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { publishService.publish(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the lake pipeline is not published when the budget is off, and is when it is on`() {
        happyStubs()
        seeder(maxRequests = 0).afterSingletonsInstantiated()

        val publishedPaths = mutableListOf<String>()
        verify(exactly = 1) { publishService.publish(any(), capture(publishedPaths), any(), any(), any()) }
        publishedPaths shouldBe listOf("/demo/nyc/mobility/revenue-by-borough")
        verify(exactly = 1) {
            keyService.issue(
                any(),
                "demo-public-key",
                null,
                ApiKeyKind.ENDPOINT,
                listOf("/demo/nyc/mobility/revenue-by-borough"),
                null,
                key,
            )
        }
    }

    @Test
    fun `a changed pipeline set retires the seeder's own stale endpoint and the binding goes with it`() {
        happyStubs()
        val stalePipeline = UUID.randomUUID()
        val stale = endpoint("/demo/nyc/mobility/gone_away", stalePipeline)
        every { endpointRepository.findByWorkspace(demoId) } returns listOf(stale)
        every { publishService.unpublish(any(), "/demo/nyc/mobility/gone_away") } returns true
        val managed = issued(key).record
        every { keyRepository.findByWorkspaceAndName(demoId, "demo-public-key") } returns listOf(managed)
        every { secretHasher.verify(managed.keyHash, key) } returns true
        every { bindingRepository.findByKey(managed.id) } returns
            listOf(binding("/demo/nyc/mobility/gone_away", managed.id))

        seeder().afterSingletonsInstantiated()

        verify(exactly = 1) { publishService.unpublish(any(), "/demo/nyc/mobility/gone_away") }
        verify(exactly = 1) { keyService.unbind(any(), managed.id, "/demo/nyc/mobility/gone_away") }
    }

    @Test
    fun `an existing endpoint at a wanted path held by a DIFFERENT pipeline is left alone`() {
        happyStubs()
        every { endpointRepository.findByPath("/demo/nyc/mobility/revenue-by-borough") } returns
            endpoint("/demo/nyc/mobility/revenue-by-borough", UUID.randomUUID(), createdBy = UUID.randomUUID())

        seeder().afterSingletonsInstantiated()

        verify(exactly = 0) { publishService.publish(any(), "/demo/nyc/mobility/revenue-by-borough", any(), any(), any()) }
        // The other pipeline still publishes; the boot is not failed by someone else's URL.
        verify(exactly = 1) { publishService.publish(any(), "/demo/nyc/mobility/taxi-vs-rideshare", any(), any(), any()) }
    }

    @Test
    fun `no seeded pipelines means no key - the public key exists only while demo endpoints do`() {
        happyStubs()
        every { examples.seedablePipelines(demoId) } returns emptyList()
        val managed = issued(key).record
        every { keyRepository.findByWorkspaceAndName(demoId, "demo-public-key") } returns listOf(managed)
        every { apiKeyService.revokeWorkspaceEndpointKey(managed.id, demoId, systemActorId) } returns true

        seeder().afterSingletonsInstantiated()

        verify(exactly = 0) { keyService.issue(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { apiKeyService.revokeWorkspaceEndpointKey(managed.id, demoId, systemActorId) }
    }

    @Test
    fun `the path mapping is one rule - the seeder and the site cannot disagree`() {
        DemoEndpointPaths.pathFor("nyc/mobility/taxi_vs_rideshare") shouldBe "/demo/nyc/mobility/taxi-vs-rideshare"
        DemoEndpointPaths.pathFor("nyc/mobility/revenue_by_borough") shouldBe "/demo/nyc/mobility/revenue-by-borough"
        DemoEndpointPaths.pathFor("trade/balance_by_partner") shouldBe "/demo/trade/balance-by-partner"
        DemoEndpointPaths.pathFor("trade/fx/imports_in_partner_currency") shouldBe "/demo/trade/fx/imports-in-partner-currency"
    }

    private fun binding(
        prefix: String,
        keyId: String,
    ): EndpointKeyBinding =
        EndpointKeyBinding(
            pathPrefix = prefix,
            apiKeyId = keyId,
            workspaceId = demoId,
            createdBy = systemActorId,
            createdAt = Instant.now(),
        )
}
