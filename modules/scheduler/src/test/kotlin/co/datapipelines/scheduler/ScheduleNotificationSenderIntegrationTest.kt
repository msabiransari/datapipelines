package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * #442b A.3 — the sender over the real V51 tables with a capturing mail port: one message per
 * recipient under the delivery's stable Message-ID; a provably-unsent failure retried on the backoff
 * schedule and `failed` after `max-attempts`; a possible send `uncertain` and never retried; a claim a
 * dead instance left recovered as `uncertain` / `interrupted`; the flag and the allowlist re-checked
 * at SEND; and the run's state never touched.
 */
class ScheduleNotificationSenderIntegrationTest {
    private val mailOn = NotificationDeployment(mailConfigured = true, baseUrl = "https://dp.example.com/", developmentPosture = false)
    private val recipients = listOf("ops@example.com", "lead@example.org")

    private lateinit var h: SchedulerHarness
    private lateinit var schedule: Schedule
    private lateinit var run: ScheduleRun

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
    }

    /** A schedule notifying on success only, one succeeded run: one `completed` notice, two pending deliveries. */
    private fun boot(notifications: SchedulerProperties.Notifications = SchedulerProperties.Notifications(enabled = true)) {
        h = SchedulerHarness(properties = SchedulerProperties(minIntervalSeconds = 60, notifications = notifications), deployment = mailOn)
        val settings =
            NotificationSettingsRequest(
                recipients = JsonNodeFactory.instance.arrayNode().apply { recipients.forEach(::add) },
                events = JsonNodeFactory.instance.arrayNode().add("success"),
            )
        schedule = h.create(h.request(timezone = "Europe/Berlin").copy(notifications = settings))
        h.setTime(schedule.nextDueAt!!.plusSeconds(3))
        h.tick()
        val queued = h.runsOf(schedule.id).single()
        h.worker.handle(queued.id)
        h.executor.outcomes[requireNotNull(h.runs.find(queued.id)!!.executionId)] = ExecutionOutcome.Finished(RunState.SUCCEEDED, null)
        h.reconciler.tick()
        run = h.runs.find(queued.id)!!
        deliveries().map { it.state } shouldBe listOf(DeliveryState.PENDING, DeliveryState.PENDING)
    }

    @Test
    fun `an accepted send is one message per recipient under the delivery's Message-ID, settled with its trail row`() {
        boot()
        h.notifier().tick() shouldBe NotifierTick(recovered = 0, settled = 2)

        h.mailer.sent.map { it.recipient } shouldContainExactlyInAnyOrder recipients
        deliveries().forEach { d ->
            d.state shouldBe DeliveryState.ACCEPTED
            d.attempts shouldBe 1
            d.sentAt.shouldNotBeNull()
            d.lastErrorCode.shouldBeNull()
            h.mailer.sent
                .single { it.recipient == d.recipient }
                .messageId shouldBe d.messageId
        }
        h.mailer.sent.forEach { mail ->
            mail.subject shouldBe "reports/daily — Completed"
            // The link is the configured base URL plus the run's deep link; no address of any recipient in a body.
            mail.text shouldContain "https://dp.example.com/schedules?id=${schedule.id}&run=${run.id}"
            mail.html shouldContain "https://dp.example.com/schedules?id=${schedule.id}&amp;run=${run.id}"
            recipients.forEach { address ->
                mail.text shouldNotContain address
                mail.html shouldNotContain address
            }
        }
        val sent = trail(TrailKind.NOTIFICATION_SENT)
        sent.size shouldBe 2
        sent.forEach { it.details.toString() shouldNotContain "example" }
        h.registry.counter(SchedulerMetrics.NOTIFICATIONS_SENT).count() shouldBe 2.0
        // Email never changes the pipeline outcome.
        h.runs.find(run.id)!!.state shouldBe RunState.SUCCEEDED

        h.notifier().tick() shouldBe NotifierTick(0, 0)
        h.mailer.sent.size shouldBe 2
    }

    @Test
    fun `queued and historically resent notices keep the run target and timezone after a schedule edit`() {
        boot()
        val target = schedule.targetRef
        SchedulerTestDb.jdbc.update(
            "UPDATE schedules SET target_ref = :target, timezone = 'America/New_York' WHERE id = :id",
            mapOf("target" to "changed/target", "id" to schedule.id),
        )
        repeat(2) { h.mailer.script += MailOutcome.Uncertain(DeliveryCodes.IO_AFTER_CONNECT) }
        h.notifier().tick()
        val original = h.mailer.sent.first()
        original.text shouldContain target
        original.text shouldContain "Europe/Berlin"
        original.text shouldNotContain "changed/target"
        original.text shouldNotContain "America/New_York"
        val delivery = deliveries().first()
        h.service.resendDelivery(SchedulerTestDb.WORKSPACE, schedule.id, run.id, delivery.id)
        h.notifier().tick()
        h.mailer.sent
            .last()
            .text shouldBe original.text
        h.mailer.sent
            .last()
            .html shouldBe original.html
    }

    @Test
    fun `manual run snapshots its target before an edit that precedes enqueue`() {
        boot()
        h.notifier().tick()
        h.mailer.sent.clear()
        val manual = h.service.runNow(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, null).value
        manual.targetRef shouldBe schedule.targetRef
        requireNotNull(h.runs.find(run.id)).targetRef shouldBe schedule.targetRef // cron creation site, persisted
        SchedulerTestDb.jdbc.update(
            "UPDATE schedules SET target_ref = 'changed/target', timezone = 'America/New_York' WHERE id = :id",
            mapOf("id" to schedule.id),
        )
        h.worker.handle(manual.id)
        h.executor.outcomes[requireNotNull(h.runs.find(manual.id)?.executionId)] = ExecutionOutcome.Finished(RunState.SUCCEEDED, null)
        h.reconciler.tick()
        h.notifier().tick()
        h.mailer.sent.size shouldBe 2
        h.mailer.sent.forEach { mail ->
            mail.text shouldContain schedule.targetRef
            mail.text shouldContain "Europe/Berlin"
            mail.text shouldNotContain "changed/target"
        }
    }

    @Test
    fun `a historical resend after editing the schedule renders the original run facts`() {
        boot()
        repeat(2) { h.mailer.script += MailOutcome.Uncertain(DeliveryCodes.IO_AFTER_CONNECT) }
        h.notifier().tick()
        SchedulerTestDb.jdbc.update(
            "UPDATE schedules SET target_ref = 'changed/target', timezone = 'America/New_York' WHERE id = :id",
            mapOf("id" to schedule.id),
        )
        val delivery = deliveries().first()
        h.service.resendDelivery(SchedulerTestDb.WORKSPACE, schedule.id, run.id, delivery.id)
        h.notifier().tick()
        h.mailer.sent
            .last()
            .text shouldContain schedule.targetRef
        h.mailer.sent
            .last()
            .text shouldContain "Europe/Berlin"
        h.mailer.sent
            .last()
            .text shouldNotContain "changed/target"
    }

    @Test
    fun `legacy run with no frozen target omits Runs in both message parts`() {
        boot()
        SchedulerTestDb.jdbc.update("UPDATE schedule_runs SET target_ref = NULL WHERE id = :id", mapOf("id" to run.id))
        h.notifier().tick()
        h.mailer.sent.forEach { mail ->
            mail.text shouldNotContain "Runs:"
            mail.html shouldNotContain ">Runs<"
            mail.text shouldContain "Started:"
            mail.text shouldContain "Ended:"
            mail.text shouldContain "Execution: ${run.executionId}"
        }
    }

    @Test
    fun `a connect failure is retried on the backoff schedule and failed after max-attempts`() {
        boot()
        repeat(STEPS) { h.mailer.script += MailOutcome.Retryable(DeliveryCodes.CONNECT_FAILED) }

        val start = h.clock.instant()
        h.notifier().tick()
        deliveries().forEach { d ->
            d.state shouldBe DeliveryState.PENDING
            d.attempts shouldBe 1
            d.lastErrorCode shouldBe DeliveryCodes.CONNECT_FAILED
            d.nextAttemptAt shouldBe start.plusSeconds(30)
        }
        h.notifier().tick().settled shouldBe 0 // not due yet

        h.setTime(start.plusSeconds(30))
        h.notifier().tick().settled shouldBe 2
        deliveries().forEach { it.nextAttemptAt shouldBe start.plusSeconds(30 + 120) }
        trail(TrailKind.NOTIFICATION_FAILED).shouldBeEmpty() // a retry is not a final failure

        h.setTime(start.plusSeconds(30 + 120))
        h.notifier().tick().settled shouldBe 2
        deliveries().forEach { d ->
            d.state shouldBe DeliveryState.FAILED
            d.attempts shouldBe 3
            d.nextAttemptAt.shouldBeNull()
            d.lastErrorCode shouldBe DeliveryCodes.CONNECT_FAILED
        }
        trail(TrailKind.NOTIFICATION_FAILED).size shouldBe 2
        h.registry.counter(SchedulerMetrics.NOTIFICATIONS_FAILED).count() shouldBe 2.0

        h.setTime(start.plus(Duration.ofDays(1)))
        h.notifier().tick().settled shouldBe 0
        h.mailer.sent.size shouldBe 6
    }

    @Test
    fun `with max-attempts 1 the first retryable failure is final`() {
        boot(SchedulerProperties.Notifications(enabled = true, maxAttempts = 1))
        repeat(2) { h.mailer.script += MailOutcome.Retryable(DeliveryCodes.SMTP_TRANSIENT) }

        h.notifier().tick()

        deliveries().forEach { d ->
            d.state shouldBe DeliveryState.FAILED
            d.attempts shouldBe 1
            d.lastErrorCode shouldBe DeliveryCodes.SMTP_TRANSIENT
        }
    }

    @Test
    fun `a refusal fails at once and a possible send is uncertain and never retried`() {
        boot()
        h.mailer.script += MailOutcome.Refused(DeliveryCodes.SMTP_REFUSED)
        h.mailer.script += MailOutcome.Uncertain(DeliveryCodes.IO_AFTER_CONNECT)

        h.notifier().tick()
        h.setTime(h.clock.instant().plus(Duration.ofDays(1)))
        h.notifier().tick().settled shouldBe 0

        h.mailer.sent.size shouldBe 2
        deliveries().map { it.state to it.lastErrorCode } shouldContainExactlyInAnyOrder
            listOf(DeliveryState.FAILED to DeliveryCodes.SMTP_REFUSED, DeliveryState.UNCERTAIN to DeliveryCodes.IO_AFTER_CONNECT)
        trail(TrailKind.NOTIFICATION_FAILED).size shouldBe 1
        trail(TrailKind.NOTIFICATION_UNCERTAIN).size shouldBe 1
        h.registry.counter(SchedulerMetrics.NOTIFICATIONS_UNCERTAIN).count() shouldBe 1.0
    }

    @Test
    fun `a port that throws leaves no claim behind - the delivery is uncertain`() {
        boot()
        h.mailer.script += CapturingMailer.Throw

        h.notifier().tick().settled shouldBe 2

        deliveries().map { it.state to it.lastErrorCode } shouldContainExactlyInAnyOrder
            listOf(DeliveryState.UNCERTAIN to DeliveryCodes.INTERRUPTED, DeliveryState.ACCEPTED to null)
    }

    @Test
    fun `a claim a dead instance left is recovered as uncertain - interrupted, and a live one is not`() {
        boot()
        val (stale, live) = deliveries()
        val now = h.clock.instant()
        claim(stale.id, now.minus(h.properties.startGrace).minusSeconds(1))
        claim(live.id, now.minusSeconds(1))

        h.notifier().tick() shouldBe NotifierTick(recovered = 1, settled = 0)

        h.mailer.sent.shouldBeEmpty()
        val after = deliveries().associateBy { it.id }
        after.getValue(stale.id).state shouldBe DeliveryState.UNCERTAIN
        after.getValue(stale.id).lastErrorCode shouldBe DeliveryCodes.INTERRUPTED
        after.getValue(live.id).state shouldBe DeliveryState.PENDING // still someone's claim: neither recovered nor claimed again
        trail(TrailKind.NOTIFICATION_UNCERTAIN)
            .single()
            .details
            .path("code")
            .asText() shouldBe DeliveryCodes.INTERRUPTED
    }

    @Test
    fun `the allowlist is re-checked at send - a domain narrowed out after enqueue is failed and never sent`() {
        boot(SchedulerProperties.Notifications(enabled = true, recipientDomains = listOf("example.com", "example.org")))
        val narrowed =
            NotificationPolicy(SchedulerProperties.Notifications(enabled = true, recipientDomains = listOf("example.com")), mailOn)

        h.notifier(narrowed).tick()

        h.mailer.sent.map { it.recipient } shouldBe listOf("ops@example.com")
        deliveries().associate { it.recipient to (it.state to it.lastErrorCode) } shouldBe
            mapOf(
                "ops@example.com" to (DeliveryState.ACCEPTED to null),
                "lead@example.org" to (DeliveryState.FAILED to DeliveryCodes.DOMAIN_NOT_ALLOWED),
            )
    }

    @Test
    fun `the flag is re-checked at send - mail turned off after enqueue fails every delivery with the reason`() {
        boot()
        val off = NotificationPolicy(SchedulerProperties.Notifications(enabled = false), mailOn)

        h.notifier(off).tick()

        h.mailer.sent.shouldBeEmpty()
        deliveries().forEach { d ->
            d.state shouldBe DeliveryState.FAILED
            d.lastErrorCode shouldBe DeliveryOffReason.DISABLED.wire
            d.attempts shouldBe 1
        }
    }

    @Test
    fun `a tick settles at most batch-size deliveries`() {
        boot(SchedulerProperties.Notifications(enabled = true, batchSize = 1))

        h.notifier().tick().settled shouldBe 1
        h.notifier().tick().settled shouldBe 1
        h.notifier().tick().settled shouldBe 0
        h.mailer.sent.size shouldBe 2
    }

    private fun deliveries(): List<Delivery> =
        SchedulerTestDb.jdbc.query(
            "SELECT * FROM schedule_notification_deliveries ORDER BY recipient DESC",
            emptyMap<String, Any>(),
            h.notifications.deliveryMapper,
        )

    private fun trail(kind: TrailKind): List<TrailEvent> = h.runs.trail(run.id).filter { it.kind == kind }

    /** Plants a claim as an instance would: `sending_since` set, the attempt counted. */
    private fun claim(
        id: java.util.UUID,
        at: Instant,
    ) {
        SchedulerTestDb.jdbc.update(
            "UPDATE schedule_notification_deliveries SET sending_since = :at, attempts = attempts + 1 WHERE id = :id",
            mapOf("at" to Timestamp.from(at), "id" to id),
        )
    }

    private companion object {
        const val STEPS = 6
    }
}
