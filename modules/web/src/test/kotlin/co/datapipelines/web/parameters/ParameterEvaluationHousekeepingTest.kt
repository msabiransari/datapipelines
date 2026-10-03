package co.datapipelines.web.parameters

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.parameters.ParameterEvaluationRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import java.util.UUID

/**
 * The evaluation history's two hourly steps (#376; metadata-db §8.1, §8.5): the sweep's cutoff is the evaluate deadline
 * plus the named margin, the retention passes the event-retention days and its bounded batch, and a storage fault is
 * one WARN line by class and SQLState — never the message — with the step answering 0. The statements themselves run
 * against Postgres in `ParameterEvaluationHistoryIntegrationTest` and the scheduled tick in the E2E.
 */
class ParameterEvaluationHousekeepingTest {
    private val repository = mockk<ParameterEvaluationRepository>()

    @Test
    fun `the sweep's cutoff is the evaluate deadline plus the margin - and it closes what the statement returns`() {
        every { repository.sweepStale(90) } returns listOf(UUID.randomUUID(), UUID.randomUUID())

        val sweeper = ParameterEvaluationSweeper(repository, evaluateTimeoutSeconds = 30)

        sweeper.staleAfterSeconds shouldBe 30 + ParameterEvaluationSweeper.MARGIN_SECONDS
        sweeper.sweepOnce() shouldBe 2
        verify(exactly = 1) { repository.sweepStale(90) }
    }

    @Test
    fun `the retention deletes one batch on the event-retention cutoff - a full batch says the backlog remains`() {
        every { repository.deleteFinishedOlderThan(7, 3) } returns 3

        val lines =
            captured(ParameterEvaluationRetention::class.java) {
                ParameterEvaluationRetention(repository, 7, batchSize = 3).retainOnce() shouldBe
                    3
            }

        lines.single { it.level == Level.WARN }.formattedMessage shouldContain "event=parameter.evaluation_retention_incomplete count=3"
    }

    @Test
    fun `a storage fault is one WARN by class and SQLState - the steps answer 0 and never throw`() {
        every { repository.sweepStale(any()) } throws DataAccessResourceFailureException("SECRET statement text")
        every { repository.deleteFinishedOlderThan(any(), any()) } throws DataAccessResourceFailureException("SECRET statement text")

        val sweep = captured(ParameterEvaluationSweeper::class.java) { ParameterEvaluationSweeper(repository, 30).sweepOnce() shouldBe 0 }
        val retain =
            captured(ParameterEvaluationRetention::class.java) {
                ParameterEvaluationRetention(repository, 7).retainOnce() shouldBe
                    0
            }

        sweep.single().formattedMessage shouldContain "event=parameter.evaluation_sweep_failed"
        retain.single().formattedMessage shouldContain "event=parameter.evaluation_retention_failed"
        (sweep + retain).forEach { it.formattedMessage shouldNotContain "SECRET" }
    }

    @Test
    fun `a non-positive deadline, retention or batch is refused at construction`() {
        shouldThrow<IllegalArgumentException> { ParameterEvaluationSweeper(repository, 0) }
        shouldThrow<IllegalArgumentException> { ParameterEvaluationRetention(repository, 0) }
        shouldThrow<IllegalArgumentException> { ParameterEvaluationRetention(repository, 7, batchSize = 0) }
    }

    private fun captured(
        type: Class<*>,
        block: () -> Unit,
    ): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(type) as ch.qos.logback.classic.Logger
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
