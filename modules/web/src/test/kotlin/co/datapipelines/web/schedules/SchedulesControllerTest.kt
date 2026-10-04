package co.datapipelines.web.schedules

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.PermissionResolverInstallation
import co.datapipelines.auth.RolePermissionsResolver
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
import co.datapipelines.web.ui.ActorNames
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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
    private val user = UUID.randomUUID()
    private val workspace = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()

    // A REAL ActorNames over a stubbed read: "every §20 response names people in ONE batched
    // read" is a must-call contract, and the name in the answer is what the test asserts.
    private val jdbc = mockk<NamedParameterJdbcTemplate>()
    private val actorNames: ActorNames
    private val controller: SchedulesController

    init {
        every {
            jdbc.query(any<String>(), any<Map<String, Any?>>(), any<RowMapper<Pair<UUID, String>>>())
        } answers { listOf(user to "Alice") }
        actorNames = ActorNames(jdbc)
        controller = SchedulesController(service, audit, mapper, actorNames)
    }

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

    @ParameterizedTest
    @CsvSource("AUTHOR,false", "AUTHOR,true", "VIEWER,false", "VIEWER,true", "PROMOTER,false", "PROMOTER,true")
    fun `notification addresses follow the real permission resolver on get and list`(
        roleName: String,
        list: Boolean,
    ) {
        PermissionResolverInstallation(RolePermissionsResolver).use {
            val role = WorkspaceRole.valueOf(roleName)
            val stored = schedule().copy(notificationRecipients = listOf("private@example.com", "other@example.org"))
            every { service.get(workspace, scheduleId, any()) } returns stored
            every { service.list(workspace, any(), any(), any(), any()) } returns listOf(stored)
            val principal =
                AuthenticatedPrincipal(
                    user,
                    "author@example.com",
                    "Author",
                    AuthMethod.OIDC,
                    "session",
                    workspace = WorkspaceContext(workspace, "acme", role),
                )
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
            val data =
                if (list) {
                    controller
                        .list(null, null, null)
                        .data.items
                        .single()
                } else {
                    controller.get(scheduleId).body!!.data
                }
            val notifications = data["notifications"] as Map<*, *>
            notifications["recipient_count"] shouldBe 2
            notifications["events"] shouldBe listOf("failure", "unknown", "blocked")
            notifications["delivery"] shouldBe mapOf("state" to "off", "reason" to "disabled")
            notifications.containsKey("recipients") shouldBe (role == WorkspaceRole.AUTHOR)
            if (role == WorkspaceRole.AUTHOR) {
                notifications["recipients"] shouldBe stored.notificationRecipients
            } else {
                mapper.writeValueAsString(data).contains("private@example.com") shouldBe false
            }
        }
    }

    // #442 review F1: a key's authority is its KEY ROLE — its workspace context is the VIEWER floor
    // that is never consulted (ApiKeyService.identityContext) — so an mcp author or workspace-admin
    // key reads the addresses, and a promoter or endpoint key does not.
    @ParameterizedTest
    @CsvSource(
        "AUTHOR,MCP,true,false",
        "AUTHOR,MCP,true,true",
        "WORKSPACE_ADMIN,MCP,true,false",
        "WORKSPACE_ADMIN,MCP,true,true",
        "PROMOTER,MCP,false,false",
        "PROMOTER,MCP,false,true",
        "API_CALLER,ENDPOINT,false,false",
        "API_CALLER,ENDPOINT,false,true",
    )
    fun `notification addresses follow the key role for a key principal on get and list`(
        keyRoleName: String,
        kindName: String,
        reads: Boolean,
        list: Boolean,
    ) {
        PermissionResolverInstallation(RolePermissionsResolver).use {
            val stored = schedule().copy(notificationRecipients = listOf("private@example.com", "other@example.org"))
            every { service.get(workspace, scheduleId, any()) } returns stored
            every { service.list(workspace, any(), any(), any(), any()) } returns listOf(stored)
            val principal =
                AuthenticatedPrincipal(
                    userId = user,
                    email = "dpk_key@keys.invalid",
                    displayName = "key",
                    authMethod = AuthMethod.API_KEY,
                    keyId = "dpk_KEY",
                    workspace = WorkspaceContext(workspace, "acme", WorkspaceRole.VIEWER),
                    keyKind = ApiKeyKind.valueOf(kindName),
                    keyRole = KeyRole.valueOf(keyRoleName),
                )
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
            val data =
                if (list) {
                    controller
                        .list(null, null, null)
                        .data.items
                        .single()
                } else {
                    controller.get(scheduleId).body!!.data
                }
            val notifications = data["notifications"] as Map<*, *>
            notifications["recipient_count"] shouldBe 2
            notifications.containsKey("recipients") shouldBe reads
            if (reads) {
                notifications["recipients"] shouldBe stored.notificationRecipients
            } else {
                mapper.writeValueAsString(data).contains("private@example.com") shouldBe false
            }
        }
    }

    @Test
    fun `PUT without notifications reaches service as null and audit has no addresses`() {
        val request = slot<ScheduleRequest>()
        every { service.update(workspace, scheduleId, user, 3, capture(request)) } returns
            schedule().copy(notificationRecipients = listOf("private@example.com"))
        controller.update(scheduleId, BODY, "3")
        request.captured.notifications shouldBe null
        audit.events.single().second["recipient_count"] shouldBe 1
        audit.events.single().second["events"] shouldBe listOf("failure", "unknown", "blocked")
        mapper.writeValueAsString(audit.events).contains("private@example.com") shouldBe false
    }

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
        // #253: the worker's hostname:pid stays in the database and the log, never the response —
        // even for the event that carried one.
        trail.last().containsKey("worker") shouldBe false
        data["payload"] shouldBe run().payload
    }

    @Test
    fun `a schedule and a run name their people beside the ids - one batched read (#261)`() {
        every { service.get(workspace, scheduleId, any()) } returns schedule()
        every { service.runs(workspace, scheduleId, 3, 0, any()) } returns listOf(run().copy(requestedBy = user))

        val schedule = controller.get(scheduleId).body!!.data
        schedule["created_by"] shouldBe user.toString()
        schedule["created_by_name"] shouldBe "Alice"
        schedule["updated_by_name"] shouldBe "Alice"

        val run =
            controller
                .runs(scheduleId, 0, 2)
                .data.items
                .single()
        run["requested_by"] shouldBe user.toString()
        run["requested_by_name"] shouldBe "Alice"
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

    /** A finished run whose execution took 17 ms — what §20.10 answers in one read (#258). */
    private fun finishedRun() =
        run().copy(
            state = RunState.SUCCEEDED,
            executionId = EXECUTION_ID,
            startedAt = AT,
            finishedAt = AT.plusSeconds(20), // the reconciler's stamp — NOT what duration means
            executionStartedAt = AT,
            executionCompletedAt = AT.plusMillis(17),
        )

    @Test
    fun `a finished run carries the execution's own timing and duration - one read, every reader (#258)`() {
        every { service.runs(workspace, scheduleId, 3, 0, any()) } returns listOf(finishedRun())

        val data = controller.runs(scheduleId, 0, 2).data

        val run = data.items.single()
        run["execution_started_at"] shouldBe AT.toString()
        run["execution_completed_at"] shouldBe AT.plusMillis(17).toString()
        run["execution_duration_ms"] shouldBe 17L
    }

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
        val EXECUTION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000e0ec")
        const val BODY =
            """{"name":"finance/daily/revenue","payload":{"pipeline":"finance/revenue","version":"current"},""" +
                """"cron":"0 6 * * *","timezone":"UTC"}"""
    }
}
