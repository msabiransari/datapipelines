package co.datapipelines.web.api

import co.datapipelines.executor.LogContext
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The correlation id's MDC slot has ONE name (#337; observability.md §3.3): the request thread
 * writes it through [CorrelationId]'s constant (`auth`'s `AuthErrorWriter.MDC_KEY`), and the
 * executor's coroutine element writes it through [LogContext]'s own — `dag` must not depend on
 * `auth`, so the string is duplicated by design and THIS test is the drift guard. A rename on
 * either side without the other splits the execution's log lines from every request line.
 */
class CorrelationMdcKeyDriftTest {
    @Test
    fun `the executor's MDC keys are the request's MDC keys`() {
        LogContext.MDC_CORRELATION_ID shouldBe CorrelationId.MDC_KEY
        LogContext.MDC_EXECUTION_ID shouldBe "execution_id"
    }
}
