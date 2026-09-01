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
        every { executions.sweepStaleRunning(capture(cutoff)) } returns 3
        val before = Instant.now()

        val swept = StaleExecutionSweeper(executions, Duration.ofMinutes(60)).sweepOnce()

        swept shouldBe 3
        // cutoff ≈ now - 60min, within the test's own runtime of slack.
        cutoff.captured.isBefore(before.minus(Duration.ofMinutes(59))) shouldBe true
        cutoff.captured.isAfter(Instant.now().minus(Duration.ofMinutes(61))) shouldBe true
    }

    @Test
    fun `a metadata-db fault fails the tick, not the scheduler`() {
        val executions = mockk<ExecutionRepository>()
        every { executions.sweepStaleRunning(any()) } throws DataAccessResourceFailureException("metadata DB down")

        val swept = StaleExecutionSweeper(executions, Duration.ofMinutes(60)).sweepOnce()

        swept shouldBe 0
    }

    @Test
    fun `a non-positive timeout is rejected at construction`() {
        val executions = mockk<ExecutionRepository>()
        runCatching { StaleExecutionSweeper(executions, Duration.ZERO) }
            .exceptionOrNull()
            .shouldBeInstanceOf<IllegalArgumentException>()
    }
}
