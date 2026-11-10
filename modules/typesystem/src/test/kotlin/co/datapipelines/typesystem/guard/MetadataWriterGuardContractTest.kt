package co.datapipelines.typesystem.guard

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/**
 * #841 (G0) — the family-neutral guard vocabulary in `typesystem`: the key order every writer shares, the one busy
 * refusal shape, the replanning loop and the unit-test [MetadataWriterGuard.NONE]. The JDBC implementation's lock
 * statements and deadline are proven in `web` (`JdbcReleaseMutationGuardDeadlineTest`, the real-server suites).
 */
class MetadataWriterGuardContractTest {
    @Test
    fun `prerequisite keys sort by namespace then canonical name - the acquisition order every writer shares`() {
        val keys = listOf(PrerequisiteKey.datasource("warehouse"), PrerequisiteKey.datasource("lake"), PrerequisiteKey.datasource("m"))

        keys.sorted().map(PrerequisiteKey::name) shouldContainExactly listOf("lake", "m", "warehouse")
        PrerequisiteKey.datasource("lake") shouldBe PrerequisiteKey(PrerequisiteNamespace.DATASOURCE, "lake")
    }

    @Test
    fun `a prerequisite key refuses a blank name - no alias or empty identity can evade the key`() {
        shouldThrow<IllegalArgumentException> { PrerequisiteKey.datasource(" ") }
    }

    @Test
    fun `the prerequisite lock space is disjoint from the endpoint registry's single bigint key`() {
        PrerequisiteNamespace.DATASOURCE.lockSpace shouldBe DATASOURCE_LOCK_SPACE
        val spaces = PrerequisiteNamespace.entries.map(PrerequisiteNamespace::lockSpace)
        spaces.distinct().size shouldBe spaces.size
        PrerequisiteNamespace.DATASOURCE.lockSpace.toLong() shouldNotBe EndpointRegistryLock.KEY
    }

    @Test
    fun `the busy refusal carries the caller's code, the release message, exactly lock_wait_seconds and no cause by default`() {
        val refusal = WriterBusy.refusal(CODE, LOCK_WAIT_SECONDS)

        refusal.code shouldBe CODE
        refusal.message shouldBe "Another change in this workspace is in progress. Nothing was written; try again in a moment."
        refusal.details shouldBe mapOf("lock_wait_seconds" to LOCK_WAIT_SECONDS)
        refusal.cause.shouldBeNull()
    }

    @Test
    fun `a real lock timeout keeps its 55P03 exception as the cause - shape 1`() {
        val timeout = SQLException("canceling statement due to lock timeout", "55P03")

        WriterBusy.refusal(CODE, LOCK_WAIT_SECONDS, timeout).cause shouldBeSameInstanceAs timeout
    }

    @Test
    fun `the replan signal is control flow - it carries no stack trace`() {
        KeySetChanged().stackTrace.toList().shouldBeEmpty()
    }

    @Test
    fun `replanning retries a changed key set inside ONE deadline scope and returns the first settled attempt`() {
        val guard = ScopeCountingGuard()
        var attempts = 0

        val result =
            Replanning.run(guard) {
                attempts++
                if (attempts < SETTLES_ON) throw KeySetChanged()
                "granted"
            }

        result shouldBe "granted"
        attempts shouldBe SETTLES_ON
        guard.scopes shouldBe 1
    }

    @Test
    fun `replanning ends on the deadline's refusal, never on a count - any other failure propagates untouched`() {
        val guard = ScopeCountingGuard()
        var attempts = 0
        val busy = WriterBusy.refusal(CODE, LOCK_WAIT_SECONDS)

        val thrown =
            shouldThrow<DatapipelinesException> {
                Replanning.run(guard) {
                    attempts++
                    if (attempts < SETTLES_ON) throw KeySetChanged()
                    throw busy
                }
            }

        thrown shouldBeSameInstanceAs busy
        attempts shouldBe SETTLES_ON
        guard.scopes shouldBe 1
    }

    @Test
    fun `NONE takes no lock and runs the block once - the unit-test construction default`() {
        val guard = MetadataWriterGuard.NONE
        guard.prerequisites(listOf(PrerequisiteKey.datasource("lake")), CODE)
        guard.workspaces(listOf(UUID.randomUUID()), CODE)
        guard.endpointRegistry(CODE)
        var runs = 0

        guard.withinOneDeadline { ++runs } shouldBe 1
        runs shouldBe 1
    }

    /** Records how many deadline scopes [Replanning] opened; takes no lock. */
    private class ScopeCountingGuard : MetadataWriterGuard by MetadataWriterGuard.NONE {
        var scopes = 0

        override fun <T> withinOneDeadline(block: () -> T): T {
            scopes++
            return block()
        }
    }

    private companion object {
        const val CODE = "datasource.write.busy"
        const val LOCK_WAIT_SECONDS = 5
        const val SETTLES_ON = 3
        const val DATASOURCE_LOCK_SPACE = 841_001
    }
}
