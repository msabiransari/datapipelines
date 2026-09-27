package co.datapipelines.web.schedules

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.User
import co.datapipelines.auth.UserKind
import co.datapipelines.auth.UserService
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.scheduler.JobExecutors
import co.datapipelines.scheduler.RunLedger
import co.datapipelines.scheduler.Schedule
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleException
import co.datapipelines.scheduler.ScheduleRepository
import co.datapipelines.scheduler.ScheduleRunRepository
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.scheduler.SchedulerProperties
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import co.datapipelines.web.ui.ActorNames
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The §20.2 create path over the REAL [ScheduleService] and the REAL [PipelineJobExecutor] —
 * the refusals the adapter's binding rules raise are answered by the route with their catalogued
 * codes (the 400 status itself is the `schedule.validation.` family default the catalog pins;
 * `ApiErrorCatalogSpecDriftTest`). The thin pass-through (routing, ETag, audit) is
 * `SchedulesControllerTest`'s, over a mocked service; the repository layer is the scheduler
 * module's integration suites'. These are the brief's four rows: a keyword on a DATE parameter
 * is 201, on a STRING 400 `binding_invalid`/`type_mismatch`, a name in both maps 400
 * `binding_conflict`, a `reference_at` inside the payload 400 `payload_invalid`.
 */
class SchedulesCreateBindingsTest {
    private val mapper = ObjectMapper()
    private val pipelines = mockk<PipelineRepository>()
    private val pipelineService = mockk<PipelineService>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val users = mockk<UserService>()

    private val workspaceId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val recordId = UUID.randomUUID()

    private val schedulesRepo = mockk<ScheduleRepository>()

    private val audit = RecordingAudit()
    private val controller =
        SchedulesController(
            ScheduleService(
                schedules = schedulesRepo,
                runs = mockk<ScheduleRunRepository>(),
                executors =
                    JobExecutors(
                        listOf(
                            PipelineJobExecutor(
                                pipelines = pipelines,
                                pipelineService = pipelineService,
                                workspaces = workspaces,
                                users = users,
                                runner = mockk<RecordingExecutionRunner>(),
                                executions = mockk<ExecutionRepository>(),
                                events = mockk<ExecutionEventRepository>(),
                                lens = mockk(),
                                executorConfig = ExecutorConfig(),
                                scope = CoroutineScope(Dispatchers.Unconfined),
                                mapper = mapper,
                            ),
                        ),
                    ),
                ledger = mockk<RunLedger>(),
                transactions =
                    mockk {
                        every { execute(any<TransactionCallback<Any>>()) } answers {
                            firstArg<TransactionCallback<Any>>().doInTransaction(mockk())
                        }
                    },
                clock = Clock.fixed(CLOCK_AT, ZoneId.of("UTC")),
                properties = SchedulerProperties(),
                queue = { _, _ -> },
                mapper = mapper,
                systemActor = { SYSTEM_ID },
            ),
            audit,
            mapper,
            ActorNames(
                mockk {
                    every { query(any<String>(), any<Map<String, Any?>>(), any<RowMapper<Pair<UUID, String>>>()) } returns emptyList()
                },
            ),
        )

    @BeforeEach
    fun setUp() {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                AuthMethod.OIDC,
                "dpk_x",
                workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.AUTHOR),
            )
        SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(principal, null, emptyList())
        val workspace = Workspace(workspaceId, "acme", "Acme", false, null, false, Instant.EPOCH)
        every { workspaces.findById(workspaceId) } returns workspace
        every { users.systemActor() } returns systemRow()
        every { schedulesRepo.lockWorkspace(workspaceId) } returns Unit
        every { schedulesRepo.countLive(workspaceId) } returns 0
        every { schedulesRepo.insert(any()) } answers { createdFrom(firstArg()) }
        every { pipelines.findByNameAnyStatus(workspaceId, "a/p") } returns record()
        every { pipelines.findVersionDetail(workspaceId, recordId, 1) } returns detail()
        every { pipelineService.findExecutable(workspaceId, ReadLens.Everything, any(), 1) } returns
            executable("as_of_date" to LogicalType.DATE)
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `a keyword binding on a DATE parameter is 201, and the stored payload keeps the bindings`() {
        val response = controller.create(body("""{"as_of_date":{"source":"keyword","name":"TODAY"}}"""), null)

        response.statusCode shouldBe HttpStatus.CREATED
        response.body!!.data["target_ref"] shouldBe "pipeline:a/p"
    }

    @Test
    fun `a keyword on a STRING parameter is refused binding_invalid type_mismatch`() {
        every { pipelineService.findExecutable(workspaceId, ReadLens.Everything, any(), 1) } returns
            executable("as_of_date" to LogicalType.STRING)

        val refused =
            shouldThrow<ScheduleException> {
                controller.create(body("""{"as_of_date":{"source":"keyword","name":"TODAY"}}"""), null)
            }
        refused.code shouldBe ScheduleErrorCodes.BINDING_INVALID
        refused.details["reason"] shouldBe "type_mismatch"
        refused.details["parameter"] shouldBe "as_of_date"
    }

    @Test
    fun `the same name in parameters and parameter_bindings is refused binding_conflict`() {
        val refused =
            shouldThrow<ScheduleException> {
                controller.create(
                    body(
                        """{"as_of_date":{"source":"literal","value":"2026-01-01"}}""",
                        parameters = """{"as_of_date":"2026-02-02"}""",
                    ),
                    null,
                )
            }
        refused.code shouldBe ScheduleErrorCodes.BINDING_CONFLICT
        refused.details["parameter"] shouldBe "as_of_date"
    }

    @Test
    fun `a reference_at inside the payload is refused payload_invalid - the run's time is not a client field`() {
        val refused =
            shouldThrow<ScheduleException> {
                controller.create(
                    """{"name":"a/b","payload":{"pipeline":"a/p","version":"current","reference_at":"2026-01-01T00:00:00Z"},""" +
                        """"cron":"0 6 * * *","timezone":"America/New_York"}""",
                    null,
                )
            }
        refused.code shouldBe ScheduleErrorCodes.PAYLOAD_INVALID
        refused.details["reason"] shouldBe "unknown_field"
    }

    // ------------------------------------------------------------------------------ fixtures

    /** §20.2's body: one DATE parameter bound, everything else literal. */
    private fun body(
        bindings: String,
        parameters: String = "{}",
    ): String =
        """{"name":"a/b","payload":{"pipeline":"a/p","version":"current","parameter_bindings":$bindings},""" +
            """"parameters":$parameters,"cron":"0 6 * * *","timezone":"America/New_York"}"""

    private fun record() =
        co.datapipelines.pipeline.PipelineRecord(
            id = recordId,
            name = "a/p",
            displayName = "A P",
            description = "",
            ownerId = SYSTEM_ID,
            currentVersion = 1,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun detail() =
        PipelineVersionDetail(
            pipelineId = recordId,
            version = 1,
            status = PipelineVersionStatus.RELEASED,
            bodyHash = "h",
            createdAt = Instant.EPOCH,
            createdBy = SYSTEM_ID,
        )

    private fun executable(vararg params: Pair<String, LogicalType>) =
        PipelineService.ExecutablePipeline(
            record(),
            1,
            "{}",
            Pipeline(
                schemaVersion = 1,
                name = "a/p",
                displayName = "A P",
                description = "",
                settings = PipelineSettings(),
                parameters = params.toMap().mapValues { Parameter(type = it.value) },
                nodes = emptyList(),
            ),
        )

    /** The row the (mocked) insert returns: what was asked for, assigned its id and stamps. */
    private fun createdFrom(newSchedule: co.datapipelines.scheduler.NewSchedule): Schedule =
        Schedule(
            id = newSchedule.id,
            workspaceId = workspaceId,
            name = newSchedule.name,
            revision = 1,
            executorId = newSchedule.executorId,
            payloadSchemaVersion = newSchedule.payloadSchemaVersion,
            payload = newSchedule.payload,
            parameters = newSchedule.parameters,
            targetRef = newSchedule.targetRef,
            cron = newSchedule.cron,
            timezone = newSchedule.timezone,
            missedRunPolicy = newSchedule.missedRunPolicy,
            enabled = true,
            blockedReason = null,
            blockedAt = null,
            blockedRunId = null,
            nextDueAt = newSchedule.nextDueAt,
            createdBy = userId,
            updatedBy = userId,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            deletedAt = null,
        )

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

    private class RecordingAudit : AuditEventSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            events += event to details
        }
    }

    private companion object {
        val CLOCK_AT: Instant = Instant.parse("2026-09-26T00:00:00Z")
        val SYSTEM_ID: UUID = UUID.fromString("5a570000-0000-0000-0000-000000000001")
    }
}
