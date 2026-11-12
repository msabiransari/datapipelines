package co.datapipelines.web.release

import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.PipelineVersionConsumers
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReleaseCheckGate
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateReviewMarks
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.PipelineReleaseRequest
import co.datapipelines.pipeline.release.ReleaseAuditRow
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseGraph
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.pipeline.release.ResolvedNode
import co.datapipelines.pipeline.release.TreeHash
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.web.NO_API_VERSION_PINS
import co.datapipelines.web.NO_SCHEDULE_PINS
import co.datapipelines.web.templates.TemplateReleaseService
import io.mockk.every
import io.mockk.mockk
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * #877 §E.3 — one pipeline release world over MOCKED repositories, the shape `PipelineReleaseServiceTest` stubbed: the
 * pipeline `test/monthly_revenue` with its DRAFT v2, the template statuses port, the validator, and a template release
 * seam. [coordinator] releases it through the REAL [ReleaseCoordinator] — the pipeline and template participants, the
 * planning walk over [PipelineDependencySource] and the template statuses, the writer guard, the strict audit and a
 * transaction manager that marks a transaction active between `begin` and `commit`/`rollback` (as the real one does).
 *
 * The tests stub [pipelines], [templates] and [validator] exactly as the native unit test did; the world adds only the
 * reads the coordinator makes on top (the record by id and name, the version detail the walk reads).
 */
internal class NativeReleaseWorld {
    // Fixed ids: two worlds built for one case answer byte-identical records (the parity compares whole results).
    val workspaceId: UUID = UUID.nameUUIDFromBytes("world:workspace".toByteArray())
    val userId: UUID = UUID.nameUUIDFromBytes("world:user".toByteArray())
    val pipelineId: UUID = UUID.nameUUIDFromBytes("world:pipeline".toByteArray())
    val pipelines = mockk<PipelineRepository>()
    val templates = mockk<TemplateVersionStatuses>()
    val validator = mockk<PipelineValidator>()
    val dashboards = mockk<PipelineVersionConsumers>(relaxed = true)
    val templateReleases = mockk<TemplateReleaseService>()

    /** `begin`, `commit`, `rollback`, and whatever a test's hooks append, in order. */
    val log = mutableListOf<String>()
    val audited = mutableListOf<ReleaseAuditRow>()

    /** Every template the release flipped, in order. */
    val templatesReleased = mutableListOf<TemplateRef>()

    /** Runs before each template flip — a test's recorder or its refusal. */
    var onTemplateRelease: (TemplateRef) -> Unit = {}

    private val manager =
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
                log += "begin"
                TransactionSynchronizationManager.setActualTransactionActive(true)
                return SimpleTransactionStatus()
            }

            override fun commit(status: TransactionStatus) {
                log += "commit"
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }

            override fun rollback(status: TransactionStatus) {
                log += "rollback"
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
        }

    /** The world's metadata transaction: `begin`/`commit`/`rollback` in [log]. */
    val transactions: TransactionOperations = TransactionTemplate(manager)

    private val sources: Map<ReleaseFamily, DependencySource> =
        ReleaseFamily.entries.associateWith { family ->
            when (family) {
                ReleaseFamily.PIPELINE -> PipelineDependencySource(pipelines)
                ReleaseFamily.TEMPLATE -> DependencySource { identity -> templateNode(identity) }
                else -> DependencySource { null }
            }
        }

    init {
        every { pipelines.findDraftBodySnapshot(any(), any(), any()) } answers {
            val body = pipelines.findVersionBody(firstArg(), secondArg(), thirdArg())
            body?.let {
                PipelineRepository.DraftBodySnapshot(it, checkNotNull(pipelines.findDraftDetail(firstArg(), secondArg())).bodyHash)
            }
        }
        every { pipelines.findById(workspaceId, pipelineId) } answers { record() }
        every { pipelines.findByNameAnyStatus(workspaceId, NAME) } answers { record() }
        every { pipelines.findVersionDetail(workspaceId, pipelineId, VERSION) } answers {
            pipelines.findDraftDetail(workspaceId, pipelineId)
        }
        every { templateReleases.releaseExact(workspaceId, any(), any(), any(), userId, any()) } answers {
            val ref = TemplateRef(secondArg(), thirdArg())
            onTemplateRelease(ref)
            templatesReleased += ref
            TemplateReleaseService.Released(
                TemplateVersionDetail(ref.id, ref.version, PipelineVersionStatus.RELEASED, arg(3), Instant.EPOCH, userId),
                mockk(),
            )
        }
    }

    /** Whether the world's metadata transaction is open right now — what a hook records as `inside`. */
    fun inside(): Boolean = TransactionSynchronizationManager.isActualTransactionActive()

    fun record(currentVersion: Int? = null) =
        PipelineRecord(pipelineId, NAME, "M", "d", userId, currentVersion, Instant.EPOCH, Instant.EPOCH)

    fun draftDetail(hash: String = "draft-hash") =
        PipelineVersionDetail(pipelineId, VERSION, PipelineVersionStatus.DRAFT, hash, Instant.EPOCH, userId)

    /** The flip's answer — v2 RELEASED, the pointer moved. */
    fun releasedFlip() =
        PipelineRepository.Released(record(currentVersion = VERSION), draftDetail().copy(status = PipelineVersionStatus.RELEASED))

    /** The release service as production constructs it, over this world's mocks. */
    fun service(
        authoring: Boolean = true,
        checkGate: ReleaseCheckGate = ReleaseCheckGate.NONE,
        reviewMarks: TemplateReviewMarks = TemplateReviewMarks.NONE,
    ) = PipelineReleaseService(
        pipelines,
        templates,
        validator,
        AuthoringGuard(authoring),
        checkGate = checkGate,
        templateReleaser = { _, id, version, _ ->
            TemplateRef(id, version).also {
                onTemplateRelease(it)
                templatesReleased += it
            }
        },
        transactions = transactions,
        reviewMarks = reviewMarks,
        dashboards = dashboards,
        endpointVersions = NO_API_VERSION_PINS,
        schedules = NO_SCHEDULE_PINS,
    )

    /** The production coordinator over [releases] and this world. */
    fun coordinator(releases: PipelineReleaseService = service()) =
        ReleaseCoordinator(
            releases = releases,
            pipelines = pipelines,
            planning =
                ReleasePlanningWalk(
                    ReleaseDependencyFamilies.registry(sources),
                    ReleaseBounds(),
                    TransactionOperations.withoutTransaction(),
                    ReadIsolation.NONE,
                    ReleasePlanningDeadline.NONE,
                ),
            participants = listOf(PipelineReleaseParticipant(releases, pipelines), TemplateReleaseParticipant(templateReleases)),
            authorization = RecordingAuthorization(mutableListOf(), principal()),
            guard = { _, _ -> },
            audit = { audited += it },
            transactions = transactions,
            bounds = ReleaseBounds(),
        )

    /** A release request for the world's pipeline — the surfaces' shape. */
    fun request(
        expectedHash: String = "draft-hash",
        overrideChecksReason: String? = null,
        releasePinnedTemplates: Boolean = false,
        plan: ReleasePlanRequest? = null,
    ) = PipelineReleaseRequest(workspaceId, pipelineId, expectedHash, userId, overrideChecksReason, releasePinnedTemplates, plan)

    /** The plan a client holding the preview would submit: its tree hash, nothing ticked. */
    fun plan(): ReleasePlanRequest {
        val root = PipelineDependencySource.identity(workspaceId, NAME, VERSION)
        val closure = requireNotNull(ReleaseGraph(sources, ReleaseBounds()).walk(root))
        return ReleasePlanRequest(TreeHash.of(closure, ReleaseBounds().maxWireBytes), emptyList())
    }

    fun principal() = AuthenticatedPrincipal(userId, "author@datapipelines.test", "Author", AuthMethod.OIDC)

    private fun templateNode(identity: co.datapipelines.pipeline.release.ReleaseNodeIdentity): ResolvedNode? =
        templates.statusOf(identity.workspaceId, identity.entityId, identity.version)?.let { status ->
            ResolvedNode(
                identity,
                identity.entityId,
                PipelineDependencySource.statusOf(status),
                "template-hash",
                null,
                emptyList(),
                UUID.nameUUIDFromBytes(identity.entityId.toByteArray()),
            )
        }

    companion object {
        const val NAME = "test/monthly_revenue"
        const val VERSION = 2
    }
}
