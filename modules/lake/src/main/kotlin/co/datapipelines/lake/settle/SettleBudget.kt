package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeLeases
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A settlement tick's work budget (record §8b "Per-tick work bound"; lane 799b decision D2). [deadline] is the
 * database-clock instant every verdict and lease comparison uses, exactly as before. [expired] reads a JVM monotonic
 * mirror minted with it, so the check before every row and statement group costs no statement and still answers while
 * the metadata server is stalled. The mirror starts before the database clock is read, so it never ends after [deadline].
 */
class SettleBudget internal constructor(
    val deadline: Instant,
    private val isExpired: () -> Boolean,
    private val left: () -> Duration,
) {
    /** True once the budget is spent: no new row, statement group or repair may start. */
    fun expired(): Boolean = isExpired()

    /** What is left of the budget on the same mirror, zero when spent: how long the tick may wait for work off its thread. */
    fun remaining(): Duration = left()

    companion object {
        /** A deadline further away than this bounds only the work mirror at this length; verdicts keep the full deadline. */
        private val MAX_MIRROR: Duration = Duration.ofDays(1)

        /** A fresh [budget] measured from the database clock: one statement. */
        fun startingNow(
            leases: LakeLeases,
            budget: Duration,
        ): SettleBudget {
            require(!budget.isNegative) { "settlement budget must not be negative: $budget" }
            val minted = System.nanoTime()
            return mirrored(leases.catalogNowPlus(budget), minted, budget)
        }

        /** The rest of a caller's database-clock [deadline]: one statement. A deadline already past is already expired. */
        fun until(
            leases: LakeLeases,
            deadline: Instant,
        ): SettleBudget {
            val minted = System.nanoTime()
            return mirrored(deadline, minted, Duration.between(leases.catalogNowPlus(Duration.ZERO), deadline))
        }

        private fun mirrored(
            deadline: Instant,
            minted: Long,
            remaining: Duration,
        ): SettleBudget {
            val end = minted + if (remaining.isNegative) 0L else minOf(remaining, MAX_MIRROR).toNanos()
            return SettleBudget(
                deadline,
                { System.nanoTime() - end >= 0 },
                { Duration.ofNanos(maxOf(end - System.nanoTime(), 0L)) },
            )
        }
    }
}

/**
 * Why a verification or a semantics repair stopped with no verdict, its row untouched for a later tick: the tick's
 * budget was spent, or the metadata read could not be waited for (D4). An exception is the third cause the sweep counts.
 */
enum class SettleDeferCause { BUDGET, STUCK_READ }

/**
 * What one settlement page did: [visited] rows whose work finished, [changed] of them moved to a terminal state.
 * [resumeAfter] is the delivery id the table's next page starts after, or null when this page reached the end of the
 * table's unsettled rows, so the next page wraps to the first (D1).
 */
data class SettleProgress(
    val visited: Int,
    val changed: Int,
    val resumeAfter: UUID?,
)

/**
 * One delivery row's settlement, or the page's projection repair, failed. [progress] already ends past the failing row,
 * so the sweep's next page for this table starts after it and a row that keeps failing cannot hide the rows behind it.
 */
class SettlementDeferred(
    val progress: SettleProgress,
    cause: Exception,
) : RuntimeException("delivery settlement deferred", cause)
