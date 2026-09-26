package co.datapipelines.web.schedules

import co.datapipelines.auth.PermissionResolver
import co.datapipelines.auth.PermissionResolverInstallation
import co.datapipelines.auth.RolePermissionsResolver
import co.datapipelines.auth.User
import co.datapipelines.auth.UserKind
import co.datapipelines.auth.UserService
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.executor.ExecutionEventRecord
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.scheduler.Admission
import co.datapipelines.scheduler.ExecutionOutcome
import co.datapipelines.scheduler.Preparation
import co.datapipelines.scheduler.RunOrigin
import co.datapipelines.scheduler.RunState
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleException
import co.datapipelines.scheduler.TargetViewer
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The pipeline executor's own decisions, with its collaborators mocked — the parts no fake
 * executor in the scheduler's suites can reach:
 *
 * - **R6's table from the execution record** (`inspect`): `SUCCESS` → succeeded, `FAILED` →
 *   failed / `execution_failed`, an abort by a person → cancelled, by the drain → aborted /
 *   `shutdown`, and the stale sweeper's `instance_lost` → **unknown** (A5 — the worker may be alive;
 *   never a conclusive abort), a vanished record → absent;
 * - **the reconciler asks with authority**: a system identity refused `execution.read` decides
 *   nothing (every run stays "running") rather than reading "absent" and blocking;
 * - **the payload contract** (schema version 1): `current` only, `latest` refused by name, no
 *   extra field, a real pipeline name;
 * - **preparation refusals** that need no pipeline body: an inactive workspace does not block, an
 *   absent pipeline or a NULL pointer blocks.
 *
 * The launch path and the binder run end to end in `SchedulerE2eTest` (parity, pointer_null).
 */
class PipelineJobExecutorTest {
    private val mapper = ObjectMapper()
    private val pipelines = mockk<PipelineRepository>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val users = mockk<UserService>()
    private val executions = mockk<ExecutionRepository>()
    private val events = mockk<ExecutionEventRepository>()
    private val adapter =
        PipelineJobExecutor(
            pipelines = pipelines,
            pipelineService = mockk(),
            workspaces = workspaces,
            users = users,
            runner = mockk(),
            executions = executions,
            events = events,
            lens = mockk(),
            executorConfig = mockk(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            mapper = mapper,
        )

    private val workspace = Workspace(UUID.randomUUID(), "acme", "Acme", false, null, false, Instant.EPOCH)
    private val executionId = UUID.randomUUID()

    init {
        every { users.systemActor() } returns systemRow()
        every { workspaces.findById(workspace.id) } returns workspace
    }

    // ------------------------------------------------------------------------------ R6 — inspect

    @Test
    fun `R6 - success, failure and an absent record map as the table says`() {
        outcomeFor(record(ExecutionStatus.SUCCESS)) shouldBe ExecutionOutcome.Finished(RunState.SUCCEEDED, null)
        outcomeFor(record(ExecutionStatus.FAILED)) shouldBe ExecutionOutcome.Finished(RunState.FAILED, PipelineJobExecutor.EXECUTION_FAILED)
        outcomeFor(record(ExecutionStatus.RUNNING)) shouldBe ExecutionOutcome.Running
        outcomeFor(null) shouldBe ExecutionOutcome.Absent
    }

    @Test
    fun `R6 - an abort is cancelled when a person asked, aborted with its reason when the drain did`() {
        every { events.findByExecution(executionId) } returns listOf(abortEvent("cancelled"))
        outcomeFor(record(ExecutionStatus.ABORTED)) shouldBe ExecutionOutcome.Finished(RunState.CANCELLED, "cancelled")

        every { events.findByExecution(executionId) } returns listOf(abortEvent("shutdown"))
        outcomeFor(record(ExecutionStatus.ABORTED)) shouldBe ExecutionOutcome.Finished(RunState.ABORTED, "shutdown")
    }

    @Test
    fun `A5 - the stale sweeper's instance_lost is unknown, never a conclusive abort, and reads no events`() {
        val lost = record(ExecutionStatus.ABORTED).copy(errorJson = """{"code":"pipeline.execution.instance_lost"}""")

        outcomeFor(lost) shouldBe ExecutionOutcome.Finished(RunState.UNKNOWN, PipelineJobExecutor.INSTANCE_LOST)
        verify(exactly = 0) { events.findByExecution(any()) }
    }

    @Test
    fun `a system identity refused execution_read decides nothing - never absent, which would block`() {
        // The installation restores the production resolver when it closes.
        val outcomes = PermissionResolverInstallation(SystemArmWithoutReads).use { adapter.inspect(workspace.id, listOf(executionId)) }

        outcomes shouldBe mapOf(executionId to ExecutionOutcome.Running)
        verify(exactly = 0) { executions.findById(any(), any()) }
    }

    // ------------------------------------------------------------------------------ the payload

    @Test
    fun `the payload is current only - latest is refused by name, a number and a missing selector too`() {
        reasonOf("""{"pipeline":"a/p","version":"latest"}""") shouldBe PipelineJobExecutor.VERSION_LATEST_REFUSED
        reasonOf("""{"pipeline":"a/p","version":"3"}""") shouldBe "version_unsupported"
        reasonOf("""{"pipeline":"a/p"}""") shouldBe "version_missing"
    }

    @Test
    fun `the payload names a real pipeline and nothing else`() {
        reasonOf("""["a/p"]""") shouldBe "not_an_object"
        reasonOf("""{"pipeline":"a/p","version":"current","sql":"DROP TABLE x"}""") shouldBe "unknown_field"
        reasonOf("""{"version":"current"}""") shouldBe "pipeline_missing"
        reasonOf("""{"pipeline":"../etc","version":"current"}""") shouldBe "pipeline_name_invalid"
        verify(exactly = 0) { pipelines.findByNameAnyStatus(any(), any()) }
    }

    @Test
    fun `a payload naming a pipeline the workspace does not hold is target_not_found`() {
        every { pipelines.findByNameAnyStatus(workspace.id, "a/p") } returns null

        shouldThrow<ScheduleException> { adapter.validate(workspace.id, payload("a/p"), mapper.createObjectNode()) }
            .code shouldBe ScheduleErrorCodes.TARGET_NOT_FOUND
    }

    @Test
    fun `a pipeline with no current version cannot be scheduled - pointer_null at save`() {
        every { pipelines.findByNameAnyStatus(workspace.id, "a/p") } returns pipeline(currentVersion = null)

        val refused = shouldThrow<ScheduleException> { adapter.validate(workspace.id, payload("a/p"), mapper.createObjectNode()) }

        refused.code shouldBe ScheduleErrorCodes.PAYLOAD_INVALID
        refused.details["reason"] shouldBe PipelineJobExecutor.POINTER_NULL
    }

    // ------------------------------------------------------------------------------ preparation

    @Test
    fun `preparation - an inactive workspace does not block, an absent pipeline and a NULL pointer do`() {
        every { workspaces.findById(workspace.id) } returns workspace.copy(deactivatedAt = Instant.EPOCH)
        (adapter.prepare(admission()) as Preparation.Refused).let {
            it.reason shouldBe PipelineJobExecutor.WORKSPACE_INACTIVE
            it.block shouldBe false
        }

        every { workspaces.findById(workspace.id) } returns workspace
        every { pipelines.findByNameAnyStatus(workspace.id, "a/p") } returns null
        (adapter.prepare(admission()) as Preparation.Refused).let {
            it.reason shouldBe PipelineJobExecutor.TARGET_NOT_FOUND
            it.block shouldBe true
        }

        every { pipelines.findByNameAnyStatus(workspace.id, "a/p") } returns pipeline(currentVersion = null)
        (adapter.prepare(admission()) as Preparation.Refused).let {
            it.reason shouldBe PipelineJobExecutor.POINTER_NULL
            it.block shouldBe true
        }
    }

    @Test
    fun `preparation - a system identity without pipeline_execute is refused and blocks`() {
        val refused = PermissionResolverInstallation(SystemArmWithoutReads).use { adapter.prepare(admission()) }

        (refused as Preparation.Refused).let {
            it.reason shouldBe PipelineJobExecutor.AUTHORITY_REFUSED
            it.block shouldBe true
        }
    }

    @Test
    fun `a viewer that is not a principal sees every target - the lens is the web's, not the scheduler's`() {
        adapter.visibleTargets(TargetViewer.EVERYONE, workspace.id, listOf("pipeline:a/p", "pipeline:b/q")) shouldBe
            setOf("pipeline:a/p", "pipeline:b/q")
    }

    // ------------------------------------------------------------------------------ fixtures

    private fun outcomeFor(record: ExecutionRecord?): ExecutionOutcome {
        every { executions.findById(workspace.id, executionId) } returns record
        return adapter.inspect(workspace.id, listOf(executionId)).getValue(executionId).also {
            it.shouldBeInstanceOf<ExecutionOutcome>()
        }
    }

    private fun reasonOf(payload: String): Any? =
        shouldThrow<ScheduleException> { adapter.validate(workspace.id, mapper.readTree(payload), mapper.createObjectNode()) }
            .also { it.code shouldBe ScheduleErrorCodes.PAYLOAD_INVALID }
            .details["reason"]

    private fun payload(name: String) = mapper.readTree("""{"pipeline":"$name","version":"current"}""")

    private fun admission() =
        Admission(
            UUID.randomUUID(),
            workspace.id,
            UUID.randomUUID(),
            RunOrigin.MANUAL,
            null,
            Instant.EPOCH,
            "UTC",
            payload("a/p"),
            mapper.createObjectNode(),
        )

    private fun pipeline(currentVersion: Int?): PipelineRecord =
        mockk {
            every { this@mockk.currentVersion } returns currentVersion
            every { name } returns "a/p"
            every { id } returns UUID.randomUUID()
        }

    private fun record(status: ExecutionStatus) =
        ExecutionRecord(
            executionId = executionId,
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 1,
            status = status,
            parametersJson = "{}",
            executedBy = SYSTEM_ID,
            triggeredVia = ExecutionTrigger.SCHEDULE,
        )

    private fun abortEvent(reason: String) =
        ExecutionEventRecord(executionId, 7, "execution_aborted", Instant.EPOCH, """{"reason":"$reason"}""")

    private fun systemRow() =
        User(
            id = SYSTEM_ID,
            email = UserService.SYSTEM_ACTOR_EMAIL,
            displayName = UserService.SYSTEM_ACTOR_DISPLAY_NAME,
            provider = UserService.SYSTEM_PROVIDER,
            providerSubject = UserService.SYSTEM_ACTOR_SUBJECT,
            isActive = true,
            isAdmin = false,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            kind = UserKind.SYSTEM,
        )

    /** A resolver whose system arm holds NOTHING — the configuration defect `scheduler.inspect_refused` names. */
    private object SystemArmWithoutReads : PermissionResolver by RolePermissionsResolver {
        override fun holdsAsSystemActor(
            workspaceId: UUID?,
            permission: co.datapipelines.auth.Permission,
        ): Boolean = false
    }

    private companion object {
        val SYSTEM_ID: UUID = UUID.fromString("5a570000-0000-0000-0000-000000000001")
    }
}
