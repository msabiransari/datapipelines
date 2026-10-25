package co.datapipelines.web.schedules

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.PermissionResolverInstallation
import co.datapipelines.auth.RolePermissionsResolver
import co.datapipelines.auth.ScopeInterceptor
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.scheduler.Delivery
import co.datapipelines.scheduler.DeliveryCodes
import co.datapipelines.scheduler.DeliveryState
import co.datapipelines.scheduler.NotificationOutcome
import co.datapipelines.scheduler.Resent
import co.datapipelines.scheduler.RunDelivery
import co.datapipelines.scheduler.RunDetail
import co.datapipelines.scheduler.RunOrigin
import co.datapipelines.scheduler.RunState
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleException
import co.datapipelines.scheduler.ScheduleRun
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.web.api.ApiErrorCatalog
import co.datapipelines.web.ui.ActorNames
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import java.time.Instant
import java.util.UUID

/**
 * #442b A.4 — a run's notification deliveries (§20.11) and the resend (§20.13) at the surface.
 *
 * Roles through the REAL [ScopeInterceptor] on the REAL handler, with the real permission resolver:
 * the resend is `schedule.update` — an author, a workspace admin and a super admin reach it; a
 * viewer and a promoter are refused 403, and so is every key kind (the MCP key is confined to
 * `/mcp`, the others reach no schedule route). The run detail shows a delivery's address only to a
 * `schedule.update` holder and no part of it — not the domain — to anyone else.
 */
class SchedulesControllerResendTest {
    private val service = mockk<ScheduleService>()
    private val audit = RecordingAudit()
    private val mapper = ObjectMapper()
    private val interceptor = ScopeInterceptor(AuthErrorWriter(mapper), mockk<AuditLogger>(relaxed = true))
    private val controller =
        SchedulesController(
            service,
            audit,
            mapper,
            ActorNames(
                mockk {
                    every { query(any<String>(), any<Map<String, Any?>>(), any<RowMapper<Pair<UUID, String>>>()) } returns emptyList()
                },
            ),
        )
    private val user = UUID.randomUUID()
    private val workspace = UUID.randomUUID()

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    @ParameterizedTest
    @CsvSource("AUTHOR,false,true", "WORKSPACE_ADMIN,false,true", "VIEWER,true,true", "VIEWER,false,false", "PROMOTER,false,false")
    fun `a session reaches the resend exactly when its role holds schedule-update`(
        roleName: String,
        superAdmin: Boolean,
        reaches: Boolean,
    ) {
        PermissionResolverInstallation(RolePermissionsResolver).use {
            signIn(session(WorkspaceRole.valueOf(roleName), superAdmin))
            val (proceed, response) = preHandle()
            withClue("$roleName super_admin=$superAdmin") {
                proceed shouldBe reaches
                response.status shouldBe (if (reaches) OK else FORBIDDEN)
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "MCP,AUTHOR",
        "MCP,WORKSPACE_ADMIN",
        "MCP,PROMOTER",
        "ENDPOINT,API_CALLER",
        "SERVER,PROMOTION_RECEIVER",
        "DASHBOARD,DASHBOARD_VIEWER",
    )
    fun `every key kind is refused the resend`(
        kindName: String,
        keyRoleName: String,
    ) {
        PermissionResolverInstallation(RolePermissionsResolver).use {
            signIn(
                AuthenticatedPrincipal(
                    userId = user,
                    email = "dpk_key@keys.invalid",
                    displayName = "key",
                    authMethod = AuthMethod.API_KEY,
                    keyId = "dpk_KEY",
                    workspace = WorkspaceContext(workspace, "acme", WorkspaceRole.VIEWER),
                    keyKind = ApiKeyKind.valueOf(kindName),
                    keyRole = KeyRole.valueOf(keyRoleName),
                ),
            )
            val (proceed, response) = preHandle()
            withClue("$kindName/$keyRoleName") {
                proceed shouldBe false
                response.status shouldBe FORBIDDEN
            }
        }
    }

    @Test
    fun `resending an uncertain delivery warns it may duplicate and audits ids and the prior state, never the address`() {
        signIn(session(WorkspaceRole.AUTHOR))
        every { service.resendDelivery(workspace, SCHEDULE_ID, RUN_ID, DELIVERY_ID) } returns
            Resent(row(DeliveryState.PENDING, resendCount = 1), DeliveryState.UNCERTAIN)

        val data = controller.resend(SCHEDULE_ID, RUN_ID, DELIVERY_ID).data

        data["warning"] shouldBe "may_duplicate"
        data["state"] shouldBe "pending"
        data["resend_count"] shouldBe 1
        data["recipient"] shouldBe ADDRESS
        val (event, details) = audit.events.single()
        event shouldBe ScheduleAuditEvents.NOTIFICATION_RESENT
        details shouldBe
            mapOf(
                "schedule_id" to SCHEDULE_ID.toString(),
                "run_id" to RUN_ID.toString(),
                "delivery_id" to DELIVERY_ID.toString(),
                "prior_state" to "uncertain",
            )
    }

    @Test
    fun `resending a failed delivery carries no warning`() {
        signIn(session(WorkspaceRole.AUTHOR))
        every { service.resendDelivery(workspace, SCHEDULE_ID, RUN_ID, DELIVERY_ID) } returns
            Resent(row(DeliveryState.PENDING, resendCount = 1), DeliveryState.FAILED)

        val data = controller.resend(SCHEDULE_ID, RUN_ID, DELIVERY_ID).data

        data.containsKey("warning") shouldBe false
        audit.events.single().second["prior_state"] shouldBe "failed"
    }

    @Test
    fun `a full backlog refusal is 409 and writes no resend audit row`() {
        signIn(session(WorkspaceRole.AUTHOR))
        every { service.resendDelivery(workspace, SCHEDULE_ID, RUN_ID, DELIVERY_ID) } throws
            ScheduleException(
                ScheduleErrorCodes.NOTIFICATION_BACKLOG_FULL,
                "Backlog full",
                mapOf("pending" to 1, "backlog_max" to 1),
            )
        val failure = shouldThrow<ScheduleException> { controller.resend(SCHEDULE_ID, RUN_ID, DELIVERY_ID) }
        ApiErrorCatalog.statusFor(failure.code) shouldBe HttpStatus.CONFLICT
        failure.details shouldBe mapOf("pending" to 1, "backlog_max" to 1)
        audit.events.shouldBeEmpty()
    }

    @ParameterizedTest
    @CsvSource("AUTHOR,true", "WORKSPACE_ADMIN,true", "VIEWER,false", "PROMOTER,false")
    fun `the run detail's deliveries carry the address only for a schedule-update holder`(
        roleName: String,
        readsAddress: Boolean,
    ) {
        PermissionResolverInstallation(RolePermissionsResolver).use {
            signIn(session(WorkspaceRole.valueOf(roleName)))
            every { service.run(workspace, SCHEDULE_ID, RUN_ID, any()) } returns
                RunDetail(run(), emptyList(), listOf(row(DeliveryState.FAILED, code = DeliveryCodes.SMTP_REFUSED)))

            val data = controller.run(SCHEDULE_ID, RUN_ID).data

            @Suppress("UNCHECKED_CAST")
            val delivery = (data["deliveries"] as List<Map<String, Any?>>).single()
            delivery["id"] shouldBe DELIVERY_ID.toString()
            delivery["notice"] shouldBe "failed"
            delivery["state"] shouldBe "failed"
            delivery["attempts"] shouldBe 1
            delivery["last_error_code"] shouldBe DeliveryCodes.SMTP_REFUSED
            delivery.containsKey("recipient") shouldBe readsAddress
            if (!readsAddress) mapper.writeValueAsString(data) shouldNotContain "example.com"
        }
    }

    private fun preHandle(): Pair<Boolean, MockHttpServletResponse> {
        val handler =
            HandlerMethod(
                controller,
                SchedulesController::class.java.getMethod("resend", UUID::class.java, UUID::class.java, UUID::class.java),
            )
        val response = MockHttpServletResponse()
        val path = "/api/v1/schedules/$SCHEDULE_ID/runs/$RUN_ID/deliveries/$DELIVERY_ID/resend"
        return interceptor.preHandle(MockHttpServletRequest("POST", path), response, handler) to response
    }

    private fun session(
        role: WorkspaceRole,
        superAdmin: Boolean = false,
    ) = AuthenticatedPrincipal(
        user,
        "author@example.net",
        "Author",
        AuthMethod.OIDC,
        "session",
        // A super admin's context is the one WorkspaceService builds for them (auth.md §11A).
        workspace =
            if (superAdmin) WorkspaceContext.superAdminOver(workspace, "acme", role) else WorkspaceContext(workspace, "acme", role),
        superAdmin = superAdmin,
    )

    private fun signIn(principal: AuthenticatedPrincipal) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun row(
        state: DeliveryState,
        code: String? = DeliveryCodes.IO_AFTER_CONNECT,
        resendCount: Int = 0,
    ) = RunDelivery(
        NotificationOutcome.FAILED,
        Delivery(
            id = DELIVERY_ID,
            eventId = UUID.randomUUID(),
            recipient = ADDRESS,
            state = state,
            attempts = 1,
            nextAttemptAt = if (state == DeliveryState.PENDING) AT else null,
            lastErrorCode = code,
            messageId = "<$DELIVERY_ID@dp.example.com>",
            sentAt = null,
            resendCount = resendCount,
            createdAt = AT,
            updatedAt = AT,
        ),
    )

    private fun run() =
        ScheduleRun(
            id = RUN_ID,
            scheduleId = SCHEDULE_ID,
            workspaceId = workspace,
            origin = RunOrigin.CRON,
            scheduledAt = AT,
            referenceAt = AT,
            referenceTimezone = "UTC",
            admitBy = AT,
            scheduleRevision = 1,
            executorId = "pipeline",
            payloadSchemaVersion = 1,
            payload = mapper.createObjectNode(),
            parameters = mapper.createObjectNode(),
            prepared = null,
            actorUserId = UUID.randomUUID(),
            requestedBy = null,
            executionId = null,
            state = RunState.FAILED,
            reason = null,
            worker = null,
            attempts = 1,
            createdAt = AT,
            claimedAt = AT,
            startedAt = AT,
            finishedAt = AT,
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
        const val OK = 200
        const val FORBIDDEN = 403
        const val ADDRESS = "ops@example.com"
        val AT: Instant = Instant.parse("2026-10-24T06:00:00Z")
        val SCHEDULE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val RUN_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000f00d")
        val DELIVERY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000de11e")
    }
}
