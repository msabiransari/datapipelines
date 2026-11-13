package co.datapipelines.web.schedules

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleException
import co.datapipelines.scheduler.ScheduleRequest
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ScheduleLakeAppendAuthorizationTest {
    private val workspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private val pipelines = mockk<PipelineRepository>()
    private val service = mockk<PipelineService>()
    private val subject = ScheduleLakeAppendAuthorization(pipelines, service)
    private val record = PipelineRecord(id, "test/pipeline", "Pipeline", "Test", actor, 3, Instant.EPOCH, Instant.EPOCH)
    private var writes = true
    private val mapper = JsonMapper.builder().build()
    private val request =
        ScheduleRequest(
            "test/schedule",
            "pipeline",
            // #786 D.1: the pin (2) is not CURRENT (3) — the PINNED version's body decides; CURRENT is never read.
            mapper.readTree("""{"pipeline":"test/pipeline","version":2}"""),
            mapper.createObjectNode(),
            "0 0 * * *",
            "UTC",
            "skip",
        )

    @BeforeEach
    fun setUp() {
        every { pipelines.findByNameAnyStatus(workspace, record.name) } returns record
        every { service.findExecutable(workspace, ReadLens.Everything, record, 2) } returns
            mockk {
                every { writesManagedLake } answers { writes }
            }
    }

    @Test
    fun `saving a writer asks for lake authority and read-only schedules do not`() {
        val viewer = principal(WorkspaceRole.VIEWER)
        shouldThrow<DatapipelinesException> { subject.check(viewer, request) }.code shouldBe PipelineErrorCodes.Lake.APPEND_FORBIDDEN
        subject.check(principal(WorkspaceRole.AUTHOR), request)
        writes = false
        subject.check(viewer, request)
    }

    @Test
    fun `missing target or pinned body is refused instead of guessing read-only`() {
        every { pipelines.findByNameAnyStatus(workspace, record.name) } returns null
        shouldThrow<ScheduleException> { subject.check(principal(WorkspaceRole.AUTHOR), request) }.code shouldBe
            ScheduleErrorCodes.TARGET_NOT_FOUND
        every { pipelines.findByNameAnyStatus(workspace, record.name) } returns record
        every { service.findExecutable(workspace, ReadLens.Everything, record, 2) } returns null
        shouldThrow<ScheduleException> { subject.check(principal(WorkspaceRole.AUTHOR), request) }.code shouldBe
            ScheduleErrorCodes.TARGET_NOT_FOUND
    }

    @Test
    fun `payload parsing keeps the pipeline job's established refusals`() {
        val invalid = request.copy(payload = mapper.readTree("""{"pipeline":"test/pipeline","version":"latest"}"""))
        shouldThrow<ScheduleException> { subject.check(principal(WorkspaceRole.AUTHOR), invalid) }.code shouldBe
            ScheduleErrorCodes.PAYLOAD_INVALID
        verify(exactly = 0) { pipelines.findByNameAnyStatus(any(), any()) }
    }

    @Test
    fun `other executor types do not resolve a pipeline target`() {
        subject.check(principal(WorkspaceRole.VIEWER), request.copy(executor = "other"))
        verify(exactly = 0) { pipelines.findByNameAnyStatus(any(), any()) }
    }

    private fun principal(role: WorkspaceRole): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            actor,
            "actor799@example.com",
            "Actor799",
            AuthMethod.OIDC,
            workspace = WorkspaceContext(workspace, "test", role),
        )
}
