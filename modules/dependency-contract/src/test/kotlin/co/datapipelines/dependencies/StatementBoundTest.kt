package co.datapipelines.dependencies

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * #786 R3 (record §25.4): the provider resolve context carries one statement's bound — `min(statement limit, remaining
 * deadline)`, read when the statement starts — so a provider that issues statements can apply it. The limit rides the
 * context's existing [Deadline] (additive: no new context field, the same expiry), and a deadline built without one
 * keeps the cumulative budget alone.
 */
class StatementBoundTest {
    private val clock = SettableClock(Instant.parse("2026-11-11T00:00:00Z"))

    @Test
    fun `the bound is the statement limit while more of the deadline remains, then the remaining deadline`() {
        val context = context(Deadline.after(Duration.ofSeconds(30), clock).withStatementLimit(Duration.ofSeconds(5)))

        context.statementBound() shouldBe Duration.ofSeconds(5)
        clock.advance(Duration.ofSeconds(27))
        context.statementBound() shouldBe Duration.ofSeconds(3)
        clock.advance(Duration.ofSeconds(4))
        context.statementBound() shouldBe Duration.ZERO
        context.deadline.expired() shouldBe true
    }

    @Test
    fun `either half alone bounds the statement, and neither leaves it unbounded`() {
        context(Deadline.none().withStatementLimit(Duration.ofSeconds(5))).statementBound() shouldBe Duration.ofSeconds(5)
        context(Deadline.after(Duration.ofSeconds(2), clock)).statementBound() shouldBe Duration.ofSeconds(2)
        context(Deadline.none()).statementBound().shouldBeNull()
    }

    @Test
    fun `the limit changes no expiry, a deadline without it has none, and a non-positive limit is refused`() {
        val plain = Deadline.after(Duration.ofSeconds(10), clock)
        val limited = plain.withStatementLimit(Duration.ofSeconds(1))
        limited.remaining() shouldBe plain.remaining()
        limited.unbounded shouldBe false
        plain.statementLimit.shouldBeNull()
        Deadline.none().withStatementLimit(Duration.ofSeconds(1)).unbounded shouldBe true
        shouldThrow<IllegalArgumentException> { plain.withStatementLimit(Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { plain.withStatementLimit(Duration.ofSeconds(-1)) }
    }

    private fun context(deadline: Deadline) =
        ResolveContext(
            UUID.randomUUID(),
            MetadataSnapshot.READ_COMMITTED,
            Purpose.RELEASE_PLANNING,
            Authority.System,
            deadline,
            ReadIsolation.NONE,
        )
}
