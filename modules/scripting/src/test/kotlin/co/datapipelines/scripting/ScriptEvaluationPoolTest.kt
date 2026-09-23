package co.datapipelines.scripting

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The pool's contract (A.4, corrected at the 7a merge): bounded admission, abandonment
 * past budget + grace, the bulkhead — an abandoned runaway keeps its slot until its thread
 * ends, and a caller waits for a slot at most its own wall clock — and the `LongAdder` a
 * caller scrapes into its meter.
 */
class ScriptEvaluationPoolTest {
    private fun pool(
        size: Int = 1,
        queue: Int = 1,
        grace: Duration = Duration.ofMillis(250),
    ): ScriptEvaluationPool = ScriptEvaluationPool(size, queue, grace, ScriptEvaluationPool.SYSTEM)

    private fun limits(budget: Duration) = EvaluationLimits(budget, ScriptEngine.DEFAULT_MAX_DEPTH)

    @Test
    fun `work completing within budget passes the value through`() {
        val p = pool()
        p.run(limits(Duration.ofSeconds(5))) { 41 + 1 } shouldBe 42
        p.abandoned.sum() shouldBe 0
    }

    @Test
    fun `an abandoned evaluation keeps its slot until its thread ends - the bulkhead`() {
        val p = pool(size = 1, queue = 1, grace = Duration.ofMillis(250))
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val timeout = AtomicReference<ScriptTimeoutException>()

        val overrunThread =
            Thread {
                try {
                    p.run(limits(Duration.ofMillis(200)), "test-script@1") {
                        started.countDown()
                        release.await() // a while-equivalent the interrupt cannot reach
                        1
                    }
                } catch (err: ScriptTimeoutException) {
                    timeout.set(err)
                }
            }
        overrunThread.isDaemon = true
        overrunThread.start()
        started.await(10, TimeUnit.SECONDS) shouldBe true
        overrunThread.join(10_000)

        // The caller failed on time and the abandonment was counted …
        timeout.get().shouldNotBeNull().code shouldBe "pipeline.transform.timeout"
        p.abandoned.sum() shouldBe 1
        p.abandonedThreadsAlive() shouldBe 1

        // … but the runaway still holds its slot: a 1/1 pool refuses the next submission
        // while the abandoned thread lives. At most `size` evaluation threads are alive,
        // abandoned ones included — a runaway never adds a thread the pool did not count.
        val refused = runCatching { p.run(limits(Duration.ofSeconds(5)), "next@1") { "served" } }.exceptionOrNull()
        (refused.shouldNotBeNull() as ScriptPoolExhaustedException).code shouldBe "pipeline.transform.pool_exhausted"

        // The slot comes back when the runaway's thread ends, and the next call is served.
        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (p.abandonedThreadsAlive() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        p.abandonedThreadsAlive() shouldBe 0
        p.run(limits(Duration.ofSeconds(5)), "next@2") { "served" } shouldBe "served"
    }

    @Test
    fun `a caller waits for a held slot at most its own wall clock, then is refused`() {
        val p = pool(size = 1, queue = 2, grace = Duration.ofSeconds(5))
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val holder =
            Thread {
                p.run(limits(Duration.ofSeconds(20)), "holder@1") {
                    started.countDown()
                    release.await()
                    1
                }
            }
        holder.isDaemon = true
        holder.start()
        try {
            started.await(10, TimeUnit.SECONDS) shouldBe true
            val waitStart = System.nanoTime()
            val refused =
                runCatching { p.run(limits(Duration.ofMillis(300)), "waiter@1") { "served" } }.exceptionOrNull()
            val waitedMs = (System.nanoTime() - waitStart) / 1_000_000
            refused.shouldNotBeNull() as ScriptPoolExhaustedException
            (waitedMs in 250..5_000) shouldBe true
        } finally {
            release.countDown()
            holder.join(10_000)
        }
        // The waiter's admission permit came back with its refusal: the pool is whole again.
        p.run(limits(Duration.ofSeconds(5)), "after@1") { "served" } shouldBe "served"
    }

    @Test
    fun `the 65th concurrent submission on a 4-of-64 pool is refused`() {
        val p = pool(size = 4, queue = 64, grace = Duration.ofSeconds(5))
        val start = CountDownLatch(1)
        val workGate = CountDownLatch(1)
        val successes =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val refusals =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val firstRefusal = AtomicReference<ScriptPoolExhaustedException>()
        val callerPool = Executors.newFixedThreadPool(65, { r -> Thread(r).apply { isDaemon = true } })
        val callers =
            (1..65).map { i ->
                Callable<Unit> {
                    start.await()
                    try {
                        p.run(limits(Duration.ofSeconds(20)), "script-$i") {
                            workGate.await()
                            i
                        }
                        successes.incrementAndGet()
                    } catch (err: ScriptPoolExhaustedException) {
                        refusals.incrementAndGet()
                        firstRefusal.compareAndSet(null, err)
                    }
                }
            }
        try {
            val futures = callers.map { callerPool.submit(it) }
            start.countDown()
            // A refusal can only exist once all 64 admission permits are held, so its
            // appearance is the deterministic signal that 64 were admitted.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (firstRefusal.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            val err = firstRefusal.get()
            err.shouldNotBeNull().runningCapacity shouldBe 4
            err.queueCapacity shouldBe 64
            workGate.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            successes.get() shouldBe 64
            refusals.get() shouldBe 1
        } finally {
            workGate.countDown()
            callerPool.shutdownNow()
        }
    }

    @Test
    fun `an evaluation failing inside the work propagates the scripting exception`() {
        val p = pool()
        val caught =
            runCatching {
                p.run(limits(Duration.ofSeconds(5))) {
                    throw ScriptEvaluationException("the body failed")
                }
            }.exceptionOrNull()
        caught.shouldNotBeNull() as ScriptEvaluationException
    }

    @Test
    fun `an await interrupted on the caller thread is not reported as a timeout`() {
        val p = pool()
        val caught =
            runCatching {
                Thread.currentThread().interrupt()
                p.run(limits(Duration.ofSeconds(5))) { 1 }
            }.exceptionOrNull()
        // A pre-set interrupt flag surfaces at the await; the pool restores the flag
        // and reports an await interruption, never a script timeout.
        caught.shouldNotBeNull() as ScriptEvaluationException
        (caught.cause is InterruptedException) shouldBe true
        Thread.interrupted() // clear the flag for the rest of the suite
    }
}
