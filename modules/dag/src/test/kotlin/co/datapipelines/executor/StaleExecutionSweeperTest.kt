package co.datapipelines.executor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Duration
import java.time.Instant

/**
 * [StaleExecutionSweeper]: the cutoff arithmetic, the return contract, and the
 * failed-tick-must-not-crash-the-scheduler boundary. The `UPDATE` itself is covered by
 * `ExecutionRepositoriesIntegrationTest`'s sweep cases against a real database.
 */
class StaleExecutionSweeperTest {
    @Test
    fun `sweeps rows older than the configured timeout`() {
        val executions = mockk<ExecutionRepository>()
        val cutoff = slot<Instant>()
        every { executions.sweepStaleRunning(capture(cutoff), any()) } returns 3
        val before = Instant.now()

        val swept = StaleExecutionSweeper(executions, Duration.ofMinutes(60), HEARTBEAT).sweepOnce()

        swept shouldBe 3
        // cutoff ≈ now - 60min, within the test's own runtime of slack.
        cutoff.captured.isBefore(before.minus(Duration.ofMinutes(59))) shouldBe true
        cutoff.captured.isAfter(Instant.now().minus(Duration.ofMinutes(61))) shouldBe true
    }

    /**
     * The cutoff that actually reaps (108 §D): THREE heartbeat intervals, not one and not the
     * sixty-minute age.
     *
     * Asserted as arithmetic here and end to end against a real database in
     * `ExecutionRepositoriesIntegrationTest`. Both are needed: this one pins the number the sweep
     * asks for, that one pins which rows the number selects.
     */
    @Test
    fun `the heartbeat cutoff is three intervals, not the stale-timeout`() {
        val executions = mockk<ExecutionRepository>()
        val heartbeatCutoff = slot<Instant>()
        every { executions.sweepStaleRunning(any(), capture(heartbeatCutoff)) } returns 0
        val before = Instant.now()

        StaleExecutionSweeper(executions, Duration.ofMinutes(60), HEARTBEAT).sweepOnce()

        // ≈ now - 45s: after now-46s and before now-44s, with the test's own runtime as slack.
        heartbeatCutoff.captured.isBefore(before.minus(Duration.ofSeconds(44))) shouldBe true
        heartbeatCutoff.captured.isAfter(Instant.now().minus(Duration.ofSeconds(46))) shouldBe true
    }

    @Test
    fun `a metadata-db fault fails the tick, not the scheduler`() {
        val executions = mockk<ExecutionRepository>()
        every { executions.sweepStaleRunning(any(), any()) } throws DataAccessResourceFailureException("metadata DB down")

        val swept = StaleExecutionSweeper(executions, Duration.ofMinutes(60), HEARTBEAT).sweepOnce()

        swept shouldBe 0
    }

    @Test
    fun `a non-positive timeout is rejected at construction`() {
        val executions = mockk<ExecutionRepository>()
        runCatching { StaleExecutionSweeper(executions, Duration.ZERO, HEARTBEAT) }
            .exceptionOrNull()
            .shouldBeInstanceOf<IllegalArgumentException>()
        runCatching { StaleExecutionSweeper(executions, Duration.ofMinutes(60), Duration.ZERO) }
            .exceptionOrNull()
            .shouldBeInstanceOf<IllegalArgumentException>()
    }

    private companion object {
        /** The shipped `heartbeat-seconds`, so the arithmetic above is the arithmetic that ships. */
        val HEARTBEAT: Duration = Duration.ofSeconds(15)
    }
}
