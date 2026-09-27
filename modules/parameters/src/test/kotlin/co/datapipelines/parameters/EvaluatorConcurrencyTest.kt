package co.datapipelines.parameters

import co.datapipelines.parameters.EvaluatorFixtures.templateSelect
import co.datapipelines.parameters.EvaluatorFixtures.version
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The evaluator's concurrency (record §12's evaluator row, P16, P31) over scripted selectors —
 * synchronised on events (latches, the scripts' own start records), never on a sleep:
 * independent selectors run concurrently, a child never starts before its parent completed, the
 * bulkhead bounds the RUNNING statements, and the deadline fails the whole request without waiting.
 */
class EvaluatorConcurrencyTest {
    private val selectors = ScriptedSelectors()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val released = AtomicBoolean(false)

    @AfterEach
    fun stop() {
        released.set(true)
        scope.cancel()
    }

    private fun evaluator(
        pool: SelectorPool = SelectorPool(4, 64),
        config: ParametersConfig = ParametersConfig(),
    ) = ParameterEvaluator(selectors, pool, config)

    /** A script that spins until the test releases it — it reads no interrupt (the stand-in models "never returns"). */
    private fun spinning(started: AtomicInteger): (SelectorRequest) -> SelectorRun =
        {
            started.incrementAndGet()
            while (!released.get()) Thread.onSpinWait()
            ScriptedSelectors.options("X")
        }

    @Test
    fun `two independent selectors run concurrently - each reaches a latch only the other can open`() {
        val both = CountDownLatch(2)
        val met = AtomicInteger()
        val rendezvous: (SelectorRequest) -> SelectorRun = {
            both.countDown()
            if (both.await(RENDEZVOUS_S, TimeUnit.SECONDS)) met.incrementAndGet()
            ScriptedSelectors.options("X")
        }
        selectors["acme/sales/a.sql"] = rendezvous
        selectors["acme/sales/b.sql"] = rendezvous

        evaluator().evaluateBlocking(EvaluatorFixtures.WORKSPACE, version(templateSelect("a"), templateSelect("b")), emptyMap())

        withClue("had they run one after the other, the first would have waited out the latch alone") { met.get() shouldBe 2 }
    }

    @Test
    fun `a child never starts before its parent completed - it binds the parent's value`() {
        // The parent holds until the child starts OR a bound passes: awaited, the child cannot start, so the
        // parent ends first; a child that did not await would start at once and the parent would see it.
        selectors["acme/sales/a.sql"] = {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PARENT_HOLD_MS)
            while (System.nanoTime() < deadline && "start:acme/sales/b.sql" !in selectors.events) Thread.onSpinWait()
            ScriptedSelectors.options("P")
        }
        selectors["acme/sales/b.sql"] = { ScriptedSelectors.options("C") }

        evaluator().evaluateBlocking(
            EvaluatorFixtures.WORKSPACE,
            version(templateSelect("a"), templateSelect("b", dependsOn = listOf("a"))),
            emptyMap(),
        )

        selectors.events.indexOf("end:acme/sales/a.sql") shouldBeLessThan selectors.events.indexOf("start:acme/sales/b.sql")
        selectors.requestsFor("acme/sales/b.sql").single().binds["a"] shouldBe "P"
    }

    @Test
    fun `the bulkhead bounds RUNNING statements - a fifth independent selector waits while four spin`() {
        val started = AtomicInteger()
        (1..5).forEach { selectors["acme/sales/s$it.sql"] = spinning(started) }
        val set = version(*(1..5).map { templateSelect("s$it") }.toTypedArray())
        val pool = SelectorPool(size = 4, waiting = 64)

        val evaluate = scope.async { evaluator(pool).evaluate(EvaluatorFixtures.WORKSPACE, set, emptyMap()) }
        waitUntil { started.get() == 4 && pool.admitted() == 5 }

        withClue("five admitted, four running: the fifth slot never opens while four spin") { started.get() shouldBe 4 }
        released.set(true)
        runBlocking { withTimeout(COMPLETES_MS) { evaluate.await() } }.valid shouldBe true
        started.get() shouldBe 5
    }

    @Test
    fun `the deadline fails the WHOLE request, answered without waiting for the spinning statements`() {
        val started = AtomicInteger()
        selectors["acme/sales/a.sql"] = spinning(started)
        selectors["acme/sales/b.sql"] = spinning(started)
        val pool = SelectorPool(4, 64)
        val oneSecond = ParametersConfig(evaluateTimeoutSeconds = 1, selectorQueryTimeoutSeconds = 1)
        val begun = System.nanoTime()

        val call =
            scope.async {
                runCatching {
                    evaluator(
                        pool,
                        oneSecond,
                    ).evaluate(EvaluatorFixtures.WORKSPACE, version(templateSelect("a"), templateSelect("b")), emptyMap())
                }
            }
        val outcome = runBlocking { withTimeout(COMPLETES_MS) { call.await() } }

        outcome.exceptionOrNull().shouldBeInstanceOf<DatapipelinesException>().code shouldBe ParameterErrorCodes.EVALUATE_TIMEOUT
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) shouldBeLessThan ANSWERED_WITHIN_MS
        withClue("the statements still spin; both were abandoned, and each worker keeps its slot") {
            started.get() shouldBe 2
            pool.abandoned.sum() shouldBe 2L
            pool.abandonedThreadsAlive() shouldBe 2
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMPLETES_MS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val RENDEZVOUS_S = 5L
        const val PARENT_HOLD_MS = 500L
        const val COMPLETES_MS = 10_000L
        const val ANSWERED_WITHIN_MS = 3_000L
        const val POLL_MS = 10L
    }
}
