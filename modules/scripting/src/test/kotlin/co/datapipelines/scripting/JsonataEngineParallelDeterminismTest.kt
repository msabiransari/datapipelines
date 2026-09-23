package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.DEFAULT_LIMITS
import co.datapipelines.scripting.ScriptingTestSupport.engine
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safety proven, not believed (the README claims "thread safe"; A.2 demands the
 * proof): ONE compiled expression evaluated from 32 threads × 1000 evaluations with
 * distinct inputs — every result equal to the single-threaded result, computed first.
 */
class JsonataEngineParallelDeterminismTest {
    private val body = "{\"n\": \$.n * 3 + 1, \"tag\": \$.tag & \"-done\"}"

    @Test
    fun `same input twice - same output`() {
        val script = engine.compile(body)
        val input = linkedMapOf("a" to 21, "b" to "hi")
        engine.evaluate(script, input, DEFAULT_LIMITS) shouldBe engine.evaluate(script, input, DEFAULT_LIMITS)
    }

    @Test
    fun `one compiled expression from 32 threads x 1000 evaluations with distinct inputs`() {
        val script = engine.compile(body)

        fun inputFor(n: Int) = linkedMapOf("n" to n, "tag" to "t${n / 1000}")

        // The single-threaded reference results, computed BEFORE any concurrency.
        val expected =
            (0 until 32_000).associateWith { n ->
                engine.evaluate(script, inputFor(n), DEFAULT_LIMITS)
            }

        val pool = Executors.newFixedThreadPool(32)
        val failures = AtomicInteger(0)
        val work =
            (0 until 32).map { thread ->
                Callable<Unit> {
                    repeat(1000) { i ->
                        val n = thread * 1000 + i
                        try {
                            engine.evaluate(script, inputFor(n), DEFAULT_LIMITS) shouldBe expected[n]
                        } catch (
                            @Suppress("SwallowedException") err: AssertionError,
                        ) {
                            // counted, then asserted below
                            failures.incrementAndGet()
                        }
                    }
                }
            }
        try {
            work.map { pool.submit(it) }.forEach { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        failures.get() shouldBe 0
    }
}
