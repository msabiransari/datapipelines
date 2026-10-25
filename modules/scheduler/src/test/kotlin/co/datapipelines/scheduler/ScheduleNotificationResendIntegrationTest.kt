package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #442b A.4 — the run detail's deliveries and the explicit resend, over the real V51 tables: only a
 * `failed` or `uncertain` delivery is queued again (pending, due now, attempts kept, `resend_count`
 * + 1); the resend of an `uncertain` one says it may duplicate, and the sender sends it again under
 * the SAME Message-ID; anything else is refused with its state; and a delivery is found only through
 * its own run, schedule and workspace.
 */
class ScheduleNotificationResendIntegrationTest {
    private val mailOn = NotificationDeployment(mailConfigured = true, baseUrl = "https://dp.example.com/", developmentPosture = false)

    private lateinit var h: SchedulerHarness
    private lateinit var schedule: Schedule
    private lateinit var run: ScheduleRun

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
        h =
            SchedulerHarness(
                properties =
                    SchedulerProperties(minIntervalSeconds = 60, notifications = SchedulerProperties.Notifications(enabled = true)),
                deployment = mailOn,
            )
        val settings =
            NotificationSettingsRequest(
                recipients = array("ops@example.com", "lead@example.org"),
                events = array("start", "success"),
            )
        schedule = h.create(h.request().copy(notifications = settings))
        h.setTime(schedule.nextDueAt!!.plusSeconds(3))
        h.tick()
        val queued = h.runsOf(schedule.id).single()
        h.worker.handle(queued.id)
        h.executor.outcomes[requireNotNull(h.runs.find(queued.id)!!.executionId)] = ExecutionOutcome.Finished(RunState.SUCCEEDED, null)
        h.reconciler.tick()
        run = h.runs.find(queued.id)!!
        deliveries().size shouldBe DELIVERIES // non-vacuity: every case starts from four pending deliveries
    }

    @Test
    fun `the run detail lists every delivery with the notice it carries, in transition then recipient order`() {
        val detail = h.service.run(SchedulerTestDb.WORKSPACE, schedule.id, run.id, TargetViewer.EVERYONE)

        detail.deliveries.map { it.outcome to it.delivery.recipient } shouldContainExactly
            listOf(
                NotificationOutcome.STARTED to "lead@example.org",
                NotificationOutcome.STARTED to "ops@example.com",
                NotificationOutcome.COMPLETED to "lead@example.org",
                NotificationOutcome.COMPLETED to "ops@example.com",
            )
        detail.deliveries.forEach { it.delivery.state shouldBe DeliveryState.PENDING }
    }

    @Test
    fun `an uncertain delivery resent is pending with its attempts kept, may duplicate, and goes out again under the same Message-ID`() {
        repeat(DELIVERIES) { h.mailer.script += MailOutcome.Uncertain(DeliveryCodes.IO_AFTER_CONNECT) }
        h.notifier().tick()
        val first = deliveries().first()
        first.state shouldBe DeliveryState.UNCERTAIN

        val resent = resend(first.id)

        resent.mayDuplicate shouldBe true
        resent.prior shouldBe DeliveryState.UNCERTAIN
        val row = deliveries().single { it.id == first.id }
        row.state shouldBe DeliveryState.PENDING
        row.attempts shouldBe 1
        row.resendCount shouldBe 1
        row.nextAttemptAt shouldBe h.clock.instant()
        resent.delivery.delivery.state shouldBe DeliveryState.PENDING
        resent.delivery.delivery.resendCount shouldBe 1

        h.notifier().tick().settled shouldBe 1
        val copies = h.mailer.sent.filter { it.messageId == first.messageId }
        copies.size shouldBe 2
        deliveries().single { it.id == first.id }.state shouldBe DeliveryState.ACCEPTED
        // Every other delivery stays uncertain: a resend queues the one row it names.
        deliveries().count { it.state == DeliveryState.UNCERTAIN } shouldBe DELIVERIES - 1
    }

    @Test
    fun `a failed delivery resent does not warn of a duplicate`() {
        repeat(DELIVERIES) { h.mailer.script += MailOutcome.Refused(DeliveryCodes.SMTP_REFUSED) }
        h.notifier().tick()
        val failed = deliveries().first()

        val resent = resend(failed.id)

        resent.mayDuplicate shouldBe false
        resent.prior shouldBe DeliveryState.FAILED
        deliveries().single { it.id == failed.id }.state shouldBe DeliveryState.PENDING
    }

    @Test
    fun `a backlog_full delivery cannot bypass the cap on resend and stays unchanged`() {
        val first = deliveries().first()
        SchedulerTestDb.jdbc.update(
            "UPDATE schedule_notification_deliveries SET state = 'failed', next_attempt_at = NULL, " +
                "last_error_code = 'backlog_full' WHERE id = :id",
            mapOf("id" to first.id),
        )
        val capped =
            SchedulerHarness(
                properties =
                    SchedulerProperties(
                        minIntervalSeconds = 60,
                        notifications = SchedulerProperties.Notifications(enabled = true, backlogMax = 1),
                    ),
                deployment = mailOn,
            )
        val before = deliveries().single { it.id == first.id }
        val trailBefore = h.runs.trail(run.id)
        val failure =
            refusal {
                capped.service.resendDelivery(SchedulerTestDb.WORKSPACE, schedule.id, run.id, first.id)
            }
        failure.code shouldBe ScheduleErrorCodes.NOTIFICATION_BACKLOG_FULL
        failure.details shouldBe mapOf("pending" to 3, "backlog_max" to 1)
        deliveries().single { it.id == first.id } shouldBe before
        h.runs.trail(run.id) shouldBe trailBefore
        // Once pending work settles the SAME backlog_full row is resendable.
        h.notifier().tick()
        capped.service.resendDelivery(SchedulerTestDb.WORKSPACE, schedule.id, run.id, first.id).prior shouldBe DeliveryState.FAILED
        capped.notifications.pendingCount() shouldBe 1
    }

    @Test
    fun `a pending or accepted delivery is not resendable and is left exactly as it was`() {
        val pending = deliveries().first()
        refusal { resend(pending.id) }.let { e ->
            e.code shouldBe ScheduleErrorCodes.NOTIFICATION_NOT_RESENDABLE
            e.details["state"] shouldBe "pending"
        }

        h.notifier().tick()
        val accepted = deliveries().first()
        accepted.state shouldBe DeliveryState.ACCEPTED
        refusal { resend(accepted.id) }.details["state"] shouldBe "accepted"
        deliveries().single { it.id == accepted.id } shouldBe accepted
    }

    @Test
    fun `a delivery is found only through its own run, schedule and workspace`() {
        val delivery = deliveries().first().id
        val other = h.create(h.request(name = "reports/other", payload = FakeExecutor.payload("job-b")))

        refusal { resend(UUID.randomUUID()) }.code shouldBe ScheduleErrorCodes.NOTIFICATION_NOT_FOUND
        refusal { h.service.resendDelivery(SchedulerTestDb.WORKSPACE, other.id, run.id, delivery) }.code shouldBe
            ScheduleErrorCodes.RUN_NOT_FOUND
        refusal { h.service.resendDelivery(UUID.randomUUID(), schedule.id, run.id, delivery) }.code shouldBe
            ScheduleErrorCodes.NOT_FOUND
        h.service.delete(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.SYSTEM_ACTOR, schedule.revision)
        refusal { resend(delivery) }.code shouldBe ScheduleErrorCodes.NOT_FOUND
    }

    private fun array(vararg values: String) = JsonNodeFactory.instance.arrayNode().apply { values.forEach(::add) }

    private fun resend(deliveryId: UUID): Resent = h.service.resendDelivery(SchedulerTestDb.WORKSPACE, schedule.id, run.id, deliveryId)

    private fun refusal(block: () -> Unit): ScheduleException = shouldThrow<ScheduleException>(block)

    private fun deliveries(): List<Delivery> =
        SchedulerTestDb.jdbc.query(
            "SELECT * FROM schedule_notification_deliveries ORDER BY created_at, recipient",
            emptyMap<String, Any>(),
            h.notifications.deliveryMapper,
        )

    private companion object {
        /** Two recipients × the start and success notices. */
        const val DELIVERIES = 4
    }
}
