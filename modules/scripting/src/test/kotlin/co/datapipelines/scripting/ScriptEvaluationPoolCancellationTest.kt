package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.javaScript
import co.datapipelines.scripting.ScriptingTestSupport.limits
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Cancellation through the pool (#8 A.2b): `Context.close(true)` is the only thing that stops a
 * running JavaScript body, so the pool's abandonment must reach it through [ScriptCancellation] —
 * otherwise an abandoned body keeps its thread, and its slot, for as long as it runs.
 */
class ScriptEvaluationPoolCancellationTest {
    /** Statement-light and never-ending: only an outside stop ends it. */
    private val endless = "const s = 'x'.repeat(1 << 16); for (;;) { s.split('').reverse().join(''); }"

    @Test
    fun `the engine's own watchdog stops a body at its wall clock and the slot is reusable at once`() {
        val pool = ScriptEvaluationPool(1, 1, Duration.ofSeconds(1), ScriptEvaluationPool.SYSTEM)
        val script = javaScript.compile(endless)
        val budget = limits(Duration.ofSeconds(1))
        val started = System.nanoTime()
        val outcome = runCatching { pool.run(budget, "watchdog") { javaScript.evaluate(script, null, budget) } }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS
        withClue("outcome $outcome after $elapsedMs ms") {
            (outcome.exceptionOrNull() is ScriptTimeoutException) shouldBe true
            (elapsedMs in WALL_MS until WALL_MS + GRACE_MS) shouldBe true
        }
        pool.abandoned.sum() shouldBe 0 // the engine stopped itself; the pool never had to
        pool.run(limits(), "next") { javaScript.evaluate(javaScript.compile("return 2;"), null, limits()) } shouldBe 2L
    }

    @Test
    fun `the pool's abandonment closes the Context - the thread ends and the slot comes back`() {
        // The body's OWN budget is a minute: only the pool's abandonment (1 s + 1 s grace) can stop it,
        // and only through the cancellation hook — the interrupt reaches nothing inside the isolate.
        // Queue 2: the second run is ADMITTED and waits for the one running slot (queue 1 would refuse
        // it at admission while the closed body's thread is still unwinding — a race, not the property).
        val pool = ScriptEvaluationPool(1, 2, Duration.ofSeconds(1), ScriptEvaluationPool.SYSTEM)
        val script = javaScript.compile(endless)
        val first = AtomicReference<Throwable>()
        val caller =
            Thread {
                first.set(
                    runCatching {
                        pool.run(
                            limits(Duration.ofSeconds(1)),
                            "abandoned",
                        ) { javaScript.evaluate(script, null, limits(Duration.ofSeconds(60))) }
                    }.exceptionOrNull(),
                )
            }
        caller.start()
        caller.join(JOIN_MS)
        withClue("the first caller's outcome: ${first.get()}") { (first.get() is ScriptTimeoutException) shouldBe true }
        pool.abandoned.sum() shouldBe 1
        // The second run waits for the slot at most its own wall clock (2 s): admitted only if the
        // abandoned body's thread ended — red (POOL_EXHAUSTED) when the hook is not registered.
        val second =
            runCatching {
                pool.run(limits(Duration.ofSeconds(2)), "next") { javaScript.evaluate(javaScript.compile("return 3;"), null, limits()) }
            }
        withClue("the second run: $second") { second.getOrNull() shouldBe 3L }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val WALL_MS = 1_000L
        const val GRACE_MS = 1_000L
        const val JOIN_MS = 10_000L
    }
}
