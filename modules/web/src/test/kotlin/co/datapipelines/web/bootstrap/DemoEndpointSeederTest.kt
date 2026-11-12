package co.datapipelines.web.bootstrap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import co.datapipelines.application.endpoints.CurrentVersion
import co.datapipelines.application.endpoints.EndpointKeyBinding
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.EndpointVersionService
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.PublishedEndpointVersion
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.SecretHasher
import co.datapipelines.auth.SecretVerificationUnavailableException
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.Workspace
import co.datapipelines.logcapture.capturingLogEvents
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.config.KeyRequestBudget
import co.datapipelines.web.release.ReleaseCoordinator
import co.datapipelines.web.release.RootReleaseRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * The demo endpoint seeder (#224), unit-tested against recording mocks of the three services it
 * must go THROUGH (the publish service, the key service, the key revocation) and of the reads it
 * reconciles against. What is pinned here is the DECISIONING — what gets published, minted,
 * revoked, retired and rebound, and what is deliberately left alone; the rows-in-the-database
 * proof is [co.datapipelines.integration.DemoApiKeyE2eTest]'s.
 */
@Suppress("LargeClass") // one seeder contract: publish, key reconciliation, retirement, deferral and revival share one fixture
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

    /** #786 A.5 — the version reads the release consents with, and the coordinator v1 is released through. */
    private val endpointVersions = mockk<PublishedEndpointVersionRepository>()
    private val releases = mockk<ReleaseCoordinator>()

    /** #889 — the API version verbs retirement and revival go through. */
    private val versionService = mockk<EndpointVersionService>()

    private val examples = mockk<ExampleContentSeeder>()
    private val demoWorkspaceSeeder = mockk<co.datapipelines.auth.DemoWorkspaceSeeder>()

    private fun properties(demoApiKey: String? = key) = BootstrapProperties(demoApiKey = demoApiKey)

    private fun endpointsProperties(maxRequests: Int = 60) =
        EndpointsProperties(keyRequestBudget = KeyRequestBudget(windowSeconds = 60, maxRequests = maxRequests))

    private fun seeder(
        demoApiKey: String? = key,
        maxRequests: Int = 60,
        authoringEnabled: Boolean = true,
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
            AuthoringGuard(authoringEnabled),
            endpointVersions,
            releases,
            versionService,
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
            every { currentVersion } returns 1
        }

    /** The seeded pipeline's current version 1 at [status] — what the seeder pins, and only when RELEASED (#786 A.5). */
    private fun currentIs(
        name: String,
        status: PipelineVersionStatus,
    ) {
        val id = UUID.nameUUIDFromBytes(name.toByteArray())
        every { pipelineRepository.findById(demoId, id) } returns pipelineFor(name)
        every { pipelineRepository.findVersionDetail(demoId, id, 1) } returns
            PipelineVersionDetail(
                pipelineId = id,
                version = 1,
                status = status,
                bodyHash = "pipeline-hash",
                createdAt = Instant.EPOCH,
                createdBy = systemActorId,
            )
    }

    /** The working draft creation just wrote for the endpoint at [path], with its own body hash. */
    private fun draftOf(path: String): PublishedEndpointVersion =
        PublishedEndpointVersion(
            endpointId = UUID.nameUUIDFromBytes(path.toByteArray()),
            version = 1,
            status = PipelineVersionStatus.DRAFT,
            body = EndpointVersionBody("p", 1, 30, "d"),
            bodyHash = "draft-hash-$path",
            createdAt = Instant.EPOCH,
            createdBy = systemActorId,
            releasedAt = null,
            releasedBy = null,
        )

    /** Version [number] of the API at [path] at [status], with a hash naming both. */
    private fun versionOf(
        path: String,
        number: Int,
        status: PipelineVersionStatus,
    ): PublishedEndpointVersion = draftOf(path).copy(version = number, status = status, bodyHash = "hash-$path-v$number")

    /** #889 — what the API at [path] holds, newest first (the repository's order). */
    private fun holds(
        path: String,
        vararg statuses: PipelineVersionStatus,
    ): List<PublishedEndpointVersion> {
        val held = statuses.mapIndexed { i, status -> versionOf(path, i + 1, status) }.reversed()
        every { endpointVersions.list(UUID.nameUUIDFromBytes(path.toByteArray())) } returns held
        return held
    }

    private fun endpoint(
        path: String,
        pipelineName: String,
        createdBy: UUID = systemActorId,
        status: PipelineVersionStatus? = PipelineVersionStatus.RELEASED,
        currentVersion: Int = 1,
    ): PublishedEndpoint =
        PublishedEndpoint.of(
            id = UUID.nameUUIDFromBytes(path.toByteArray()),
            workspaceId = demoId,
            pathPattern = path,
            isEnabled = true,
            createdBy = createdBy,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            current = status?.let { CurrentVersion(currentVersion, it, EndpointVersionBody(pipelineName, 1, 30, "d")) },
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
        currentIs("nyc/mobility/revenue_by_borough", PipelineVersionStatus.RELEASED)
        currentIs("nyc/mobility/taxi_vs_rideshare", PipelineVersionStatus.RELEASED)
        every { publishService.create(any(), any(), any(), any(), any(), any()) } answers {
            endpoint(secondArg(), thirdArg(), status = PipelineVersionStatus.DRAFT)
        }
        every { endpointVersions.findDraft(any()) } answers {
            val id = firstArg<UUID>()
            listOf("/demo/nyc/mobility/revenue-by-borough", "/demo/nyc/mobility/taxi-vs-rideshare")
                .map(::draftOf)
                .firstOrNull { it.endpointId == id }
        }
        every { releases.releaseRoot(any()) } returns mockk()
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
        verify(exactly = 2) { publishService.create(any(), capture(publishedPaths), any(), any(), any(), any()) }
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
        every { endpointRepository.findByPath("/demo/nyc/mobility/revenue-by-borough") } returns
            endpoint("/demo/nyc/mobility/revenue-by-borough", "nyc/mobility/revenue_by_borough")
        every { endpointRepository.findByPath("/demo/nyc/mobility/taxi-vs-rideshare") } returns
            endpoint("/demo/nyc/mobility/taxi-vs-rideshare", "nyc/mobility/taxi_vs_rideshare")
        val managed = issued(key).record
        every { keyRepository.findByWorkspaceAndName(demoId, "demo-public-key") } returns listOf(managed)
        every { secretHasher.verify(managed.keyHash, key) } returns true
        every { bindingRepository.findByKey(managed.id) } returns
            listOf(
                binding("/demo/nyc/mobility/revenue-by-borough", managed.id),
                binding("/demo/nyc/mobility/taxi-vs-rideshare", managed.id),
            )

        seeder().afterSingletonsInstantiated()

        verify(exactly = 0) { publishService.create(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { releases.releaseRoot(any()) }
        verify(exactly = 0) { keyService.issue(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { apiKeyService.revokeWorkspaceEndpointKey(any(), any(), any()) }
        verify(exactly = 0) { keyService.bind(any(), any(), any()) }
        verify(exactly = 0) { keyService.unbind(any(), any(), any()) }
    }

    /**
     * #847 — a native verification fault on the managed key is NOT "the hash does not match": the
     * public demo key must not be silently revoked and re-minted by a server fault. The exception
     * propagates and fails the boot instead; nothing is revoked, minted or rebound. The live row
     * carries the CONFIGURED id, or the id-gated `verify` would never run.
     */
    @Test
    fun `a native verification fault fails the boot instead of rotating the public demo key`() {
        happyStubs()
        every { endpointRepository.findByPath("/demo/nyc/mobility/revenue-by-borough") } returns
            endpoint("/demo/nyc/mobility/revenue-by-borough", "nyc/mobility/revenue_by_borough")
        every { endpointRepository.findByPath("/demo/nyc/mobility/taxi-vs-rideshare") } returns
            endpoint("/demo/nyc/mobility/taxi-vs-rideshare", "nyc/mobility/taxi_vs_rideshare")
        val managed = issued(key).record
        managed.id shouldBe key.substringBefore('.')
        every { keyRepository.findByWorkspaceAndName(demoId, "demo-public-key") } returns listOf(managed)
        every { secretHasher.verify(managed.keyHash, key) } throws SecretVerificationUnavailableException(-22, null)

        shouldThrow<SecretVerificationUnavailableException> { seeder().afterSingletonsInstantiated() }

        verify(exactly = 1) { secretHasher.verify(managed.keyHash, key) }
        verify(exactly = 0) { apiKeyService.revokeWorkspaceEndpointKey(any(), any(), any()) }
        verify(exactly = 0) { keyService.issue(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { keyService.bind(any(), any(), any()) }
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
    fun `the kill switch retires the seeder's own released API through the version verbs and revokes the managed key`() {
        happyStubs()
        val stalePath = "/demo/nyc/mobility/revenue-by-borough"
        val foreignPath = "/demo/custom/v1/thing"
        every { endpointRepository.findByWorkspace(demoId) } returns
            listOf(
                endpoint(stalePath, "nyc/mobility/revenue_by_borough"),
                endpoint(foreignPath, "custom/thing", createdBy = UUID.randomUUID()),
            )
        holds(stalePath, PipelineVersionStatus.RELEASED)
        holds(foreignPath, PipelineVersionStatus.RELEASED)
        val staleId = UUID.nameUUIDFromBytes(stalePath.toByteArray())
        val foreignId = UUID.nameUUIDFromBytes(foreignPath.toByteArray())
        every { versionService.discardVersion(any(), staleId, 1) } returns EndpointVersionService.Moved(1, 1, null)
        val managed = issued(key).record
        every { keyRepository.findByWorkspaceAndName(demoId, "demo-public-key") } returns listOf(managed)
        every { apiKeyService.revokeWorkspaceEndpointKey(managed.id, demoId, systemActorId) } returns true

        seeder(demoApiKey = "").afterSingletonsInstantiated()

        // #889 — the seeder's own released API is discarded, never deleted around the guard; a super admin's
        // hand-published /demo row is not touched at all.
        verify(exactly = 1) { versionService.discardVersion(any(), staleId, 1) }
        verify(exactly = 0) { publishService.unpublish(any(), any()) }
        verify(exactly = 0) { versionService.discardVersion(any(), foreignId, any()) }
        verify(exactly = 0) { versionService.discardDraft(any(), foreignId, any()) }
        verify(exactly = 1) { apiKeyService.revokeWorkspaceEndpointKey(managed.id, demoId, systemActorId) }
        verify(exactly = 0) { keyService.issue(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { publishService.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the lake pipeline is not published when the budget is off, and is when it is on`() {
        happyStubs()
        seeder(maxRequests = 0).afterSingletonsInstantiated()

        val publishedPaths = mutableListOf<String>()
        verify(exactly = 1) { publishService.create(any(), capture(publishedPaths), any(), any(), any(), any()) }
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
    fun `a changed pipeline set deletes the seeder's own draft-only stale endpoint and the binding goes with it`() {
        happyStubs()
        val stale = endpoint("/demo/nyc/mobility/gone_away", "nyc/mobility/gone_away", status = PipelineVersionStatus.DRAFT)
        every { endpointRepository.findByWorkspace(demoId) } returns listOf(stale)
        holds("/demo/nyc/mobility/gone_away", PipelineVersionStatus.DRAFT)
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
            endpoint("/demo/nyc/mobility/revenue-by-borough", "someone/else", createdBy = UUID.randomUUID())

        seeder().afterSingletonsInstantiated()

        verify(exactly = 0) { publishService.create(any(), "/demo/nyc/mobility/revenue-by-borough", any(), any(), any(), any()) }
        // The other pipeline still publishes; the boot is not failed by someone else's URL.
        verify(exactly = 1) { publishService.create(any(), "/demo/nyc/mobility/taxi-vs-rideshare", any(), any(), any(), any()) }
    }

    @Test
    fun `a failing publish is one skipped target - the next endpoint still publishes and the boot proceeds`() {
        // #274: a demo seed is never worth a refused context. On the base, the throw below
        // escaped afterSingletonsInstantiated and killed the whole application start; here it
        // is one ERROR line and the remaining targets proceed.
        happyStubs()
        every {
            publishService.create(any(), "/demo/nyc/mobility/revenue-by-borough", any(), any(), any(), any())
        } throws RuntimeException("the conflict check exploded")

        seeder().afterSingletonsInstantiated()

        verify(exactly = 1) { publishService.create(any(), "/demo/nyc/mobility/taxi-vs-rideshare", any(), any(), any(), any()) }
        verify(exactly = 1) { publishService.create(any(), "/demo/nyc/mobility/revenue-by-borough", any(), any(), any(), any()) }
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

    // ------------------------------------------------------------------ #786 A.5, the capability decision

    @Test
    fun `each created v1 is released through the coordinator as the system actor, consenting to its own draft hash`() {
        happyStubs()
        val requests = mutableListOf<RootReleaseRequest>()
        every { releases.releaseRoot(capture(requests)) } returns mockk()

        seeder().afterSingletonsInstantiated()

        // Pinned to the pipeline's RELEASED current, exactly (version 1), never "latest".
        verify(exactly = 1) {
            publishService.create(any(), "/demo/nyc/mobility/revenue-by-borough", "nyc/mobility/revenue_by_borough", 1, null, any())
        }
        assertAll(
            { requests.map { it.family }.distinct() shouldBe listOf(ReleaseFamily.ENDPOINT) },
            {
                requests.map { it.expectedHash } shouldBe
                    listOf("draft-hash-/demo/nyc/mobility/revenue-by-borough", "draft-hash-/demo/nyc/mobility/taxi-vs-rideshare")
            },
            { requests.map { it.actor }.distinct() shouldBe listOf(systemActorId) },
            // The boot has no request to read a principal from: the system actor is named explicitly.
            { requests.map { it.principal?.userId }.distinct() shouldBe listOf(systemActorId) },
            { requests.map { it.plan } shouldBe listOf(null, null) },
        )
    }

    @Test
    fun `an authoring-disabled deployment creates and releases nothing, and still retires and reconciles the key`() {
        // The wanted set is NOT the decision: it is non-empty here, and the capability still says no.
        happyStubs()
        val stale = endpoint("/demo/nyc/mobility/gone_away", "nyc/mobility/gone_away", status = PipelineVersionStatus.DRAFT)
        every { endpointRepository.findByWorkspace(demoId) } returns listOf(stale)
        holds("/demo/nyc/mobility/gone_away", PipelineVersionStatus.DRAFT)

        val lines = logLines { seeder(authoringEnabled = false).afterSingletonsInstantiated() }

        assertAll(
            // One explicit capability line, and no refusal: nothing was attempted, so nothing failed.
            { lines.count { it.second.startsWith("event=demo.endpoints_authoring_disabled") } shouldBe 1 },
            { lines.filter { it.first.isGreaterOrEqual(Level.WARN) } shouldBe emptyList() },
            { verify(exactly = 0) { publishService.create(any(), any(), any(), any(), any(), any()) } },
            { verify(exactly = 0) { releases.releaseRoot(any()) } },
            { verify(exactly = 0) { endpointVersions.findDraft(any()) } },
            // Retirement and key reconciliation run exactly as before.
            { verify(exactly = 1) { publishService.unpublish(any(), "/demo/nyc/mobility/gone_away") } },
            { verify(exactly = 1) { keyService.issue(any(), "demo-public-key", null, ApiKeyKind.ENDPOINT, any(), null, key) } },
        )
    }

    @Test
    fun `an authoring-disabled deployment with nothing wanted retracts the key and retires exactly as before`() {
        happyStubs()
        every { examples.seedablePipelines(demoId) } returns emptyList()
        val stale = endpoint("/demo/nyc/mobility/gone_away", "nyc/mobility/gone_away", status = PipelineVersionStatus.DRAFT)
        every { endpointRepository.findByWorkspace(demoId) } returns listOf(stale)
        holds("/demo/nyc/mobility/gone_away", PipelineVersionStatus.DRAFT)
        val managed = issued(key).record
        every { keyRepository.findByWorkspaceAndName(demoId, "demo-public-key") } returns listOf(managed)
        every { apiKeyService.revokeWorkspaceEndpointKey(managed.id, demoId, systemActorId) } returns true

        val lines = logLines { seeder(authoringEnabled = false).afterSingletonsInstantiated() }

        assertAll(
            { verify(exactly = 1) { publishService.unpublish(any(), "/demo/nyc/mobility/gone_away") } },
            { verify(exactly = 1) { apiKeyService.revokeWorkspaceEndpointKey(managed.id, demoId, systemActorId) } },
            { verify(exactly = 0) { keyService.issue(any(), any(), any(), any(), any(), any(), any()) } },
            { verify(exactly = 0) { publishService.create(any(), any(), any(), any(), any(), any()) } },
            // The empty branch decides first: the capability line belongs to a NON-empty wanted set only.
            {
                lines.map { it.second.substringBefore(' ') } shouldBe
                    listOf("event=demo.endpoint_retired", "event=demo.api_key_revoked", "event=demo.api_key_no_endpoints")
            },
        )
    }

    @Test
    fun `a seeded pipeline whose current is not RELEASED gets no endpoint, and the other still does`() {
        happyStubs()
        currentIs("nyc/mobility/revenue_by_borough", PipelineVersionStatus.DRAFT)

        seeder().afterSingletonsInstantiated()

        assertAll(
            { verify(exactly = 0) { publishService.create(any(), "/demo/nyc/mobility/revenue-by-borough", any(), any(), any(), any()) } },
            { verify(exactly = 1) { publishService.create(any(), "/demo/nyc/mobility/taxi-vs-rideshare", any(), any(), any(), any()) } },
            { verify(exactly = 1) { releases.releaseRoot(any()) } },
        )
    }

    @Test
    fun `our own endpoint whose current is still the DRAFT a previous boot created is released, not re-created`() {
        happyStubs()
        every { endpointRepository.findByPath("/demo/nyc/mobility/revenue-by-borough") } returns
            endpoint("/demo/nyc/mobility/revenue-by-borough", "nyc/mobility/revenue_by_borough", status = PipelineVersionStatus.DRAFT)
        every { endpointRepository.findByPath("/demo/nyc/mobility/taxi-vs-rideshare") } returns
            endpoint("/demo/nyc/mobility/taxi-vs-rideshare", "nyc/mobility/taxi_vs_rideshare")
        val requests = mutableListOf<RootReleaseRequest>()
        every { releases.releaseRoot(capture(requests)) } returns mockk()

        seeder().afterSingletonsInstantiated()

        assertAll(
            { verify(exactly = 0) { publishService.create(any(), any(), any(), any(), any(), any()) } },
            { requests.map { it.entityId } shouldBe listOf(UUID.nameUUIDFromBytes("/demo/nyc/mobility/revenue-by-borough".toByteArray())) },
        )
    }

    @Test
    fun `the path mapping is one rule - the seeder and the site cannot disagree`() {
        DemoEndpointPaths.pathFor("nyc/mobility/taxi_vs_rideshare") shouldBe "/demo/nyc/mobility/taxi-vs-rideshare"
        DemoEndpointPaths.pathFor("nyc/mobility/revenue_by_borough") shouldBe "/demo/nyc/mobility/revenue-by-borough"
        DemoEndpointPaths.pathFor("trade/balance_by_partner") shouldBe "/demo/trade/balance-by-partner"
        DemoEndpointPaths.pathFor("trade/fx/imports_in_partner_currency") shouldBe "/demo/trade/fx/imports-in-partner-currency"
    }

    // ------------------------------------------------------------------ #889, retirement through the version verbs

    @Test
    fun `a stale API with release history keeps its row - the draft is purged, then every RELEASED version discarded oldest first`() {
        happyStubs()
        val path = "/demo/nyc/mobility/gone_away"
        val id = UUID.nameUUIDFromBytes(path.toByteArray())
        every { endpointRepository.findByWorkspace(demoId) } returns
            listOf(endpoint(path, "nyc/mobility/gone_away", status = PipelineVersionStatus.DRAFT, currentVersion = 4))
        holds(
            path,
            PipelineVersionStatus.RELEASED,
            PipelineVersionStatus.DISCARDED,
            PipelineVersionStatus.RELEASED,
            PipelineVersionStatus.DRAFT,
        )
        every { versionService.discardDraft(any(), id, "hash-$path-v4") } returns EndpointVersionService.Purged(4, entityDeleted = false)
        every { versionService.discardVersion(any(), id, 1) } returns EndpointVersionService.Moved(1, 3, 3)
        every { versionService.discardVersion(any(), id, 3) } returns EndpointVersionService.Moved(3, 3, null)

        val lines = logLines { seeder().afterSingletonsInstantiated() }

        assertAll(
            {
                verifyOrder {
                    versionService.discardDraft(any(), id, "hash-$path-v4")
                    versionService.discardVersion(any(), id, 1)
                    versionService.discardVersion(any(), id, 3)
                }
            },
            { verify(exactly = 0) { versionService.discardVersion(any(), id, 2) } },
            { verify(exactly = 0) { publishService.unpublish(any(), any()) } },
            {
                lines.filter { it.second.startsWith("event=demo.endpoint_retired") } shouldBe
                    listOf(
                        Level.INFO to
                            "event=demo.endpoint_retired path=$path removed=false discarded=[1, 3] pipeline=nyc/mobility/gone_away",
                    )
            },
            { lines.filter { it.first.isGreaterOrEqual(Level.WARN) } shouldBe emptyList() },
            { lines.single { it.second.startsWith("event=demo.api_key_seeded") }.second.contains("retired=1") shouldBe true },
        )
    }

    @Test
    fun `an API whose versions are all DISCARDED is already retired - no write, no line, not counted`() {
        happyStubs()
        val path = "/demo/nyc/mobility/gone_away"
        every { endpointRepository.findByWorkspace(demoId) } returns listOf(endpoint(path, "nyc/mobility/gone_away", status = null))
        holds(path, PipelineVersionStatus.DISCARDED, PipelineVersionStatus.DISCARDED)

        val lines = logLines { seeder(demoApiKey = "").afterSingletonsInstantiated() }

        assertAll(
            { verify(exactly = 0) { publishService.unpublish(any(), any()) } },
            { verify(exactly = 0) { versionService.discardVersion(any(), any(), any()) } },
            { verify(exactly = 0) { versionService.discardDraft(any(), any(), any()) } },
            { lines.filter { it.second.contains(path) } shouldBe emptyList() },
            { lines.filter { it.first.isGreaterOrEqual(Level.WARN) } shouldBe emptyList() },
            { lines.single { it.second.startsWith("event=demo.api_key_unset") }.second.contains("retired=0") shouldBe true },
        )
    }

    @Test
    fun `on an authoring-disabled deployment a stale released API is deferred with one INFO line and nothing attempted`() {
        happyStubs()
        val path = "/demo/nyc/mobility/gone_away"
        every { endpointRepository.findByWorkspace(demoId) } returns listOf(endpoint(path, "nyc/mobility/gone_away"))
        holds(path, PipelineVersionStatus.RELEASED, PipelineVersionStatus.DRAFT)

        val lines = logLines { seeder(authoringEnabled = false).afterSingletonsInstantiated() }

        assertAll(
            { verify(exactly = 0) { publishService.unpublish(any(), any()) } },
            { verify(exactly = 0) { versionService.discardVersion(any(), any(), any()) } },
            { verify(exactly = 0) { versionService.discardDraft(any(), any(), any()) } },
            { deferredLines(lines, path) shouldBe 1 },
            { lines.filter { it.first.isGreaterOrEqual(Level.WARN) } shouldBe emptyList() },
            // The API was NOT retired: the boot's count says so, and nothing claims otherwise.
            { lines.single { it.second.startsWith("event=demo.api_key_seeded") }.second.contains("retired=0") shouldBe true },
            { lines.filter { it.second.startsWith("event=demo.endpoint_retired") } shouldBe emptyList() },
        )
    }

    @Test
    fun `the kill switch on an authoring-disabled deployment defers the released API and still revokes the managed key`() {
        // Owner ruling 2026-11-11 (#889 round 2): the gap is accepted, #897 tracks retraction. The three facts the
        // ruling pins: no discard or delete is attempted, the managed demo key is still revoked, one deferral line.
        happyStubs()
        val path = "/demo/nyc/mobility/revenue-by-borough"
        every { endpointRepository.findByWorkspace(demoId) } returns listOf(endpoint(path, "nyc/mobility/revenue_by_borough"))
        holds(path, PipelineVersionStatus.RELEASED, PipelineVersionStatus.DRAFT)
        val managed = issued(key).record
        every { keyRepository.findByWorkspaceAndName(demoId, "demo-public-key") } returns listOf(managed)
        every { apiKeyService.revokeWorkspaceEndpointKey(managed.id, demoId, systemActorId) } returns true

        val lines = logLines { seeder(demoApiKey = "", authoringEnabled = false).afterSingletonsInstantiated() }

        assertAll(
            { verify(exactly = 0) { publishService.unpublish(any(), any()) } },
            { verify(exactly = 0) { versionService.discardVersion(any(), any(), any()) } },
            { verify(exactly = 0) { versionService.discardDraft(any(), any(), any()) } },
            { verify(exactly = 1) { apiKeyService.revokeWorkspaceEndpointKey(managed.id, demoId, systemActorId) } },
            { verify(exactly = 0) { keyService.issue(any(), any(), any(), any(), any(), any(), any()) } },
            { deferredLines(lines, path) shouldBe 1 },
            { lines.filter { it.first.isGreaterOrEqual(Level.WARN) } shouldBe emptyList() },
            {
                lines.single { it.second.startsWith("event=demo.api_key_unset") }.second shouldBe
                    "event=demo.api_key_unset workspace=demo retired=0 revoked=1"
            },
        )
    }

    /**
     * The deferral lines for [path] in [lines] that state the ruling plainly: the API keeps serving to other valid keys
     * and #897 tracks it — the line must never read as a retirement.
     */
    private fun deferredLines(
        lines: List<Pair<Level, String>>,
        path: String,
    ): Int =
        lines.count { (level, message) ->
            level == Level.INFO &&
                message.startsWith("event=demo.endpoint_retire_deferred path=$path ") &&
                message.contains("is NOT retired") &&
                message.contains("keeps serving to any other valid key bound to it") &&
                message.contains("#897")
        }

    @Test
    fun `a failing retirement is one ERROR line and the next stale API is still retired`() {
        happyStubs()
        val first = "/demo/nyc/mobility/gone_away"
        val second = "/demo/nyc/mobility/also_gone"
        every { endpointRepository.findByWorkspace(demoId) } returns
            listOf(endpoint(first, "nyc/mobility/gone_away"), endpoint(second, "nyc/mobility/also_gone"))
        holds(first, PipelineVersionStatus.RELEASED)
        holds(second, PipelineVersionStatus.RELEASED)
        every { versionService.discardVersion(any(), UUID.nameUUIDFromBytes(first.toByteArray()), 1) } throws
            RuntimeException("the version lock was busy")
        every { versionService.discardVersion(any(), UUID.nameUUIDFromBytes(second.toByteArray()), 1) } returns
            EndpointVersionService.Moved(1, 1, null)

        val lines = logLines { seeder(demoApiKey = "").afterSingletonsInstantiated() }

        assertAll(
            { verify(exactly = 1) { versionService.discardVersion(any(), UUID.nameUUIDFromBytes(second.toByteArray()), 1) } },
            {
                lines.filter { it.first == Level.ERROR }.map { it.second.substringBefore(" reason") } shouldBe
                    listOf("event=demo.endpoint_retire_failed path=$first")
            },
            { lines.single { it.second.startsWith("event=demo.api_key_unset") }.second.contains("retired=1") shouldBe true },
        )
    }

    @Test
    fun `a retired API at a path wanted again is revived - a new draft on the newest version's hash, released, never re-created`() {
        happyStubs()
        val path = "/demo/nyc/mobility/revenue-by-borough"
        val id = UUID.nameUUIDFromBytes(path.toByteArray())
        every { endpointRepository.findByPath(path) } returns endpoint(path, "nyc/mobility/revenue_by_borough", status = null)
        every { endpointVersions.findDraft(id) } returns null andThen versionOf(path, 3, PipelineVersionStatus.DRAFT)
        holds(path, PipelineVersionStatus.DISCARDED, PipelineVersionStatus.DISCARDED)
        val inputs = mutableListOf<EndpointVersionService.DraftInput>()
        every { versionService.saveDraft(any(), id, capture(inputs), "hash-$path-v2") } returns
            versionOf(path, 3, PipelineVersionStatus.DRAFT)
        val requests = mutableListOf<RootReleaseRequest>()
        every { releases.releaseRoot(capture(requests)) } returns mockk()

        val lines = logLines { seeder().afterSingletonsInstantiated() }

        assertAll(
            { verify(exactly = 0) { publishService.create(any(), path, any(), any(), any(), any()) } },
            {
                inputs shouldBe
                    listOf(EndpointVersionService.DraftInput("nyc/mobility/revenue_by_borough", 1, null, DemoEndpointSeeder.DESCRIPTION))
            },
            { requests.filter { it.entityId == id }.map { it.expectedHash } shouldBe listOf("hash-$path-v3") },
            {
                lines.count {
                    it.second == "event=demo.endpoint_revived path=$path version=3 pipeline=nyc/mobility/revenue_by_borough"
                } shouldBe
                    1
            },
            { lines.filter { it.first.isGreaterOrEqual(Level.WARN) } shouldBe emptyList() },
        )
    }

    @Test
    fun `a hand-published demo API with no current version is not revived - it is someone else's path`() {
        happyStubs()
        val path = "/demo/nyc/mobility/revenue-by-borough"
        every { endpointRepository.findByPath(path) } returns
            endpoint(path, "nyc/mobility/revenue_by_borough", createdBy = UUID.randomUUID(), status = null)

        val lines = logLines { seeder().afterSingletonsInstantiated() }

        assertAll(
            { verify(exactly = 0) { versionService.saveDraft(any(), any(), any(), any()) } },
            { lines.count { it.second.startsWith("event=demo.path_taken path=$path") } shouldBe 1 },
        )
    }

    /** The seeder's own log lines during [block], as level and formatted message. */
    private fun logLines(block: () -> Unit): List<Pair<Level, String>> =
        capturingLogEvents(LoggerFactory.getLogger(DemoEndpointSeeder::class.java) as Logger, block = block)
            .map { it.level to it.formattedMessage }

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
