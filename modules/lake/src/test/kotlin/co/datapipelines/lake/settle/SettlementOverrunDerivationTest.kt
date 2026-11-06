package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.append.LakeAppendRuntime
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.ResourceHolderSupport
import java.time.Duration

/**
 * Record §8b's permitted overrun past the tick's budget, recomputed term by term from the source constants, so a moved
 * constant cannot leave the sentence in docs/lake.md §8 behind it. The timing itself is measured by
 * `SettlementOverrunTimingIntegrationTest`; this class holds the arithmetic and the premises it stands on.
 */
class SettlementOverrunDerivationTest {
    @Test
    fun `Spring rounds a transaction's remaining time up to whole seconds, the one second the sum adds to five`() {
        val holder = object : ResourceHolderSupport() {}
        holder.setTimeoutInMillis(TTL_JUST_OVER_ONE_SECOND_MILLIS)

        holder.timeToLiveInSeconds shouldBe 2
    }

    @Test
    fun `the unit in flight is the acquire's transaction bound plus the rounding, the same as a statement group's`() {
        LakeLeases.ACQUIRE_TRANSACTION_SECONDS shouldBe LakeAppendRuntime.STATEMENT_TIMEOUT_SECONDS
        inFlightSeconds() shouldBe EXPECTED_IN_FLIGHT_SECONDS
    }

    @Test
    fun `the overrun is the unit in flight, the statement that must follow it and the heartbeat`() {
        val following = LakeAppendRuntime.STATEMENT_TIMEOUT_SECONDS.toLong()
        val heartbeat = LakeAppendRuntime.STATEMENT_TIMEOUT_SECONDS.toLong()

        Duration.ofSeconds(inFlightSeconds() + following + heartbeat) shouldBe LakeSettlementSweep.PERMITTED_OVERRUN
    }

    @Test
    fun `the whole wall clock adds the mode read before the budget and the budget itself`() {
        val total =
            Duration.ofSeconds(LakeAppendRuntime.STATEMENT_TIMEOUT_SECONDS.toLong()) +
                LakeSettlementSweep.SWEEP_BUDGET +
                LakeSettlementSweep.PERMITTED_OVERRUN

        total shouldBe Duration.ofSeconds(STATED_WALL_CLOCK_SECONDS)
    }

    @Test
    fun `a projection repair at the deadline stays inside the overrun at the default Redis timeouts`() {
        val configuration = LakeTestDb.repoFile("docs/configuration.md").readText()
        configuration shouldContain "| `datapipelines.redis.command-timeout` | `${REDIS_SECONDS}s` |"
        configuration shouldContain "| `datapipelines.redis.connect-timeout` | `${REDIS_SECONDS}s` |"

        val projection = inFlightSeconds() + REDIS_SECONDS + REDIS_SECONDS + LakeAppendRuntime.STATEMENT_TIMEOUT_SECONDS

        (projection <= LakeSettlementSweep.PERMITTED_OVERRUN.toSeconds()) shouldBe true
        projection shouldBe STATED_PROJECTION_SECONDS
    }

    @Test
    fun `the lake document states the numbers this class derives`() {
        val lake = LakeTestDb.repoFile("docs/lake.md").readText().replace(Regex("\\s+"), " ")

        lake shouldContain "**A tick ends at most ${LakeSettlementSweep.PERMITTED_OVERRUN.toSeconds()} seconds after its budget**"
        lake shouldContain "whole wall clock is at most $STATED_WALL_CLOCK_SECONDS seconds"
        lake shouldContain "$STATED_PROJECTION_SECONDS s in all"
    }

    private fun inFlightSeconds(): Long = LakeLeases.ACQUIRE_TRANSACTION_SECONDS + SPRING_ROUNDING_SECONDS

    private companion object {
        const val SPRING_ROUNDING_SECONDS = 1L
        const val EXPECTED_IN_FLIGHT_SECONDS = 6L
        const val STATED_WALL_CLOCK_SECONDS = 31L
        const val STATED_PROJECTION_SECONDS = 15L
        const val REDIS_SECONDS = 2L
        const val TTL_JUST_OVER_ONE_SECOND_MILLIS = 1_500L
    }
}
