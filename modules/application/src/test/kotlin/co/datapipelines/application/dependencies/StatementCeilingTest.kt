package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ResolvedNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * #786 R3 (record §25.4): the release provider reads each source under its [StatementCeiling] with the context's bound
 * read when that read starts; a zero bound refuses before the read; a statement cancelled at its bound is the walk's
 * [DeadlineExceeded]. The PostgreSQL behaviour itself is `ReleaseStatementCeilingPostgresTest`'s.
 */
class StatementCeilingTest {
    private val workspaceId = UUID.randomUUID()
    private val clock = MovingClock()
    private val reads = mutableListOf<ReleaseNodeIdentity>()
    private val source =
        DependencySource { identity ->
            reads += identity
            clock.now = clock.now.plusSeconds(2)
            ResolvedNode(identity, identity.entityId, ReleaseNodeStatus.RELEASED, "h", 1, emptyList(), UUID.randomUUID())
        }

    @Test
    fun `each read gets min(statement limit, remaining deadline) as it starts`() {
        val ceiling = RecordingCeiling()
        val provider = ReleaseDependencyFamilies.descriptor(ReleaseFamily.PIPELINE, source, ceiling).provider
        val requests = listOf("a", "b", "c").map { request(it) }

        provider.resolveMany(requests, context(Deadline.after(Duration.ofSeconds(9), clock), Duration.ofSeconds(5)))

        // 9 s left, then 7, then 5 (each read spends 2 s): the limit 5, then 5, then the remaining 5.
        ceiling.bounds shouldContainExactly listOf(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5))
        provider.resolveMany(listOf(request("d")), context(Deadline.after(Duration.ofSeconds(3), clock), Duration.ofSeconds(5)))
        ceiling.bounds.last() shouldBe Duration.ofSeconds(3)
    }

    @Test
    fun `a zero bound refuses before the read - a zero statement_timeout would mean none`() {
        val ceiling = RecordingCeiling()
        val provider = ReleaseDependencyFamilies.descriptor(ReleaseFamily.PIPELINE, source, ceiling).provider
        val expired = context(Deadline.after(Duration.ofSeconds(1), clock), Duration.ofSeconds(5))
        clock.now = clock.now.plusSeconds(1)

        shouldThrow<DeadlineExceeded> { provider.resolveMany(listOf(request("a")), expired) }.family shouldBe FamilyKey.PIPELINE
        reads.shouldBeEmpty()
        ceiling.bounds.shouldBeEmpty()
    }

    @Test
    fun `a statement cancelled at its bound is the walk's DeadlineExceeded, its cancellation the cause`() {
        val cancelled = StatementCancelled(QueryTimeoutException("cancelled", SQLException("canceling statement", "57014")))
        val ceiling =
            object : StatementCeiling {
                override fun <T> within(
                    bound: Duration,
                    read: () -> T,
                ): T = throw cancelled
            }
        val provider = ReleaseDependencyFamilies.descriptor(ReleaseFamily.ENDPOINT, source, ceiling).provider

        val refused =
            shouldThrow<DeadlineExceeded> {
                provider.resolveMany(listOf(request("/a/v1/x", ReleaseFamily.ENDPOINT)), context(Deadline.none(), Duration.ofSeconds(5)))
            }
        refused.family shouldBe FamilyKey.API_ENDPOINT
        refused.cause shouldBe cancelled
    }

    @Test
    fun `with neither a limit nor a deadline the read is unbounded and the ceiling is not consulted`() {
        val ceiling = RecordingCeiling()
        val provider = ReleaseDependencyFamilies.descriptor(ReleaseFamily.PIPELINE, source, ceiling).provider
        provider
            .resolveMany(listOf(request("a")), context(Deadline.none(), null))
            .values
            .single()
            .shouldBeInstanceOf<Outcome.Resolved<*>>()
        ceiling.bounds.shouldBeEmpty()
    }

    @Test
    fun `the PostgreSQL ceiling rounds up to whole milliseconds, recognises 57014 anywhere in the chain, and needs a transaction`() {
        PostgresStatementCeiling.millis(Duration.ofMillis(1500)) shouldBe 1500L
        PostgresStatementCeiling.millis(Duration.ofSeconds(1).plusNanos(1)) shouldBe 1001L
        PostgresStatementCeiling.millis(Duration.ofNanos(1)) shouldBe 1L
        PostgresStatementCeiling.cancelled(RuntimeException(QueryTimeoutException("x", SQLException("c", "57014")))) shouldBe true
        PostgresStatementCeiling.cancelled(RuntimeException(SQLException("division by zero", "22012"))) shouldBe false
        shouldThrow<IllegalStateException> { PostgresStatementCeiling(mockk()).within(Duration.ofSeconds(1)) { 1 } }
        shouldThrow<IllegalArgumentException> { PostgresStatementCeiling(mockk()).within(Duration.ZERO) { 1 } }
    }

    private fun request(
        key: String,
        family: ReleaseFamily = ReleaseFamily.PIPELINE,
    ) = ReleaseDependencyFamilies.request(ReleaseNodeIdentity(workspaceId, family, key, 1))

    private fun context(
        deadline: Deadline,
        statementLimit: Duration?,
    ) = ResolveContext(
        workspaceId,
        MetadataSnapshot.READ_COMMITTED,
        Purpose.RELEASE_PLANNING,
        Authority.System,
        statementLimit?.let(deadline::withStatementLimit) ?: deadline,
        ReadIsolation.NONE,
    )

    private class RecordingCeiling : StatementCeiling {
        val bounds = mutableListOf<Duration>()

        override fun <T> within(
            bound: Duration,
            read: () -> T,
        ): T {
            bounds += bound
            return read()
        }
    }

    private class MovingClock : Clock() {
        var now: Instant = Instant.parse("2026-11-11T00:00:00Z")

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }
}
