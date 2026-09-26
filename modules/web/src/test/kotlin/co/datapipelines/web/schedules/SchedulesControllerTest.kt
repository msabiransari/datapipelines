package co.datapipelines.web.schedules

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.scheduler.MissedRunPolicy
import co.datapipelines.scheduler.Occurrence
import co.datapipelines.scheduler.RunDetail
import co.datapipelines.scheduler.RunOrigin
import co.datapipelines.scheduler.RunState
import co.datapipelines.scheduler.Schedule
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleException
import co.datapipelines.scheduler.ScheduleRequest
import co.datapipelines.scheduler.ScheduleRun
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.scheduler.TrailEvent
import co.datapipelines.scheduler.TrailKind
import co.datapipelines.scheduler.Written
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The schedules REST surface (rest-api.md §20) over a mocked [ScheduleService] — the routing,
 * status and header contract, the request defaults, and the audit trail. The audit sink is a REAL
 * recording one: "every mutation writes one row" is a must-be-called contract, and a strict mock
 * would make a missing row unobservable (MISTAKES.md). Behaviour behind the service is the
 * scheduler module's integration suites'.
 */
class SchedulesControllerTest {
    private val service = mockk<ScheduleService>()
    private val audit = RecordingAudit()
    private val mapper = ObjectMapper()
    private val controller = SchedulesController(service, audit, mapper)

    private val user = UUID.randomUUID()
    private val workspace = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()

    @BeforeEach
    fun signIn() {
        val principal =
            AuthenticatedPrincipal(
                user,
                "a@b.c",
                "A",
                AuthMethod.OIDC,
                "dpk_x",
                workspace = WorkspaceContext(workspace, "acme", WorkspaceRole.AUTHOR),
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    // ------------------------------------------------------------------------------ create

    @Test
    fun `create answers 201 with the schedule, its revision as the ETag, and one audit row`() {
        val request = slot<ScheduleRequest>()
        every { service.create(workspace, user, capture(request), "k-1") } returns Written(schedule(), replayed = false)

        val response = controller.create(BODY, "k-1")

        response.statusCode shouldBe HttpStatus.CREATED
        response.headers.getFirst(HttpHeaders.ETAG) shouldBe "\"3\""
        response.body!!.data["id"] shouldBe scheduleId.toString()
        request.captured.name shouldBe "finance/daily/revenue"
        audit.events.map { it.first } shouldContainExactly listOf(ScheduleAuditEvents.CREATED)
        audit.events.single().second["schedule_id"] shouldBe scheduleId.toString()
    }

    @Test
    fun `an idempotent replay answers 200 with the original and writes no second audit row`() {
        every { service.create(workspace, user, any(), "k-1") } returns Written(schedule(), replayed = true)

        controller.create(BODY, "k-1").statusCode shouldBe HttpStatus.OK

        audit.events.shouldBeEmpty()
    }

    @Test
    fun `the request defaults - executor pipeline, parameters empty, policy skip`() {
        val request = slot<ScheduleRequest>()
        every { service.create(any(), any(), capture(request), null) } returns Written(schedule(), replayed = false)

        controller.create(
            """{"name":"a/b","payload":{"pipeline":"a/p","version":"current"},"cron":"0 6 * * *","timezone":"UTC"}""",
            null,
        )

        request.captured.executor shouldBe "pipeline"
        request.captured.parameters.isObject shouldBe true
        request.captured.parameters.size() shouldBe 0
        request.captured.missedRunPolicy shouldBe "skip"
    }

    @Test
    fun `a body that is not a JSON object, or lacks a required field, is request_invalid naming the field`() {
        shouldThrow<ScheduleException> { controller.create("not json", null) }.let {
            it.code shouldBe ScheduleErrorCodes.REQUEST_INVALID
            it.details["field"] shouldBe "body"
        }
        shouldThrow<ScheduleException> { controller.create("""{"name":"a/b","cron":"0 6 * * *","timezone":"UTC"}""", null) }
            .details["field"] shouldBe "payload"
        shouldThrow<ScheduleException> {
            controller.create("""{"name":7,"payload":{},"cron":"0 6 * * *","timezone":"UTC"}""", null)
        }.details["field"] shouldBe "name"
        verify(exactly = 0) { service.create(any(), any(), any(), any()) }
        audit.events.shouldBeEmpty()
    }

    // ------------------------------------------------------------------------------ edit / delete

    @Test
    fun `edit reads the revision from If-Match - bare, quoted or weak - and audits`() {
        listOf("3", "\"3\"", "W/\"3\"").forEach { header ->
            every { service.update(workspace, scheduleId, user, 3, any()) } returns schedule(revision = 4)
            controller.update(scheduleId, BODY, header).headers.getFirst(HttpHeaders.ETAG) shouldBe "\"4\""
        }
        audit.events.map { it.first }.distinct() shouldContainExactly listOf(ScheduleAuditEvents.UPDATED)
    }

    @Test
    fun `a missing If-Match is refused before the service, and a non-revision one is request_invalid`() {
        shouldThrow<ApiException> { controller.update(scheduleId, BODY, null) }
        shouldThrow<ScheduleException> { controller.update(scheduleId, BODY, "\"abc\"") }.code shouldBe ScheduleErrorCodes.REQUEST_INVALID
        shouldThrow<ScheduleException> { controller.delete(scheduleId, "0") }.code shouldBe ScheduleErrorCodes.REQUEST_INVALID
        verify(exactly = 0) { service.update(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { service.delete(any(), any(), any(), any()) }
        audit.events.shouldBeEmpty()
    }

    @Test
    fun `a stale revision surfaces the service's conflict and writes no audit row`() {
        every { service.update(workspace, scheduleId, user, 2, any()) } throws
            ScheduleException(ScheduleErrorCodes.REVISION_CONFLICT, "changed", mapOf("current_revision" to 3))

        shouldThrow<ScheduleException> { controller.update(scheduleId, BODY, "2") }.code shouldBe ScheduleErrorCodes.REVISION_CONFLICT
        audit.events.shouldBeEmpty()
    }

    @Test
    fun `delete passes the revision and audits the schedule id`() {
        every { service.delete(workspace, scheduleId, user, 3) } returns Unit

        controller.delete(scheduleId, "\"3\"")

        verify(exactly = 1) { service.delete(workspace, scheduleId, user, 3) }
        audit.events.single().first shouldBe ScheduleAuditEvents.DELETED
    }

    // ------------------------------------------------------------------------------ operational controls

    @Test
    fun `pause, resume and unblock each return the schedule with its ETag and write their own audit row`() {
        every { service.pause(workspace, scheduleId, user) } returns schedule(enabled = false)
        every { service.resume(workspace, scheduleId, user) } returns schedule()
        every { service.unblock(workspace, scheduleId, user) } returns schedule()

        controller.pause(scheduleId).body!!.data["condition"] shouldBe "paused"
        controller.resume(scheduleId).body!!.data["condition"] shouldBe "enabled"
        controller.unblock(scheduleId).statusCode shouldBe HttpStatus.OK

        audit.events.map { it.first } shouldContainExactly
            listOf(ScheduleAuditEvents.PAUSED, ScheduleAuditEvents.RESUMED, ScheduleAuditEvents.UNBLOCKED)
    }

    @Test
    fun `a blocked schedule renders its block, and blocked wins over paused`() {
        every { service.get(workspace, scheduleId, any()) } returns
            schedule(enabled = false).copy(blockedReason = "run_unknown", blockedAt = AT, blockedRunId = RUN_ID)

        val data = controller.get(scheduleId).body!!.data

        data["condition"] shouldBe "blocked"
        data["blocked"] shouldBe mapOf("reason" to "run_unknown", "at" to AT.toString(), "run_id" to RUN_ID.toString())
    }

    // ------------------------------------------------------------------------------ run now

    @Test
    fun `Run now answers 202 with the queued run and audits it - a replay answers 200 and does not`() {
        every { service.runNow(workspace, scheduleId, user, "r-1") } returns Written(run(), replayed = false)
        every { service.runNow(workspace, scheduleId, user, "r-2") } returns Written(run(), replayed = true)

        val first = controller.runNow(scheduleId, "r-1")
        val replay = controller.runNow(scheduleId, "r-2")

        first.statusCode shouldBe HttpStatus.ACCEPTED
        first.body!!.data["origin"] shouldBe "manual"
        first.body!!.data["requested_by"] shouldBe user.toString()
        replay.statusCode shouldBe HttpStatus.OK
        audit.events.map { it.first } shouldContainExactly listOf(ScheduleAuditEvents.RUN_REQUESTED)
        audit.events.single().second["run_id"] shouldBe RUN_ID.toString()
    }

    // ------------------------------------------------------------------------------ reads

    @Test
    fun `the preview renders each occurrence as the instant, the local time and the offset`() {
        every { service.preview("30 2 * * *", "America/New_York", 5) } returns
            listOf(Occurrence(Instant.parse("2026-03-08T07:00:00Z"), ZoneOffset.ofHours(-4), LocalDateTime.parse("2026-03-08T03:00")))

        val data = controller.preview(" 30 2 * * * ", "America/New_York", null).data

        data["cron"] shouldBe "30 2 * * *"
        data["occurrences"] shouldBe listOf(mapOf("at" to "2026-03-08T07:00:00Z", "local" to "2026-03-08T03:00", "offset" to "-04:00"))
    }

    @Test
    fun `the list pages with one extra row to learn has_more`() {
        every { service.list(workspace, "finance/", 3, 0, any()) } returns List(3) { schedule() }

        val data = controller.list(" finance/ ", 0, 2).data

        data.items.size shouldBe 2
        data.pagination.hasMore shouldBe true
    }

    @Test
    fun `one run carries its frozen payload and its trail in order`() {
        every { service.run(workspace, scheduleId, RUN_ID, any()) } returns
            RunDetail(
                run(),
                listOf(
                    TrailEvent(RUN_ID, 1, TrailKind.RECORDED, null, AT, null, mapper.createObjectNode().put("origin", "manual")),
                    TrailEvent(RUN_ID, 2, TrailKind.CLAIMED, null, AT, "w-1", mapper.createObjectNode()),
                ),
            )

        val data = controller.run(scheduleId, RUN_ID).data

        @Suppress("UNCHECKED_CAST")
        val trail = data["trail"] as List<Map<String, Any?>>
        trail.map { it["seq"] } shouldContainExactly listOf(1, 2)
        trail.map { it["kind"] } shouldContainExactly listOf("recorded", "claimed")
        trail.last()["worker"] shouldBe "w-1"
        data["payload"] shouldBe run().payload
    }

    // ------------------------------------------------------------------------------ fixtures

    private fun schedule(
        revision: Int = 3,
        enabled: Boolean = true,
    ) = Schedule(
        id = scheduleId,
        workspaceId = workspace,
        name = "finance/daily/revenue",
        revision = revision,
        executorId = "pipeline",
        payloadSchemaVersion = 1,
        payload = mapper.readTree("""{"pipeline":"finance/revenue","version":"current"}"""),
        parameters = mapper.createObjectNode(),
        targetRef = "pipeline:finance/revenue",
        cron = "0 6 * * *",
        timezone = "UTC",
        missedRunPolicy = MissedRunPolicy.SKIP,
        enabled = enabled,
        blockedReason = null,
        blockedAt = null,
        blockedRunId = null,
        nextDueAt = AT,
        createdBy = user,
        updatedBy = user,
        createdAt = AT,
        updatedAt = AT,
        deletedAt = null,
    )

    private fun run() =
        ScheduleRun(
            id = RUN_ID,
            scheduleId = scheduleId,
            workspaceId = workspace,
            origin = RunOrigin.MANUAL,
            scheduledAt = null,
            referenceAt = AT,
            referenceTimezone = "UTC",
            admitBy = AT.plusSeconds(600),
            scheduleRevision = 3,
            executorId = "pipeline",
            payloadSchemaVersion = 1,
            payload = mapper.readTree("""{"pipeline":"finance/revenue","version":"current"}"""),
            parameters = mapper.createObjectNode(),
            prepared = null,
            actorUserId = UUID.randomUUID(),
            requestedBy = user,
            executionId = null,
            state = RunState.QUEUED,
            reason = null,
            worker = null,
            attempts = 0,
            createdAt = AT,
            claimedAt = null,
            startedAt = null,
            finishedAt = null,
            updatedAt = AT,
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
        val AT: Instant = Instant.parse("2026-09-25T06:00:00Z")
        val RUN_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000f00d")
        const val BODY =
            """{"name":"finance/daily/revenue","payload":{"pipeline":"finance/revenue","version":"current"},""" +
                """"cron":"0 6 * * *","timezone":"UTC"}"""
    }
}
