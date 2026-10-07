package co.datapipelines.executor

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID

/**
 * The live writes' outage evidence (#336 D7; observability §3.2, §3.4G).
 *
 * `pipeline_executions.heartbeat_at` is the stale-execution sweep's primary signal: a store that
 * refuses the writes makes a LIVE execution look dead to the sweep on another instance. The
 * defect this class pins: both write failures were logged at DEBUG — below every production
 * level — and carried the driver's message (§3.4G forbids it at any level that ships). The rule
 * they now follow is the rate limiter's (§3.2): one WARN on the healthy → failing transition, an
 * INFO when the write answers again, the failures between them counted rather than logged.
 */
class JdbcExecutionProgressOutageTest {
    private fun captured(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(JdbcExecutionProgress::class.java) as Logger
        return capturingLogEvents(logger, block = block)
    }

    @Test
    fun `an outage warns once on the transition, recovers with one INFO, and never quotes the driver message`() {
        val executions = mockk<ExecutionRepository>()
        val failure = SQLException("store refused the heartbeat", "08006")
        every { executions.heartbeat(any()) } throws
            failure andThenThrows
            failure andThenThrows
            failure andThen true
        every { executions.recordProgress(any(), any()) } returns true

        val registry = SimpleMeterRegistry()
        val progress = JdbcExecutionProgress(executions, { "[]" }, throttleMillis = 0, metrics = ExecutorMetrics(registry))
        val executionId = UUID.randomUUID()

        val events =
            captured {
                repeat(3) { progress.heartbeat(executionId) }
                progress.heartbeat(executionId) // the store answers again
            }

        // One WARN for the whole outage — the transition, not one line per refused write — and
        // one INFO when the write answers again.
        val warns = events.filter { it.level == ch.qos.logback.classic.Level.WARN }
        val infos = events.filter { it.level == ch.qos.logback.classic.Level.INFO }
        warns.shouldHaveSize(1)
        infos.shouldHaveSize(1)

        // §3.4G: the failure is named by class and SQLState, never the driver's message.
        val warnText = warns.single().formattedMessage
        warnText.shouldContain("SQLException")
        warnText.shouldContain("08006")
        warnText.shouldNotContain("store refused the heartbeat")

        // Every refusal counted — between the transition lines the counter is the evidence.
        registry.get("datapipelines.executions.progress_write_failed").counter().count() shouldBe 3
    }

    /** The two write kinds share the outage state: a heartbeating store and a refusing progress write warn once. */
    @Test
    fun `the outage state is shared by both write kinds and dropped by forget`() {
        val executions = mockk<ExecutionRepository>()
        val failure = SQLException("store refused the progress write", "08006")
        every { executions.heartbeat(any()) } returns true
        every { executions.recordProgress(any(), any()) } throws failure andThen true

        val progress = JdbcExecutionProgress(executions, { "[]" }, throttleMillis = 0)
        val executionId = UUID.randomUUID()

        val events =
            captured {
                progress.record(executionId, emptyList()) // the transition WARN
                progress.record(executionId, emptyList()) // the recovery INFO
                progress.forget(executionId) // the executor's finally
                progress.record(executionId, emptyList()) // healthy again — and a fresh execution slot
                progress.record(executionId, emptyList())
                progress.record(executionId, emptyList())
            }

        events.count { it.level == ch.qos.logback.classic.Level.WARN }.let { it shouldBe 1 }
        events.count { it.level == ch.qos.logback.classic.Level.INFO }.let { it shouldBe 1 }
    }
}
