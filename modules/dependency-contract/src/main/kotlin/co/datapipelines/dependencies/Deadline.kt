package co.datapipelines.dependencies

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The cumulative metadata deadline of one walk (record §25.4): every provider statement uses the smaller of its own
 * limit and [remaining], and the walk refuses to start a batch once it [expired] ([DeadlineExceeded]). Built over an
 * injectable [Clock] so a test controls time; [none] is unbounded (G1's release-planning seam, which #786 activates).
 */
class Deadline private constructor(
    private val clock: Clock,
    private val at: Instant?,
    /** #786 R3: one statement's own ceiling (§25.4 `statement-timeout-seconds`); null = none beyond the deadline. */
    val statementLimit: Duration? = null,
) {
    /**
     * This deadline with [limit] as the per-statement ceiling (#786 R3, additive): the same clock and the same expiry, so
     * the cumulative budget is unchanged; only [statementBound] reads the limit.
     */
    fun withStatementLimit(limit: Duration): Deadline {
        require(limit > Duration.ZERO) { "a statement limit is positive, got $limit" }
        return Deadline(clock, at, limit)
    }

    /**
     * The bound of a statement starting NOW (§25.4): `min(statementLimit, remaining())`, whichever of the two is set;
     * null when neither is. [Duration.ZERO] once the deadline has passed — a provider never starts a statement on a zero
     * bound (to PostgreSQL `0` means no timeout); it refuses with [DeadlineExceeded] instead.
     */
    fun statementBound(): Duration? {
        val remaining = remaining()
        return when {
            statementLimit == null -> remaining
            remaining == null -> statementLimit
            else -> minOf(statementLimit, remaining)
        }
    }

    /** The time left, never negative; null when unbounded. */
    fun remaining(): Duration? = at?.let { Duration.between(clock.instant(), it).coerceAtLeast(Duration.ZERO) }

    /** True once the deadline has passed; never for an unbounded deadline. */
    fun expired(): Boolean = at != null && !clock.instant().isBefore(at)

    /** True for [none]. */
    val unbounded: Boolean get() = at == null

    override fun toString(): String =
        (if (at == null) "Deadline.none" else "Deadline(at=$at)") + (statementLimit?.let { "[statement=$it]" } ?: "")

    companion object {
        private val NONE = Deadline(Clock.systemUTC(), null)

        /** No deadline: [remaining] is null and the deadline never expires. */
        fun none(): Deadline = NONE

        /** A deadline [budget] after [clock]'s now. */
        fun after(
            budget: Duration,
            clock: Clock = Clock.systemUTC(),
        ): Deadline {
            require(!budget.isNegative) { "a deadline budget is not negative, got $budget" }
            return Deadline(clock, clock.instant().plus(budget))
        }
    }
}

/**
 * The walk refused to start a provider batch after its [Deadline] expired — never a partial graph (§25.4). The caller
 * maps it to its own refusal (G4: `dependencies.metadata.unavailable`; #786: `<family>.release.metadata_unavailable`).
 */
class DeadlineExceeded(
    val family: FamilyKey,
) : RuntimeException("the metadata deadline expired before a $family batch")
