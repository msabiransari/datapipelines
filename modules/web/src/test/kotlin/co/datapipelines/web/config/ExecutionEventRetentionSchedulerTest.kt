package co.datapipelines.web.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.auth.AuditLogRetention
import co.datapipelines.auth.KeyRetentionPurge
import co.datapipelines.executor.ExecutionEventRetention
import co.datapipelines.web.dashboards.runtime.DashboardRefreshRetention
import co.datapipelines.web.parameters.ParameterEvaluationRetention
import co.datapipelines.web.parameters.ParameterEvaluationSweeper
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException

/**
 * The retention sweep's one `@Scheduled` tick (metadata-db §8, #310): six steps in a fixed
 * order — execution events, the finished dashboard refreshes (#10 L2), the evaluation history's stale sweep and its
 * retention (#376), the keys purge, the audit log — each isolated from the others.
 *
 * Isolation is the point of this suite. Before #310 the keys purge was the LAST step, so its
 * exception reaching Spring's scheduler stopped nothing; with the audit purge after it, an
 * unisolated keys purge failure would silently skip the audit retention every hour it failed.
 * Each step's failure is one ERROR line naming the step, and the tick carries on.
 *
 * Relaxed mocks with `verify`: the contract here is "each step IS called", which a verify fails
 * on when a call is missing (a strict mock would instead make the missing call the only passing
 * state — MISTAKES, the strict-mock entry).
 */
class ExecutionEventRetentionSchedulerTest {
    private val events = mockk<ExecutionEventRetention>(relaxed = true)
    private val keys = mockk<KeyRetentionPurge>(relaxed = true)
    private val audit = mockk<AuditLogRetention>(relaxed = true)
    private val dashboards = mockk<DashboardRefreshRetention>(relaxed = true)
    private val evaluationSweeper = mockk<ParameterEvaluationSweeper>(relaxed = true)
    private val evaluations = mockk<ParameterEvaluationRetention>(relaxed = true)
    private val scheduler = ExecutionEventRetentionScheduler(events, keys, audit, dashboards, evaluationSweeper, evaluations)

    @Test
    fun `a tick runs the six steps in order - events, dashboard refreshes, the evaluation sweep and retention, keys, the audit log`() {
        val lines = captured { scheduler.retain() }

        verifyOrder {
            events.retainOnce()
            dashboards.retainOnce()
            evaluationSweeper.sweepOnce()
            evaluations.retainOnce()
            keys.purgeOnce()
            audit.purgeOnce()
        }
        lines.filter { it.level == Level.ERROR }.shouldBeEmpty()
    }

    @Test
    fun `a failing evaluation sweep does not stop the evaluation retention or the purges after it`() {
        every { evaluationSweeper.sweepOnce() } throws IllegalStateException("unexpected")

        val lines = captured { scheduler.retain() }

        verify(exactly = 1) { evaluations.retainOnce() }
        verify(exactly = 1) { keys.purgeOnce() }
        verify(exactly = 1) { audit.purgeOnce() }
        lines.single { it.level == Level.ERROR }.formattedMessage shouldContain
            "event=retention.step_failed step=parameter_evaluation_sweep"
    }

    @Test
    fun `a failing evaluation retention does not stop the purges after it`() {
        every { evaluations.retainOnce() } throws DataAccessResourceFailureException("metadata database unreachable")

        val lines = captured { scheduler.retain() }

        verify(exactly = 1) { keys.purgeOnce() }
        verify(exactly = 1) { audit.purgeOnce() }
        lines.single { it.level == Level.ERROR }.formattedMessage shouldContain "event=retention.step_failed step=parameter_evaluations"
    }

    @Test
    fun `a failing dashboard refresh retention does not stop the purges after it`() {
        every { dashboards.retainOnce() } throws DataAccessResourceFailureException("metadata database unreachable")

        val lines = captured { scheduler.retain() }

        verify(exactly = 1) { keys.purgeOnce() }
        verify(exactly = 1) { audit.purgeOnce() }
        lines.single { it.level == Level.ERROR }.formattedMessage shouldContain "event=retention.step_failed step=dashboard_refreshes"
    }

    @Test
    fun `a failing keys purge does not stop the audit retention`() {
        every { keys.purgeOnce() } throws DataAccessResourceFailureException("metadata database unreachable")

        val lines = captured { scheduler.retain() }

        verify(exactly = 1) { audit.purgeOnce() }
        val error = lines.single { it.level == Level.ERROR }
        error.formattedMessage shouldContain "event=retention.step_failed step=keys"
    }

    @Test
    fun `a failing audit retention is contained - the scheduler thread never sees it`() {
        every { audit.purgeOnce() } throws IllegalStateException("unexpected")

        val lines = captured { scheduler.retain() }

        verify(exactly = 1) { events.retainOnce() }
        verify(exactly = 1) { keys.purgeOnce() }
        lines.single { it.level == Level.ERROR }.formattedMessage shouldContain "step=audit_log"
    }

    @Test
    fun `a failing event retention does not stop the purges`() {
        every { events.retainOnce() } throws IllegalStateException("unexpected")

        val lines = captured { scheduler.retain() }

        verify(exactly = 1) { keys.purgeOnce() }
        verify(exactly = 1) { audit.purgeOnce() }
        lines.single { it.level == Level.ERROR }.formattedMessage shouldContain "step=execution_events"
    }

    @Test
    fun `every step failing still ends the tick normally, one ERROR line per step`() {
        every { events.retainOnce() } throws IllegalStateException("a")
        every { dashboards.retainOnce() } throws IllegalStateException("b")
        every { evaluationSweeper.sweepOnce() } throws IllegalStateException("c")
        every { evaluations.retainOnce() } throws IllegalStateException("d")
        every { keys.purgeOnce() } throws IllegalStateException("e")
        every { audit.purgeOnce() } throws IllegalStateException("f")

        val lines = captured { scheduler.retain() }

        lines.filter { it.level == Level.ERROR }.map { it.formattedMessage.substringAfter("step=").substringBefore(' ') } shouldBe
            listOf("execution_events", "dashboard_refreshes", "parameter_evaluation_sweep", "parameter_evaluations", "keys", "audit_log")
    }

    private fun captured(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(ExecutionEventRetentionScheduler::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.toList()
    }
}
