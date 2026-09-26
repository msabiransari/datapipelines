package co.datapipelines.integration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * [retryingDeadlockVictim] — the E2E cleaner's answer to the application's boot-time jobs: a
 * statement Postgres aborts as a deadlock victim (SQLSTATE 40P01) runs again; anything else
 * propagates on the first throw, and the last attempt's deadlock propagates too.
 */
class DeadlockRetryTest {
    @Test
    fun `a deadlock victim runs again and the retry's result is returned`() {
        var calls = 0
        val result =
            retryingDeadlockVictim(attempts = 3, pauseMillis = 0) {
                calls += 1
                if (calls < 3) throw SQLException("deadlock detected", "40P01")
                "done"
            }
        result shouldBe "done"
        calls shouldBe 3
    }

    @Test
    fun `any other failure propagates on the first attempt`() {
        var calls = 0
        val thrown =
            shouldThrow<SQLException> {
                retryingDeadlockVictim(attempts = 3, pauseMillis = 0) {
                    calls += 1
                    throw SQLException("relation does not exist", "42P01")
                }
            }
        thrown.sqlState shouldBe "42P01"
        calls shouldBe 1
    }

    @Test
    fun `the last attempt's deadlock propagates`() {
        var calls = 0
        val thrown =
            shouldThrow<SQLException> {
                retryingDeadlockVictim(attempts = 2, pauseMillis = 0) {
                    calls += 1
                    throw SQLException("deadlock detected", "40P01")
                }
            }
        thrown.sqlState shouldBe "40P01"
        calls shouldBe 2
    }
}
