package co.datapipelines.datasources.pooling

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Forces the generation-first order hidden by the probabilistic R152 stress supplement. */
class LakeGenerationCloseRaceTest {
    @Test
    fun `a holder arriving inside the generation close returns and a refused generation attempt stays reported`() {
        DriverManager.getConnection("jdbc:duckdb:").use { raw ->
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val attempts = AtomicInteger()
            val forgotten = AtomicInteger()
            val reported = AtomicReference<Exception?>()
            val outcome = AtomicReference<TrackedDuplicate.ReleaseOutcome?>()
            val failure = SQLException("generation's only attempt refused")
            val registry =
                object : TrackedDuplicate.Registry {
                    override fun forget(handle: TrackedDuplicate) {
                        forgotten.incrementAndGet()
                    }

                    override fun exhausted(
                        handle: TrackedDuplicate,
                        error: Exception,
                    ) {
                        reported.set(error)
                    }
                }
            val handle =
                TrackedDuplicate(
                    object : Connection by raw {
                        override fun close() {
                            attempts.incrementAndGet()
                            entered.countDown()
                            proceed.await(10, TimeUnit.SECONDS) shouldBe true
                            throw failure
                        }
                    },
                    registry,
                )
            val release = Thread { outcome.set(handle.releaseByGeneration()) }.apply { start() }
            try {
                entered.await(10, TimeUnit.SECONDS) shouldBe true
                handle.close() // Deliberate second-caller contract: no wait and no driver call.
                attempts.get() shouldBe 1
            } finally {
                proceed.countDown()
                release.join(10_000)
            }
            release.isAlive shouldBe false
            outcome.get() shouldBe TrackedDuplicate.ReleaseOutcome.FAILED
            reported.get() shouldBe failure
            forgotten.get() shouldBe 0
            attempts.get() shouldBe 1
            raw.isClosed shouldBe false
        }
    }
}
