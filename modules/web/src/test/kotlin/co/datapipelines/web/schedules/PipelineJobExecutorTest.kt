package co.datapipelines.web.schedules

import co.datapipelines.auth.PermissionResolver
import co.datapipelines.auth.PermissionResolverInstallation
import co.datapipelines.auth.RolePermissionsResolver
import co.datapipelines.auth.User
import co.datapipelines.auth.UserKind
import co.datapipelines.auth.UserService
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionEventRecord
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionReference
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.scheduler.Admission
import co.datapipelines.scheduler.ExecutionOutcome
import co.datapipelines.scheduler.Launch
import co.datapipelines.scheduler.Preparation
import co.datapipelines.scheduler.RunOrigin
import co.datapipelines.scheduler.RunState
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleException
import co.datapipelines.scheduler.StartOutcome
import co.datapipelines.scheduler.TargetViewer
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
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
    private val pipelineService = mockk<PipelineService>()
    private val runner = mockk<RecordingExecutionRunner>()
    private val executorConfig = mockk<ExecutorConfig>()
    private val adapter =
        PipelineJobExecutor(
            pipelines = pipelines,
            pipelineService = pipelineService,
            workspaces = workspaces,
            users = users,
            runner = runner,
            executions = executions,
            events = events,
            lens = mockk(),
            executorConfig = executorConfig,
            scope = CoroutineScope(Dispatchers.Unconfined),
            mapper = mapper,
        )

    private val workspace = Workspace(UUID.randomUUID(), "acme", "Acme", false, null, false, Instant.EPOCH)
    private val executionId = UUID.randomUUID()
    private val recordId = UUID.randomUUID()

    init {
        every { users.systemActor() } returns systemRow()
        every { workspaces.findById(workspace.id) } returns workspace
    }

    // ------------------------------------------------------------------------------ R6 — inspect

    @Test
    fun `R6 - success, failure and an absent record map as the table says`() {
        val success = record(ExecutionStatus.SUCCESS)
        val failure = record(ExecutionStatus.FAILED)
        outcomeFor(success) shouldBe
            ExecutionOutcome.Finished(RunState.SUCCEEDED, null, success.startedAt, success.completedAt)
        outcomeFor(failure) shouldBe
            ExecutionOutcome.Finished(RunState.FAILED, PipelineJobExecutor.EXECUTION_FAILED, failure.startedAt, failure.completedAt)
        outcomeFor(record(ExecutionStatus.RUNNING)) shouldBe ExecutionOutcome.Running
        outcomeFor(null) shouldBe ExecutionOutcome.Absent
    }

    @Test
    fun `R6 - an abort is cancelled when a person asked, aborted with its reason when the drain did`() {
        val record = record(ExecutionStatus.ABORTED)
        every { events.findByExecution(executionId) } returns listOf(abortEvent("cancelled"))
        outcomeFor(record) shouldBe ExecutionOutcome.Finished(RunState.CANCELLED, "cancelled", record.startedAt, record.completedAt)

        every { events.findByExecution(executionId) } returns listOf(abortEvent("shutdown"))
        outcomeFor(record) shouldBe ExecutionOutcome.Finished(RunState.ABORTED, "shutdown", record.startedAt, record.completedAt)
    }

    @Test
    fun `A5 - the stale sweeper's instance_lost is unknown, never a conclusive abort, and reads no events`() {
        val record = record(ExecutionStatus.ABORTED).copy(errorJson = """{"code":"pipeline.execution.instance_lost"}""")

        outcomeFor(record) shouldBe
            ExecutionOutcome.Finished(RunState.UNKNOWN, PipelineJobExecutor.INSTANCE_LOST, record.startedAt, record.completedAt)
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
    fun `a pipeline with no current version cannot be scheduled - target_not_released at save`() {
        every { pipelines.findByNameAnyStatus(workspace.id, "a/p") } returns pipeline(currentVersion = null)

        val refused = shouldThrow<ScheduleException> { adapter.validate(workspace.id, payload("a/p"), mapper.createObjectNode()) }

        refused.code shouldBe ScheduleErrorCodes.TARGET_NOT_RELEASED
        refused.details shouldBe mapOf("pipeline" to "a/p")
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

    // ------------------------------------------------------------------------------ bindings (slice 3)

    @Test
    fun `save - a keyword binding on a required DATE parameter passes the binder, on a STRING it is type_mismatch`() {
        val datePipeline = executable("as_of_date" to LogicalType.DATE)
        val stringPipeline = executable("as_of_date" to LogicalType.STRING)
        every { pipelines.findByNameAnyStatus(workspace.id, "a/p") } returns datePipeline.record
        every { pipelines.findVersionDetail(workspace.id, datePipeline.record.id, 1) } returns detail()
        every { pipelineService.findExecutable(workspace.id, ReadLens.Everything, datePipeline.record, 1) } returns datePipeline

        adapter.validate(
            workspace.id,
            payloadJson(todayBindingJson),
            mapper.createObjectNode(),
        ) shouldBe "pipeline:a/p"

        every { pipelineService.findExecutable(workspace.id, ReadLens.Everything, datePipeline.record, 1) } returns stringPipeline
        val refused =
            shouldThrow<ScheduleException> {
                adapter.validate(
                    workspace.id,
                    payloadJson(todayBindingJson),
                    mapper.createObjectNode(),
                )
            }
        refused.code shouldBe ScheduleErrorCodes.BINDING_INVALID
        refused.details["reason"] shouldBe "type_mismatch"
        refused.details["parameter"] shouldBe "as_of_date"
    }

    @Test
    fun `save - the same name in parameters and parameter_bindings is the conflict, and a reference key is payload_invalid`() {
        val bound = executable("as_of_date" to LogicalType.DATE)
        every { pipelines.findByNameAnyStatus(workspace.id, "a/p") } returns bound.record
        every { pipelines.findVersionDetail(workspace.id, bound.record.id, 1) } returns detail()
        every { pipelineService.findExecutable(workspace.id, ReadLens.Everything, bound.record, 1) } returns bound

        val conflict =
            shouldThrow<ScheduleException> {
                adapter.validate(
                    workspace.id,
                    payloadJson(literalBindingJson),
                    mapper.readTree("""{"as_of_date":"2026-02-02"}"""),
                )
            }
        conflict.code shouldBe ScheduleErrorCodes.BINDING_CONFLICT
        conflict.details["parameter"] shouldBe "as_of_date"

        reasonOf("""{"pipeline":"a/p","version":"current","reference_at":"2026-01-01T00:00:00Z"}""") shouldBe "unknown_field"
        reasonOf(referenceSpoofJson) shouldBe "unknown_field"
    }

    @Test
    fun `prepare - bindings resolve on the frozen reference and the snapshot carries resolved_parameters`() {
        val bound = executable("as_of_date" to LogicalType.DATE, "previous_date" to LogicalType.DATE)
        every { pipelines.findByNameAnyStatus(workspace.id, "a/p") } returns bound.record
        every { pipelines.findVersionDetail(workspace.id, bound.record.id, 1) } returns detail()
        every { pipelineService.findExecutable(workspace.id, ReadLens.Everything, bound.record, 1) } returns bound

        // The record's New York example: the occurrence 2026-09-22 23:55 NY, the actual start the next day.
        val prepared =
            adapter.prepare(
                admission(
                    payload =
                        payloadJson(
                            """{"pipeline":"a/p","version":"current","parameter_bindings":""" +
                                """{"as_of_date":{"source":"keyword","name":"TODAY"},""" +
                                """"previous_date":{"source":"keyword","name":"YESTERDAY"}}}""",
                        ),
                    referenceAt = Instant.parse("2026-09-23T03:55:00Z"),
                    referenceTimezone = "America/New_York",
                ),
            )

        val snapshot = (prepared as Preparation.Prepared).snapshot
        snapshot["resolved_parameters"]["as_of_date"].asText() shouldBe "2026-09-22"
        snapshot["resolved_parameters"]["previous_date"].asText() shouldBe "2026-09-21"
    }

    @Test
    fun `prepare - a binding the current version no longer declares is parameters_invalid, naming the binding`() {
        val noLonger = executable("other_date" to LogicalType.DATE)
        every { pipelines.findByNameAnyStatus(workspace.id, "a/p") } returns noLonger.record
        every { pipelines.findVersionDetail(workspace.id, noLonger.record.id, 1) } returns detail()
        every { pipelineService.findExecutable(workspace.id, ReadLens.Everything, noLonger.record, 1) } returns noLonger

        val refused =
            adapter.prepare(
                admission(
                    payload = payloadJson(todayBindingJson),
                    referenceTimezone = "UTC",
                ),
            )

        (refused as Preparation.Refused).let {
            it.reason shouldBe PipelineJobExecutor.PARAMETERS_INVALID
            it.block shouldBe true
            it.message.contains("as_of_date") shouldBe true
        }
    }

    @Test
    fun `start - the request carries the resolved parameters and the frozen reference`() {
        val bound = executable("as_of_date" to LogicalType.DATE)
        every { pipelines.findById(workspace.id, bound.record.id) } returns bound.record
        every { pipelines.findVersionDetail(workspace.id, bound.record.id, 1) } returns detail()
        every { pipelineService.findExecutable(workspace.id, ReadLens.Everything, bound.record, 1) } returns bound
        every { executorConfig.result } returns mockk { every { ttlMaxSeconds } returns 3600 }
        var captured: ExecuteRequest? = null
        coEvery { runner.run(any(), any(), any(), any(), any()) } coAnswers {
            captured = firstArg()
            arg<(UUID) -> Unit>(4).invoke(requireNotNull(captured!!.executionId))
            ExecutionResult(
                executionId = captured!!.executionId!!,
                status = ExecutionStatus.RUNNING,
                nodeStats = emptyList(),
                resultRef = null,
                startedAt = Instant.now(),
                completedAt = Instant.now(),
                durationMs = 1,
            )
        }

        val outcome =
            adapter.start(
                Launch(
                    admission(
                        payload = payload("a/p"),
                        referenceAt = Instant.parse("2026-09-23T03:55:00Z"),
                        referenceTimezone = "America/New_York",
                    ),
                    executionId = executionId,
                    snapshot =
                        mapper.readTree(
                            """{"pipeline_id":"${bound.record.id}","version":1,"body_sha256":"h",""" +
                                """"resolved_parameters":{"as_of_date":"2026-09-22"}}""",
                        ),
                    capacity = mockk(),
                ),
            )

        outcome.shouldBeInstanceOf<StartOutcome.Started>()
        captured!!.reference shouldBe ExecutionReference(Instant.parse("2026-09-23T03:55:00Z"), ZoneId.of("America/New_York"))
        captured!!.parameters["as_of_date"] shouldBe TextNode("2026-09-22")
    }

    @Test
    fun `start - a refusal before the row exists carries only catalogued values, never the exception's text (#253)`() {
        val bound = executable("as_of_date" to LogicalType.DATE)
        every { pipelines.findById(workspace.id, bound.record.id) } returns bound.record
        every { pipelines.findVersionDetail(workspace.id, bound.record.id, 1) } returns detail()
        every { pipelineService.findExecutable(workspace.id, ReadLens.Everything, bound.record, 1) } returns bound
        every { executorConfig.result } returns mockk { every { ttlMaxSeconds } returns 3600 }

        fun launch() =
            Launch(
                admission(payload = payload("a/p")),
                executionId = executionId,
                snapshot = mapper.readTree("""{"pipeline_id":"${bound.record.id}","version":1,"body_sha256":"h"}"""),
                capacity = mockk(),
            )

        // One of ours: the trail may name the catalogued code and nothing else.
        coEvery { runner.run(any(), any(), any(), any(), any()) } coAnswers {
            throw DatapipelinesException("pipeline.execution.driver_exploded", "ORA-01756: quoted string not properly terminated")
        }
        val coded = adapter.start(launch()).shouldBeInstanceOf<StartOutcome.NotStarted>()
        coded.reason shouldBe PipelineJobExecutor.START_REFUSED
        coded.block shouldBe true
        coded.message shouldBe "pipeline.execution.driver_exploded"

        // Anything else: the fixed reason — never the exception's message.
        coEvery { runner.run(any(), any(), any(), any(), any()) } coAnswers {
            throw IllegalStateException("poucha pond driver exploded mid-connect")
        }
        val uncoded = adapter.start(launch()).shouldBeInstanceOf<StartOutcome.NotStarted>()
        uncoded.message shouldBe PipelineJobExecutor.LAUNCH_REFUSED_WITHOUT_CODE
        uncoded.message.contains("poucha") shouldBe false
    }

    @Test
    fun `start - a bindings-carrying schedule whose snapshot lacks resolved_parameters never launches raw (#269)`() {
        val bound = executable("as_of_date" to LogicalType.DATE)
        every { pipelines.findById(workspace.id, bound.record.id) } returns bound.record
        every { pipelines.findVersionDetail(workspace.id, bound.record.id, 1) } returns detail()
        every { pipelineService.findExecutable(workspace.id, ReadLens.Everything, bound.record, 1) } returns bound
        every { executorConfig.result } returns mockk { every { ttlMaxSeconds } returns 3600 }
        val outcome =
            adapter.start(
                Launch(
                    admission(
                        payload = payloadJson(todayBindingJson),
                        referenceAt = Instant.parse("2026-09-23T03:55:00Z"),
                        referenceTimezone = "America/New_York",
                    ),
                    executionId = executionId,
                    // A slice-3 prepare always froze the map; its absence is a rolling deploy
                    // between prepare and start.
                    snapshot = mapper.readTree("""{"pipeline_id":"${bound.record.id}","version":1,"body_sha256":"h"}"""),
                    capacity = mockk(),
                ),
            )

        val refused = outcome.shouldBeInstanceOf<StartOutcome.NotStarted>()
        refused.reason shouldBe PipelineJobExecutor.SNAPSHOT_UNRESOLVED
        refused.block shouldBe false // nothing about the schedule is wrong — the next occurrence re-prepares
        coVerify(exactly = 0) { runner.run(any(), any(), any(), any(), any()) }
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

    /** A payload spelled out in full — bindings included. */
    private fun payloadJson(json: String) = mapper.readTree(json)

    private val todayBindingJson =
        """{"pipeline":"a/p","version":"current","parameter_bindings":""" +
            """{"as_of_date":{"source":"keyword","name":"TODAY"}}}"""

    private val literalBindingJson =
        """{"pipeline":"a/p","version":"current","parameter_bindings":""" +
            """{"as_of_date":{"source":"literal","value":"2026-01-01"}}}"""

    private val referenceSpoofJson =
        """{"pipeline":"a/p","version":"current","parameter_bindings":""" +
            """{"as_of_date":{"source":"keyword","name":"TODAY","reference_at":"2026-01-01T00:00:00Z"}}}"""

    private fun admission(
        payload: com.fasterxml.jackson.databind.JsonNode = payload("a/p"),
        parameters: com.fasterxml.jackson.databind.JsonNode = mapper.createObjectNode(),
        referenceAt: Instant = Instant.EPOCH,
        referenceTimezone: String = "UTC",
    ) = Admission(
        UUID.randomUUID(),
        workspace.id,
        UUID.randomUUID(),
        RunOrigin.MANUAL,
        null,
        referenceAt,
        referenceTimezone,
        payload,
        parameters,
    )

    /** A record + parsed body declaring [params], the shape `findExecutable` returns. */
    private fun executable(vararg params: Pair<String, LogicalType>): PipelineService.ExecutablePipeline {
        // A REAL record, not a mock: `start` reads the id back out of the frozen snapshot, and a
        // mock's unstubbed getter answer would not survive the string interpolation.
        val record =
            PipelineRecord(
                id = recordId,
                name = "a/p",
                displayName = "A P",
                description = "",
                ownerId = SYSTEM_ID,
                currentVersion = 1,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        val body =
            Pipeline(
                schemaVersion = 1,
                name = "a/p",
                displayName = "A P",
                description = "",
                settings = PipelineSettings(),
                parameters = params.toMap().mapValues { Parameter(type = it.value) },
                nodes = emptyList(),
            )
        return PipelineService.ExecutablePipeline(record, 1, "{}", body)
    }

    private fun detail() =
        mockk<PipelineVersionDetail> {
            every { status } returns PipelineVersionStatus.RELEASED
            every { bodyHash } returns "h"
        }

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
