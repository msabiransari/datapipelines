package co.datapipelines.application.endpoints

import co.datapipelines.application.SharedPostgres
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.inverse.InverseRequest
import co.datapipelines.pipeline.inverse.InverseScope
import co.datapipelines.pipeline.inverse.InverseSql
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * #786 A.3/C.2/C.4 — the API version lifecycle against real PostgreSQL, below the web layer: [EndpointVersionService]'s
 * draft save under `If-Match`, the draft discard and version purge, discard / restore / the current switch, and
 * [EndpointReleaseSteps]' prepare-then-commit flip, over the real [PublishedEndpointVersionRepository]. The REST, MCP and
 * dialog surfaces over these verbs are `PublishedEndpointE2eTest`'s and the web tests'; the G0 guard races are
 * `EndpointVersionMigrationE2eTest`'s. Here every assertion reads what the database holds after the verb.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointVersionLifecycleIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var endpoints: PublishedEndpointRepository
    private lateinit var versions: PublishedEndpointVersionRepository
    private lateinit var pipelineRepository: PipelineRepository
    private val pipelines = mockk<PipelineService>()
    private val audit = RecordingAudit()
    private var userId: UUID = UUID.randomUUID()
    private var workspaceId: UUID = UUID.randomUUID()

    /** The one pipeline each test seeds; every draft input pins it. */
    private var seededPipeline: String = ""

    @BeforeAll
    fun wire() {
        val dataSource = SharedPostgres.dataSource()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        endpoints = PublishedEndpointRepository(jdbc)
        versions = PublishedEndpointVersionRepository(jdbc)
        pipelineRepository = PipelineRepository(jdbc)
    }

    @BeforeEach
    fun seed() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        user(userId)
        workspace(workspaceId)
        audit.events.clear()
        every { pipelines.findExecutable(any(), any(), any(), any()) } answers {
            val record = thirdArg<PipelineRecord>()
            PipelineService.ExecutablePipeline(record, arg(3), "{}", readOnlyBody(record.name))
        }
    }

    @Test
    fun `the lifecycle walk - save, release, a second draft, discard, restore and the switch - each lands what it says`() {
        pipeline("acme/rides/daily", current = 2, PipelineVersionStatus.RELEASED, PipelineVersionStatus.RELEASED)
        val service = service()
        val api = publishing().create(principal(), "/acme/v1/rides", "acme/rides/daily", 1, 45, "Daily")
        val v1 = versions.find(api.id, 1).shouldNotBeNull()
        v1.status shouldBe PipelineVersionStatus.DRAFT
        pointer(api.id) shouldBe 1

        withClue("a stale If-Match is a conflict, and nothing is written") {
            shouldThrow<DatapipelinesException> {
                service.saveDraft(principal(), api.id, input(pipelineVersion = 2), "stale")
            }.code shouldBe PipelineErrorCodes.Endpoint.VERSION_CONFLICT
            versions.find(api.id, 1).shouldNotBeNull().bodyHash shouldBe v1.bodyHash
        }

        val saved = service.saveDraft(principal(), api.id, input(pipelineVersion = 2), v1.bodyHash)
        withClue("the working draft is saved in place; a null timeout and description keep the base version's") {
            saved.version shouldBe 1
            saved.body shouldBe EndpointVersionBody("acme/rides/daily", 2, 45, "Daily")
        }

        val released = release(api.id, saved.bodyHash)
        released.status shouldBe PipelineVersionStatus.RELEASED
        pointer(api.id) shouldBe 1

        val second = service.saveDraft(principal(), api.id, input(pipelineVersion = 1, timeout = 60, description = "v2"), released.bodyHash)
        withClue("with no draft, a save opens max+1 on the current version's hash; the pointer stays on the release") {
            second.version shouldBe 2
            second.body shouldBe EndpointVersionBody("acme/rides/daily", 1, 60, "v2")
            pointer(api.id) shouldBe 1
        }
        shouldThrow<DatapipelinesException> {
            service.discardDraft(principal(), api.id, "stale")
        }.code shouldBe PipelineErrorCodes.Endpoint.VERSION_CONFLICT
        service.discardDraft(principal(), api.id, second.bodyHash) shouldBe EndpointVersionService.Purged(2, entityDeleted = false)
        versions.list(api.id).map { it.version } shouldContainExactly listOf(1)

        val again = service.saveDraft(principal(), api.id, input(pipelineVersion = 1), released.bodyHash)
        withClue("a purged draft's number is reused") { again.version shouldBe 2 }
        release(api.id, again.bodyHash)
        pointer(api.id) shouldBe 2

        service.discardVersion(principal(), api.id, 2) shouldBe EndpointVersionService.Moved(2, currentBefore = 2, currentAfter = 1)
        versions.find(api.id, 2).shouldNotBeNull().status shouldBe PipelineVersionStatus.DISCARDED
        service.restoreVersion(principal(), api.id, 2) shouldBe EndpointVersionService.Moved(2, currentBefore = 1, currentAfter = 2)
        versions.find(api.id, 2).shouldNotBeNull().status shouldBe PipelineVersionStatus.RELEASED
        service.switchCurrent(principal(), api.id, 1) shouldBe EndpointVersionService.Moved(1, currentBefore = 2, currentAfter = 1)

        assertReadsAfterTheWalk(service, api)
        audit.events shouldContainExactly
            listOf(
                EndpointVersionService.AUDIT_DRAFT_SAVED,
                EndpointVersionService.AUDIT_DRAFT_SAVED,
                EndpointVersionService.AUDIT_DRAFT_SAVED,
                EndpointVersionService.AUDIT_VERSION_PURGED,
                EndpointVersionService.AUDIT_DRAFT_SAVED,
                EndpointVersionService.AUDIT_VERSION_DISCARDED,
                EndpointVersionService.AUDIT_VERSION_RESTORED,
                EndpointVersionService.AUDIT_CURRENT_SWITCHED,
            )
    }

    @Test
    fun `each verb refuses the version state it does not apply to, and another workspace's API is not found`() {
        pipeline("acme/rides/states", current = 1, PipelineVersionStatus.RELEASED)
        val service = service()
        val api = publishing().create(principal(), "/acme/v1/states", "acme/rides/states", 1, null, "")
        val released = release(api.id, versions.find(api.id, 1).shouldNotBeNull().bodyHash)

        fun refused(block: () -> Unit) = shouldThrow<DatapipelinesException> { block() }.code
        refused { service.purgeVersion(principal(), api.id, 1) } shouldBe PipelineErrorCodes.Endpoint.VERSION_NOT_DRAFT
        refused { service.restoreVersion(principal(), api.id, 1) } shouldBe PipelineErrorCodes.Endpoint.VERSION_NOT_DISCARDED
        refused { service.purgeVersion(principal(), api.id, 9) } shouldBe PipelineErrorCodes.Endpoint.VERSION_NOT_FOUND
        refused { service.discardDraft(principal(), api.id, released.bodyHash) } shouldBe PipelineErrorCodes.Endpoint.VERSION_NOT_FOUND

        val draft = service.saveDraft(principal(), api.id, input(pipelineVersion = 1, description = "next"), released.bodyHash)
        refused { service.discardVersion(principal(), api.id, draft.version) } shouldBe PipelineErrorCodes.Endpoint.VERSION_NOT_RELEASED
        service.discardVersion(principal(), api.id, 1).currentAfter shouldBe draft.version
        refused { service.switchCurrent(principal(), api.id, 1) } shouldBe PipelineErrorCodes.Endpoint.VERSION_NOT_ELIGIBLE

        val elsewhere = UUID.randomUUID().also(::workspace)
        val stranger = principal(elsewhere)
        withClue("another workspace's API answers as absent: reads null, writes not found") {
            service.versions(stranger, api.id).shouldBeNull()
            refused { service.discardVersion(stranger, api.id, 1) } shouldBe PipelineErrorCodes.Endpoint.VERSION_NOT_FOUND
            refused { service.switchCurrent(principal(), UUID.randomUUID(), 1) } shouldBe PipelineErrorCodes.Endpoint.VERSION_NOT_FOUND
        }
    }

    @Test
    fun `the release steps refuse a stale hash, a non-draft and a changed draft, and find no root elsewhere`() {
        pipeline("acme/rides/steps", current = 1, PipelineVersionStatus.RELEASED)
        val service = service()
        val steps = steps()
        val api = publishing().create(principal(), "/acme/v1/steps", "acme/rides/steps", 1, null, "")
        val v1 = versions.find(api.id, 1).shouldNotBeNull()
        val identity = steps.rootIdentity(workspaceId, api.id).shouldNotBeNull()
        identity.version shouldBe 1
        steps.rootIdentity(UUID.randomUUID(), api.id).shouldBeNull()

        fun refused(block: () -> Unit) = shouldThrow<DatapipelinesException> { block() }.code
        refused { steps.prepare(identity, api.id, "stale", userId) } shouldBe PipelineErrorCodes.Endpoint.VERSION_CONFLICT
        refused { steps.prepare(identity, null, v1.bodyHash, userId) } shouldBe PipelineErrorCodes.Endpoint.RELEASE_PLAN_CONFLICT
        refused { steps.prepare(identity.copy(version = 7), api.id, v1.bodyHash, userId) } shouldBe
            PipelineErrorCodes.Endpoint.RELEASE_PLAN_CONFLICT

        withClue("the draft changed between prepare and commit: the CAS misses, the root's plan_conflict, nothing flips") {
            val prepared = steps.prepare(identity, api.id, v1.bodyHash, userId)
            service.saveDraft(principal(), api.id, input(pipelineVersion = 1, description = "moved"), v1.bodyHash)
            refused { transactions.execute { steps.commit(prepared) } } shouldBe PipelineErrorCodes.Endpoint.RELEASE_PLAN_CONFLICT
            versions.find(api.id, 1).shouldNotBeNull().status shouldBe PipelineVersionStatus.DRAFT
        }

        release(api.id, versions.find(api.id, 1).shouldNotBeNull().bodyHash)
        refused { steps.rootIdentity(workspaceId, api.id) } shouldBe PipelineErrorCodes.Endpoint.VERSION_NOT_DRAFT
        refused { steps.prepare(identity, api.id, v1.bodyHash, userId) } shouldBe PipelineErrorCodes.Endpoint.VERSION_NOT_DRAFT
    }

    @Test
    fun `a restore rechecks its pin - discarded is the 400, a draft the 409, and a live pin restores`() {
        val pipelineId = pipeline("acme/rides/restore", current = 2, PipelineVersionStatus.RELEASED, PipelineVersionStatus.RELEASED)
        val service = service()
        val api = publishing().create(principal(), "/acme/v1/restore", "acme/rides/restore", 1, null, "")
        release(api.id, versions.find(api.id, 1).shouldNotBeNull().bodyHash)
        service.discardVersion(principal(), api.id, 1).currentAfter.shouldBeNull()

        pipelineStatus(pipelineId, 1, PipelineVersionStatus.DISCARDED)
        shouldThrow<DatapipelinesException> {
            service.restoreVersion(principal(), api.id, 1)
        }.code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_VERSION_NOT_FOUND
        pipelineStatus(pipelineId, 1, PipelineVersionStatus.DRAFT)
        shouldThrow<DatapipelinesException> {
            service.restoreVersion(principal(), api.id, 1)
        }.code shouldBe PipelineErrorCodes.Endpoint.RELEASE_DEPENDENCY_NOT_RELEASED
        withClue("both refusals rolled back: the API version is still DISCARDED, nothing current") {
            versions.find(api.id, 1).shouldNotBeNull().status shouldBe PipelineVersionStatus.DISCARDED
            pointer(api.id).shouldBeNull()
        }

        pipelineStatus(pipelineId, 1, PipelineVersionStatus.RELEASED)
        service.restoreVersion(principal(), api.id, 1) shouldBe EndpointVersionService.Moved(1, currentBefore = null, currentAfter = 1)
    }

    @Test
    fun `purging the sole version removes the API with it`() {
        pipeline("acme/rides/sole", current = 1, PipelineVersionStatus.RELEASED)
        val api = publishing().create(principal(), "/acme/v1/sole", "acme/rides/sole", 1, null, "")
        service().purgeVersion(principal(), api.id, 1) shouldBe EndpointVersionService.Purged(1, entityDeleted = true)
        endpoints.findById(api.id).shouldBeNull()
        versions.list(api.id) shouldBe emptyList()
    }

    @Test
    fun `deleting a released API is refused last_release in the deleting transaction, and nothing it holds goes (#786 R1)`() {
        pipeline("acme/rides/kept", current = 1, PipelineVersionStatus.RELEASED)
        val api = publishing(transactions).create(principal(), "/acme/v1/kept", "acme/rides/kept", 1, null, "")
        val released = release(api.id, versions.find(api.id, 1).shouldNotBeNull().bodyHash)
        bind("/acme")
        val before = snapshot(api.id)

        val refused = shouldThrow<DatapipelinesException> { publishing(transactions).unpublish(principal(), "/acme/v1/kept") }
        refused.code shouldBe PipelineErrorCodes.Endpoint.VERSION_LAST_RELEASE
        refused.details shouldBe mapOf("endpoint_id" to api.id.toString(), "version" to released.version, "status" to "RELEASED")
        withClue("the entity, its pointer, its versions and the node's binding all stay; no unpublish is audited") {
            snapshot(api.id) shouldBe before
            audit.events.contains(EndpointPublishService.AUDIT_UNPUBLISHED) shouldBe false
        }

        // A NON-current RELEASED version: v2 DRAFT becomes current, v1 RELEASED stays behind it — still refused.
        val draft = service().saveDraft(principal(), api.id, input(pipelineVersion = 1, description = "v2"), released.bodyHash)
        service().switchCurrent(principal(), api.id, draft.version)
        pointer(api.id) shouldBe draft.version
        shouldThrow<DatapipelinesException> {
            publishing(transactions).unpublish(principal(), "/acme/v1/kept")
        }.details["version"] shouldBe released.version
        endpoints.findById(api.id).shouldNotBeNull()
        versions.list(api.id).map { it.status } shouldContainExactly listOf(PipelineVersionStatus.DRAFT, PipelineVersionStatus.RELEASED)
    }

    @Test
    fun `a draft-only API is deleted with its versions, and its node's binding is left to the tree (#786 R1)`() {
        pipeline("acme/rides/drafty", current = 1, PipelineVersionStatus.RELEASED)
        val api = publishing(transactions).create(principal(), "/acme/v1/drafty", "acme/rides/drafty", 1, null, "")
        bind("/acme")

        publishing(transactions).unpublish(principal(), "/acme/v1/drafty") shouldBe true
        endpoints.findById(api.id).shouldBeNull()
        versions.list(api.id) shouldBe emptyList()
        bindings() shouldBe 1
        audit.events.contains(EndpointPublishService.AUDIT_UNPUBLISHED) shouldBe true
    }

    @Test
    fun `with authoring disabled every lifecycle verb and the release prepare refuse, whatever the role`() {
        pipeline("acme/rides/hardened", current = 1, PipelineVersionStatus.RELEASED)
        val api = publishing().create(principal(), "/acme/v1/hardened", "acme/rides/hardened", 1, null, "")
        val hash = versions.find(api.id, 1).shouldNotBeNull().bodyHash
        val identity = steps().rootIdentity(workspaceId, api.id).shouldNotBeNull()
        val off = AuthoringGuard(false)
        val service = service(off)
        val verbs: List<() -> Unit> =
            listOf(
                { service.saveDraft(principal(), api.id, input(pipelineVersion = 1), hash) },
                { service.discardDraft(principal(), api.id, hash) },
                { service.purgeVersion(principal(), api.id, 1) },
                { service.discardVersion(principal(), api.id, 1) },
                { service.restoreVersion(principal(), api.id, 1) },
                { service.switchCurrent(principal(), api.id, 1) },
                { steps(off).prepare(identity, api.id, hash, userId) },
            )
        verbs.forEach { verb ->
            shouldThrow<DatapipelinesException> { verb() }.code shouldBe PipelineErrorCodes.Endpoint.AUTHORING_DISABLED
        }
        versions.find(api.id, 1).shouldNotBeNull().bodyHash shouldBe hash
    }

    @Test
    fun `the selected workspace bounds management reads and row locks`() {
        pipeline("acme/rides/scoped", current = 1, PipelineVersionStatus.RELEASED)
        val api = publishing().create(principal(), "/acme/v1/scoped", "acme/rides/scoped", 1, null, "")
        val hash = versions.find(api.id, 1).shouldNotBeNull().bodyHash
        val elsewhere = UUID.randomUUID().also { workspace(it) }
        val service = service()

        endpoints.findById(workspaceId, api.id).shouldNotBeNull().id shouldBe api.id
        endpoints.findById(elsewhere, api.id).shouldBeNull()
        service.versions(principal(elsewhere), api.id).shouldBeNull()
        transactions.execute { versions.lockEntity(workspaceId, api.id) }.shouldNotBeNull().endpointId shouldBe api.id

        SharedPostgres.dataSource().connection.use { holder ->
            holder.autoCommit = false
            try {
                holder.prepareStatement("SELECT id FROM published_endpoints WHERE id = ? FOR UPDATE").use { statement ->
                    statement.setObject(1, api.id)
                    statement.executeQuery().next() shouldBe true
                }
                transactions.execute { status ->
                    status.setRollbackOnly()
                    jdbc.jdbcTemplate.execute("SET LOCAL lock_timeout = '100ms'")
                    versions.lockEntity(elsewhere, api.id).shouldBeNull()
                    shouldThrow<DatapipelinesException> {
                        service.discardDraft(principal(elsewhere), api.id, hash)
                    }.code shouldBe PipelineErrorCodes.Endpoint.VERSION_NOT_FOUND
                }
            } finally {
                holder.rollback()
            }
        }
        versions.find(api.id, 1).shouldNotBeNull().bodyHash shouldBe hash
    }

    // ------------------------------------------------------------------------- helpers

    /** What the walk leaves for the readers: v2 RELEASED, v1 RELEASED and current. */
    private fun assertReadsAfterTheWalk(
        service: EndpointVersionService,
        api: PublishedEndpoint,
    ) {
        val listed = service.versions(principal(), api.id).shouldNotBeNull()
        withClue("newest first, the current one marked") {
            listed.map { it.version.version to it.isCurrent } shouldContainExactly listOf(2 to false, 1 to true)
            listed.all { it.eligible } shouldBe true
        }
        service.version(principal(), api.id, 3).shouldBeNull()
        versions.findCurrent(api.id).shouldNotBeNull().version shouldBe 1
        val (entity, byPath) = versions.findByPath(workspaceId, api.pathPattern, 2).shouldNotBeNull()
        entity shouldBe PublishedEndpointVersionRepository.EntityRead(api.id, 1, enabled = true)
        byPath.version shouldBe 2
        versions.findByPath(UUID.randomUUID(), api.pathPattern, 2).shouldBeNull()
        withClue("E.2: no path leaves a version-less entity behind") { versions.countVersionless() shouldBe 0 }
    }

    @Test
    fun `the inverse API-version pages walk the pins by keyset - limit + 1 rows, every occurrence once`() {
        // #877 §B: rides v1 DRAFT, trips v1 RELEASED + v2 DRAFT — three API versions pinning daily@1, the page unit.
        pipeline("acme/rides/daily", current = 1, PipelineVersionStatus.RELEASED)
        publishing().create(principal(), "/acme/v1/rides", "acme/rides/daily", 1, 45, "Rides")
        val trips = publishing().create(principal(), "/acme/v1/trips", "acme/rides/daily", 1, 45, "Trips")
        release(trips.id, versions.find(trips.id, 1).shouldNotBeNull().bodyHash)
        versions.insert(trips.id, 2, PipelineVersionStatus.DRAFT, EndpointVersionBody("acme/rides/daily", 1, 45, "Trips"), userId)
        val request = InverseRequest(workspaceId, "acme/rides/daily", 1, InverseScope.LIVE, null, 1)

        versions.findPipelinePinOccurrences(request) shouldHaveSize 2
        val paged = InverseSql.exhaust(1) { after -> versions.findPipelinePinOccurrences(request.copy(after = after)) }
        paged shouldContainExactly versions.findPipelinePinOccurrences(request.copy(limit = InverseSql.EXHAUST_PAGE))
        paged.map { it.dependentName to it.dependentVersion } shouldContainExactlyInAnyOrder
            listOf("/acme/v1/rides" to 1, "/acme/v1/trips" to 1, "/acme/v1/trips" to 2)
        paged.map { it.sourcePath }.distinct() shouldContainExactly listOf(PublishedEndpointVersionRepository.PIN_PATH)
    }

    private fun release(
        endpointId: UUID,
        hash: String,
    ): PublishedEndpointVersion {
        val steps = steps()
        val identity = steps.rootIdentity(workspaceId, endpointId).shouldNotBeNull()
        val prepared = steps.prepare(identity, endpointId, hash, userId)
        return transactions.execute { steps.commit(prepared) }.shouldNotBeNull()
    }

    private fun input(
        pipelineVersion: Int,
        timeout: Int? = null,
        description: String? = null,
    ) = EndpointVersionService.DraftInput(seededPipeline, pipelineVersion, timeout, description)

    private fun pins(authoring: AuthoringGuard) =
        EndpointPinValidator(
            pipelineRepository = pipelineRepository,
            pipelines = pipelines,
            readOnlyRule = ReadOnlyPipelineRule(PipelineResolver { _, _, _ -> null }, MAX_DEPTH),
            authoring = authoring,
        )

    private fun service(authoring: AuthoringGuard = AuthoringGuard(true)) =
        EndpointVersionService(
            endpoints = endpoints,
            versions = versions,
            pins = pins(authoring),
            restorePins = EndpointRestorePins(pipelineRepository, planning = null, maxBlockers = MAX_BLOCKERS),
            registry = EndpointRegistry(endpoints),
            audit = audit,
            timeouts = TIMEOUTS,
            authoring = authoring,
            transactions = transactions,
        )

    private fun steps(authoring: AuthoringGuard = AuthoringGuard(true)) =
        EndpointReleaseSteps(endpoints, versions, pins(authoring), authoring, EndpointRegistry(endpoints))

    private fun publishing(transactions: TransactionTemplate? = null) =
        EndpointPublishService(
            endpoints = endpoints,
            versions = versions,
            pins = pins(AuthoringGuard(true)),
            registry = EndpointRegistry(endpoints),
            audit = audit,
            timeouts = TIMEOUTS,
            authoring = AuthoringGuard(true),
            transactions = transactions,
        )

    /** One endpoint key bound at [prefix] — a tree node's binding, which an API deletion never touches. */
    private fun bind(prefix: String) {
        val keyId = "k_${UUID.randomUUID().toString().take(8)}"
        jdbc.update(
            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                " VALUES (:id, :owner, :owner, 'endpoint key', 'x', :ws, 'endpoint', 'api_caller')",
            mapOf("id" to keyId, "owner" to userId, "ws" to workspaceId),
        )
        jdbc.update(
            "INSERT INTO endpoint_key_bindings (path_prefix, api_key_id, workspace_id, created_by) VALUES (:p, :key, :ws, :owner)",
            mapOf("p" to prefix, "key" to keyId, "ws" to workspaceId, "owner" to userId),
        )
    }

    private fun bindings(): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM endpoint_key_bindings WHERE workspace_id = :ws",
            mapOf("ws" to workspaceId),
            Int::class.java,
        ) ?: 0

    /** What an entity deletion would remove: the entity row, its pointer, its versions (status + hash), the bindings. */
    private fun snapshot(id: UUID) =
        listOf(endpoints.findById(id)?.id, pointer(id), versions.list(id).map { it.version to it.status to it.bodyHash }, bindings())

    private fun user(id: UUID) {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to id, "email" to "u$id@example.com", "sub" to "sub-$id"),
        )
    }

    private fun workspace(id: UUID) {
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to id, "name" to "w_${id.toString().replace("-", "")}", "owner" to userId),
        )
    }

    private fun pipeline(
        name: String,
        current: Int?,
        vararg statuses: PipelineVersionStatus,
    ): UUID {
        val id = UUID.randomUUID()
        seededPipeline = name
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES (:id, :name, 'P', :owner, :current, :ws)",
            mapOf("id" to id, "name" to name, "owner" to userId, "current" to current, "ws" to workspaceId),
        )
        statuses.forEachIndexed { index, status ->
            jdbc.update(
                "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)" +
                    " VALUES (:id, :version, CAST('{}' AS jsonb), :hash, :status, :owner," +
                    " CASE WHEN :status <> 'DRAFT' THEN CAST(:owner AS UUID) END, CASE WHEN :status <> 'DRAFT' THEN NOW() END)",
                mapOf("id" to id, "version" to index + 1, "hash" to "seed-$index", "status" to status.name, "owner" to userId),
            )
        }
        return id
    }

    /** Moves a seeded pipeline version's status by SQL, with the stamps the table's CHECKs require. */
    private fun pipelineStatus(
        pipelineId: UUID,
        version: Int,
        status: PipelineVersionStatus,
    ) {
        jdbc.update(
            "UPDATE pipeline_versions SET status = :status," +
                " discarded_at = CASE WHEN :status = 'DISCARDED' THEN NOW() END," +
                " released_at = CASE WHEN :status = 'DRAFT' THEN NULL ELSE COALESCE(released_at, NOW()) END," +
                " released_by = CASE WHEN :status = 'DRAFT' THEN NULL ELSE COALESCE(released_by, CAST(:owner AS UUID)) END" +
                " WHERE pipeline_id = :id AND version = :version",
            mapOf("id" to pipelineId, "version" to version, "status" to status.name, "owner" to userId),
        )
    }

    private fun readOnlyBody(name: String) =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = name,
            displayName = "P",
            description = "",
            settings = PipelineSettings(),
            parameters = emptyMap(),
            nodes =
                listOf(
                    Node(
                        id = "rides",
                        description = "",
                        type = NodeType.DQL,
                        source = "pg",
                        template = TemplateRef("test/rides.sql", 1),
                        output = NodeOutput.Caller,
                        dependsOn = emptyList(),
                    ),
                ),
        )

    private fun principal(workspace: UUID = workspaceId) =
        AuthenticatedPrincipal(
            userId = userId,
            email = "a@b.c",
            displayName = "A",
            authMethod = AuthMethod.OIDC,
            workspaceName = "w",
            workspace = WorkspaceContext(workspace, "w"),
        )

    private fun pointer(id: UUID): Int? =
        jdbc.queryForObject("SELECT current_version FROM published_endpoints WHERE id = :id", mapOf("id" to id), Int::class.javaObjectType)

    /** A recording sink: the audit contract is "you must call me", so a strict mock would hide a missing call. */
    private class RecordingAudit : AuditEventSink {
        val events = mutableListOf<String>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            events += event
        }
    }

    private companion object {
        const val MAX_DEPTH = 5
        const val MAX_BLOCKERS = 50
        val TIMEOUTS = EndpointPublishService.TimeoutBounds(defaultSeconds = 30, minSeconds = 1, maxSeconds = 300)
    }
}
