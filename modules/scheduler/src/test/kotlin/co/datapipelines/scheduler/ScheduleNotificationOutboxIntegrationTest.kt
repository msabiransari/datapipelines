package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * #442b A.2 — the outbox the ledger writes in a transition's own transaction (record §6.1), over
 * the real V51 tables: the policy's mapping from transitions to notices, selection by the
 * schedule's event set at that moment, nothing written when delivery is off, the allowlist and
 * the backlog cap decided per recipient at enqueue (visible as `failed`, never dropped), the
 * `blocked` notice only on the actual transition, a replay writing nothing, and atomicity — a
 * transition and its notice commit together or not at all.
 */
class ScheduleNotificationOutboxIntegrationTest {
    private val mailOn = NotificationDeployment(mailConfigured = true, baseUrl = "https://dp.example.com", developmentPosture = false)
    private val recipients = listOf("ops@example.com", "lead@example.org")

    private lateinit var h: SchedulerHarness
    private lateinit var schedule: Schedule

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
        boot()
    }

    private fun boot(
        notifications: SchedulerProperties.Notifications = SchedulerProperties.Notifications(enabled = true),
        deployment: NotificationDeployment = mailOn,
        events: List<String> = NotificationEvent.entries.map { it.wire },
    ) {
        h =
            SchedulerHarness(
                properties = SchedulerProperties(minIntervalSeconds = 60, notifications = notifications),
                deployment = deployment,
            )
        val settings =
            NotificationSettingsRequest(
                recipients = JsonNodeFactory.instance.arrayNode().apply { recipients.forEach(::add) },
                events = JsonNodeFactory.instance.arrayNode().apply { events.forEach(::add) },
            )
        schedule = h.create(h.request().copy(notifications = settings))
        h.setTime(schedule.nextDueAt!!.plusSeconds(3))
        h.tick()
    }

    @Test
    fun `start and success each enqueue one notice, one pending delivery per recipient, and a trail row`() {
        val run = startedRun()
        finish(run, ExecutionOutcome.Finished(RunState.SUCCEEDED, null))

        outcomes() shouldContainExactly listOf("started", "completed")
        h.notifications.eventsOfRun(run.id).forEach { event ->
            val deliveries = h.notifications.deliveriesOf(event.id)
            deliveries.map { it.recipient } shouldContainExactlyInAnyOrder recipients
            deliveries.forEach { d ->
                d.state shouldBe DeliveryState.PENDING
                d.attempts shouldBe 0
                d.messageId shouldMatch Regex("""^<${d.id}@dp\.example\.com>$""")
            }
        }
        val trail = h.runs.trail(run.id).map { it.kind }
        trail shouldContainExactly
            listOf(
                TrailKind.RECORDED,
                TrailKind.CLAIMED,
                TrailKind.EXECUTION_STARTED,
                TrailKind.NOTIFICATION_ENQUEUED,
                TrailKind.FINISHED,
                TrailKind.NOTIFICATION_ENQUEUED,
            ).filter { it != TrailKind.CLAIMED || TrailKind.CLAIMED in trail }
        // The enqueued row carries ids, the event and counts — never an address or a domain.
        val enqueued = h.runs.trail(run.id).last { it.kind == TrailKind.NOTIFICATION_ENQUEUED }
        enqueued.details.path("queued").asInt() shouldBe 2
        enqueued.details.toString().contains("example") shouldBe false
    }

    @Test
    fun `each failure outcome is its own notice under the failure checkbox`() {
        val cases =
            listOf(
                ExecutionOutcome.Finished(RunState.FAILED, "execution_failed") to "failed",
                ExecutionOutcome.Finished(RunState.CANCELLED, "cancelled") to "cancelled",
                ExecutionOutcome.Finished(RunState.ABORTED, "shutdown") to "aborted",
            )
        cases.forEach { (outcome, expected) ->
            SchedulerTestDb.reset()
            boot(events = listOf("failure"))
            finish(startedRun(), outcome)
            outcomes() shouldContainExactly listOf(expected)
            h.notifications
                .eventsOfRun(onlyRun().id)
                .single()
                .outcome.event shouldBe NotificationEvent.FAILURE
        }
    }

    @Test
    fun `a pre-start refusal is could_not_start - and a block it asks for is the blocked notice, once`() {
        h.executor.refuseAtPrepare = Preparation.Refused("pointer_null", block = true, "the pipeline has no current version")
        h.worker.handle(onlyRun().id)

        outcomes() shouldContainExactly listOf("could_not_start", "blocked")
        val blocked = h.notifications.eventsOfRun(onlyRun().id).single { it.outcome == NotificationOutcome.BLOCKED }
        blocked.reason shouldBe "pointer_null"
    }

    @Test
    fun `selection - with only failure ticked, start and success write nothing, a cancellation does`() {
        SchedulerTestDb.reset()
        boot(events = listOf("failure"))
        finish(startedRun(), ExecutionOutcome.Finished(RunState.SUCCEEDED, null))
        countEvents() shouldBe 0
        h.runs
            .trail(onlyRun().id)
            .none { it.kind == TrailKind.NOTIFICATION_ENQUEUED } shouldBe true

        SchedulerTestDb.reset()
        boot(events = listOf("failure"))
        finish(startedRun(), ExecutionOutcome.Finished(RunState.CANCELLED, "cancelled"))
        outcomes() shouldContainExactly listOf("cancelled")
    }

    @Test
    fun `unknown blocks once and its later real outcome is an update under the unknown checkbox`() {
        val run = startedRun()
        finish(run, ExecutionOutcome.Finished(RunState.UNKNOWN, "instance_lost"))
        finish(run, ExecutionOutcome.Finished(RunState.SUCCEEDED, null))

        outcomes() shouldContainExactly listOf("started", "unknown", "blocked", "unknown_update")
        val update = h.notifications.eventsOfRun(run.id).single { it.outcome == NotificationOutcome.UNKNOWN_UPDATE }
        update.runState shouldBe RunState.SUCCEEDED
        update.outcome.event shouldBe NotificationEvent.UNKNOWN
    }

    @Test
    fun `an already blocked schedule writes no second blocked notice`() {
        // One active run per schedule, so the realistic case is a block that landed while the run
        // was in flight; the run's own blocking outcome then keeps that block and notifies nothing new.
        val run = startedRun()
        h.schedules.block(schedule.id, "pointer_null", run.id, h.clock.instant()) shouldBe true
        finish(run, ExecutionOutcome.Finished(RunState.UNKNOWN, "instance_lost"))

        outcomes() shouldContainExactly listOf("started", "unknown")
        countEvents("blocked") shouldBe 0
    }

    @Test
    fun `a queued run skipped because its schedule is blocked writes nothing - no mail for skipped`() {
        val run = onlyRun()
        h.schedules.block(schedule.id, "pointer_null", run.id, h.clock.instant()) shouldBe true
        h.worker.handle(run.id)

        h.runs.find(run.id)!!.state shouldBe RunState.SKIPPED
        countEvents() shouldBe 0
    }

    @Test
    fun `delivery off writes nothing - the flag, and development with an empty allowlist`() {
        listOf(
            SchedulerProperties.Notifications(enabled = false) to mailOn,
            SchedulerProperties.Notifications(enabled = true) to mailOn.copy(developmentPosture = true),
            SchedulerProperties.Notifications(enabled = true) to mailOn.copy(mailConfigured = false),
        ).forEach { (notifications, deployment) ->
            SchedulerTestDb.reset()
            boot(notifications, deployment)
            finish(startedRun(), ExecutionOutcome.Finished(RunState.FAILED, "execution_failed"))
            countEvents() shouldBe 0
            countDeliveries() shouldBe 0
        }
        // Non-vacuity: the same cycle with delivery on writes the notices.
        SchedulerTestDb.reset()
        boot()
        finish(startedRun(), ExecutionOutcome.Finished(RunState.FAILED, "execution_failed"))
        countDeliveries() shouldBe 4
    }

    @Test
    fun `a stored recipient outside the allowlist at enqueue is written failed domain_not_allowed, never pending`() {
        SchedulerTestDb.reset()
        // Saved while example.org was allowed; the deployment then narrowed the list to example.com.
        boot(SchedulerProperties.Notifications(enabled = true, recipientDomains = listOf("example.com", "example.org")))
        val narrowed = SchedulerProperties.Notifications(enabled = true, recipientDomains = listOf("example.com"))
        val outbox = NotificationOutbox(h.notifications, h.runs, NotificationPolicy(narrowed, mailOn), narrowed.backlogMax)
        val ledger = RunLedger(h.runs, h.schedules, h.transactions, h.clock, h.metrics, outbox, "test")
        val run = onlyRun()

        ledger.move(run.id, setOf(RunState.QUEUED), RunState.NOT_STARTED, "capacity", TrailKind.NOT_STARTED) shouldBe true

        val deliveries =
            h.notifications.deliveriesOf(
                h.notifications
                    .eventsOfRun(run.id)
                    .single()
                    .id,
            )
        deliveries.associate { it.recipient to (it.state to it.lastErrorCode) } shouldBe
            mapOf(
                "ops@example.com" to (DeliveryState.PENDING to null),
                "lead@example.org" to (DeliveryState.FAILED to DeliveryCodes.DOMAIN_NOT_ALLOWED),
            )
    }

    @Test
    fun `over backlog-max a new delivery is written failed backlog_full - visible, never dropped`() {
        SchedulerTestDb.reset()
        boot(SchedulerProperties.Notifications(enabled = true, backlogMax = 3))
        val run = startedRun() // two pending
        finish(run, ExecutionOutcome.Finished(RunState.SUCCEEDED, null)) // room for one more

        val completed = h.notifications.eventsOfRun(run.id).single { it.outcome == NotificationOutcome.COMPLETED }
        // Recipients are decided in their stored order: the first takes the last free slot.
        h.notifications.deliveriesOf(completed.id).associate { it.recipient to (it.state to it.lastErrorCode) } shouldBe
            mapOf(
                "ops@example.com" to (DeliveryState.PENDING to null),
                "lead@example.org" to (DeliveryState.FAILED to DeliveryCodes.BACKLOG_FULL),
            )
        h.notifications.pendingCount() shouldBe 3
    }

    @Test
    fun `concurrent runs share one deployment backlog slot`() {
        SchedulerTestDb.reset()
        val settings = SchedulerProperties.Notifications(enabled = true, backlogMax = 1)
        boot(settings, events = listOf("failure"))
        h.setTime(schedule.nextDueAt!!.minusSeconds(60))
        val second =
            h.create(
                h.request(name = "reports/other").copy(
                    notifications =
                        NotificationSettingsRequest(
                            recipients = JsonNodeFactory.instance.arrayNode().add("ops@example.com"),
                            events = JsonNodeFactory.instance.arrayNode().add("failure"),
                        ),
                ),
            )
        // One recipient on each of TWO schedules: a schedule-local lock cannot pass this witness.
        SchedulerTestDb.jdbc.update(
            "UPDATE schedules SET notification_recipients = ARRAY['ops@example.com'] WHERE id = :id",
            mapOf("id" to schedule.id),
        )
        h.setTime(second.nextDueAt!!.plusSeconds(3))
        h.tick()
        val ids = listOf(onlyRun().id, h.runsOf(second.id).single().id)
        val counted = AtomicInteger()
        val firstCount = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Test seam: spy pendingCount AFTER its real SELECT, with the caller's transaction OPEN.
        val repository = spyk(h.notifications)
        every { repository.pendingCount() } answers {
            val count = callOriginal()
            counted.incrementAndGet()
            firstCount.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "count latch was not released" }
            count
        }
        val outbox = NotificationOutbox(repository, h.runs, h.policy, settings.backlogMax)
        val ledger = RunLedger(h.runs, h.schedules, h.transactions, h.clock, h.metrics, outbox, "race")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first =
                pool.submit<Boolean> {
                    ledger.move(ids[0], setOf(RunState.QUEUED), RunState.NOT_STARTED, "capacity", TrailKind.NOT_STARTED)
                }
            firstCount.await(10, TimeUnit.SECONDS) shouldBe true
            val other =
                pool.submit<Boolean> {
                    ledger.move(ids[1], setOf(RunState.QUEUED), RunState.NOT_STARTED, "capacity", TrailKind.NOT_STARTED)
                }
            // With the fix the second transaction waits BEFORE counting; without it both counts
            // return zero. Poll on a separate connection (pg_stat_activity snapshots per transaction).
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var waiting = false
            while (counted.get() < 2 && !waiting && System.nanoTime() < deadline) {
                waiting = SchedulerTestDb.jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() " +
                        "AND wait_event = 'advisory' AND pid <> pg_backend_pid())",
                    emptyMap<String, Any>(),
                    Boolean::class.java,
                ) == true
                Thread.yield()
            }
            check(counted.get() == 2 || waiting) { "second transaction neither counted nor waited for admission" }
            release.countDown()
            first.get(10, TimeUnit.SECONDS) shouldBe true
            other.get(10, TimeUnit.SECONDS) shouldBe true
        } finally {
            release.countDown()
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS) shouldBe true
        }
        h.notifications.pendingCount() shouldBe 1
        val deliveries = ids.flatMap { h.notifications.deliveriesOfRun(it) }.map { it.delivery }
        deliveries.map { it.state to it.lastErrorCode } shouldContainExactlyInAnyOrder
            listOf(DeliveryState.PENDING to null, DeliveryState.FAILED to DeliveryCodes.BACKLOG_FULL)
    }

    @Test
    fun `a replayed outcome writes no second event - the run reaches each outcome once`() {
        val run = startedRun()
        val first = h.notifications.eventsOfRun(run.id).single()

        h.outbox.enqueue(run.id, NotificationOutcome.STARTED, RunState.RUNNING, null, h.clock.instant(), 99, "replay") shouldBe null

        h.notifications.eventsOfRun(run.id).map { it.id } shouldContainExactly listOf(first.id)
        countDeliveries() shouldBe 2
    }

    @Test
    fun `atomicity - a failure after the outbox write rolls back the transition, its trail and its notice`() {
        val run = startedRun()
        val before = h.runs.trail(run.id).size
        val eventsBefore = countEvents()
        // The block runs AFTER the outbox wrote the unknown notice, in the same transaction.
        val schedules = spyk(h.schedules)
        every { schedules.block(any(), any(), any(), any()) } throws IllegalStateException("planted: the block write failed")
        val ledger = RunLedger(h.runs, schedules, h.transactions, h.clock, h.metrics, h.outbox, "test")

        shouldThrow<IllegalStateException> {
            ledger.move(run.id, setOf(RunState.RUNNING), RunState.UNKNOWN, "instance_lost", TrailKind.UNKNOWN, block = true)
        }

        h.runs.find(run.id)!!.state shouldBe RunState.RUNNING
        h.runs.trail(run.id).size shouldBe before
        countEvents() shouldBe eventsBefore // no "event without transition"
        countEvents("unknown") shouldBe 0
    }

    @Test
    fun `a run with no schedule - or a deleted one - notifies nobody`() {
        val run = onlyRun()
        h.service.delete(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, schedule.revision)

        h.outbox.enqueue(run.id, NotificationOutcome.FAILED, RunState.FAILED, null, h.clock.instant(), 1, "test") shouldBe null
        h.outbox.enqueue(UUID.randomUUID(), NotificationOutcome.FAILED, RunState.FAILED, null, h.clock.instant(), 1, "test") shouldBe null
        countEvents().shouldBe(0)
        h.notifications.eventsOfRun(run.id).shouldBeEmpty()
    }

    private fun onlyRun(): ScheduleRun = h.runsOf(schedule.id).first()

    private fun startedRun(): ScheduleRun {
        val run = onlyRun()
        h.worker.handle(run.id)
        return h.runs.find(run.id)!!.also { it.state shouldBe RunState.RUNNING }
    }

    private fun finish(
        run: ScheduleRun,
        outcome: ExecutionOutcome,
    ) {
        h.executor.outcomes[requireNotNull(h.runs.find(run.id)!!.executionId)] = outcome
        h.reconciler.tick()
    }

    private fun outcomes(): List<String> =
        SchedulerTestDb.jdbc.queryForList(
            "SELECT outcome FROM schedule_notification_events WHERE schedule_id = :id ORDER BY occurred_at, sequence, outcome DESC",
            mapOf("id" to schedule.id),
            String::class.java,
        )

    private fun countEvents(outcome: String? = null): Int =
        SchedulerTestDb.jdbc.queryForObject(
            "SELECT count(*) FROM schedule_notification_events WHERE CAST(:outcome AS text) IS NULL OR outcome = :outcome",
            mapOf("outcome" to outcome),
            Int::class.java,
        )!!

    private fun countDeliveries(): Int =
        SchedulerTestDb.jdbc.queryForObject(
            "SELECT count(*) FROM schedule_notification_deliveries",
            emptyMap<String, Any>(),
            Int::class.java,
        )!!
}
