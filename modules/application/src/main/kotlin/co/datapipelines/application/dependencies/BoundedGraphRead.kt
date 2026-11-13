package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.ResolveContext
import java.time.Duration

/**
 * The bounded batch read of G3's two non-artifact providers (#874; dependency-graph record §7.1, §7.2, §25.4). One
 * `resolveMany` call is ONE parameterised statement, and [read] runs it:
 *
 * - a batch above [GraphLimits.MAX_RESOLVE_BATCH_SIZE] is refused on entry — the walker never sends one, and a provider
 *   never loops per key to absorb it;
 * - a deadline already spent refuses before anything is sent ([DeadlineExceeded]: to PostgreSQL a `0` timeout is "none");
 * - the statement runs under the [ceiling] with the context's LIVE `statementBound()` (`min(statement limit, remaining
 *   deadline)` read when the statement starts, #786 R3), and a statement cancelled at its bound — or not issued because
 *   the bound was spent — is the same [DeadlineExceeded], the ceiling's exception its cause;
 * - the deadline is checked AGAIN after the rows are read: a result that arrives after expiry is discarded and refused,
 *   never returned as outcomes (G1's walker checks only before a batch, record §25.4 refuses an exceeded plan).
 *
 * Every failure propagates as the infrastructure failure — never MISSING, never an empty result.
 */
internal object BoundedGraphRead {
    fun <T> run(
        family: FamilyKey,
        requests: List<ObjectRequest>,
        context: ResolveContext,
        ceiling: StatementCeiling,
        read: () -> T,
    ): T {
        require(requests.size <= GraphLimits.MAX_RESOLVE_BATCH_SIZE) {
            "a $family batch holds at most ${GraphLimits.MAX_RESOLVE_BATCH_SIZE} requests, got ${requests.size}"
        }
        val value = bounded(family, context, ceiling, read)
        if (context.deadline.expired()) throw DeadlineExceeded(family)
        return value
    }

    @Suppress("ThrowsCount") // spent before the read, spent bound, cancelled in flight: three distinct DeadlineExceeded causes
    private fun <T> bounded(
        family: FamilyKey,
        context: ResolveContext,
        ceiling: StatementCeiling,
        read: () -> T,
    ): T {
        if (context.deadline.expired()) throw DeadlineExceeded(family)
        val bound = context.statementBound() ?: return read()
        if (bound <= Duration.ZERO) throw DeadlineExceeded(family)
        return try {
            ceiling.within(context::statementBound, read)
        } catch (reached: StatementBoundReached) {
            throw DeadlineExceeded(family).apply { initCause(reached) }
        }
    }
}
