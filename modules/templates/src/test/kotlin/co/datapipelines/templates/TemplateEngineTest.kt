package co.datapipelines.templates

import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * [TemplateEngine]: render, import synthesis (§6.3), type-aware interpolation (§4.4), and the
 * two render guards (§4.3).
 */
class TemplateEngineTest {
    private val engines = mutableListOf<TemplateEngine>()

    @AfterEach
    fun tearDown() = engines.forEach { it.close() }

    private fun engine(
        vararg versions: TemplateVersion,
        timeoutMs: Long = 5_000,
        maxOutputChars: Long = 1_000_000,
    ): TemplateEngine = TemplateEngine(InMemoryTemplateRegistry(versions.toList()), 50, timeoutMs, maxOutputChars).also { engines += it }

    private fun render(
        engine: TemplateEngine,
        ref: TemplateRef,
        context: Map<String, Any?>,
    ): String = engine.render(ref, context)

    @Test
    fun `renders a plain interpolation`() {
        val e = engine(TemplateFixtures.version("t.sql", body = "SELECT * FROM o WHERE id = \${order_id}"))
        render(e, TemplateRef("t.sql", 1), mapOf("order_id" to 42)) shouldBe "SELECT * FROM o WHERE id = 42"
    }

    @Test
    fun `synthesizes an import prologue and calls a library macro`() {
        val lib =
            TemplateFixtures.version(
                "lib_date.sql",
                isLibrary = true,
                body = "<#macro date_range column start end>\${column} BETWEEN '\${start}' AND '\${end}'</#macro>",
            )
        val main =
            TemplateFixtures.version(
                "orders.sql",
                imports = listOf(TemplateImport("lib_date.sql", 1, "dates")),
                body = "WHERE <@dates.date_range column=\"order_date\" start=start end=end/>",
            )
        val out = render(engine(lib, main), TemplateRef("orders.sql", 1), mapOf("start" to "2026-01-01", "end" to "2026-12-31"))
        out shouldBe "WHERE order_date BETWEEN '2026-01-01' AND '2026-12-31'"
    }

    @Test
    fun `resolves transitive imports through the loader`() {
        val libB = TemplateFixtures.version("libb.sql", isLibrary = true, body = "<#macro inner v>[\${v}]</#macro>")
        val libA =
            TemplateFixtures.version(
                "liba.sql",
                isLibrary = true,
                imports = listOf(TemplateImport("libb.sql", 1, "b")),
                body = "<#macro outer v><@b.inner v=v/></#macro>",
            )
        val main =
            TemplateFixtures.version(
                "main.sql",
                imports = listOf(TemplateImport("liba.sql", 1, "a")),
                body = "<@a.outer v=\"hi\"/>",
            )
        render(engine(libB, libA, main), TemplateRef("main.sql", 1), emptyMap()) shouldBe "[hi]"
    }

    @Test
    fun `interpolates each canonical type per §4-4`() {
        val body =
            "i=\${i} d=\${d} b=\${flag} day=\${day} ts=\${ts} tm=\${tm} bin=\${bin}"
        val e = engine(TemplateFixtures.version("types.sql", body = body))
        val out =
            render(
                e,
                TemplateRef("types.sql", 1),
                mapOf(
                    "i" to 42,
                    "d" to BigDecimal("12345.67"),
                    "flag" to true,
                    "day" to LocalDate.of(2026, 8, 5),
                    "ts" to Instant.parse("2026-08-05T14:30:00Z"),
                    "tm" to LocalTime.of(14, 30, 0),
                    "bin" to byteArrayOf(1, 2, 3),
                ),
            )
        out shouldBe "i=42 d=12345.67 b=true day=2026-08-05 ts=2026-08-05T14:30:00Z tm=14:30:00 bin=AQID"
    }

    @Test
    fun `a decimal keeps its declared scale and a large double never goes scientific`() {
        // §4.4: DECIMAL/BIGDECIMAL render "with declared scale", approximate decimals render plain.
        // Both are trailing-zero / notation cases no built-in Freemarker number format produces —
        // see PlainNumberFormat. The 12345.67 case above passes under either, so it cannot guard this.
        val e = engine(TemplateFixtures.version("n.sql", body = "\${money} \${big} \${whole}"))
        render(
            e,
            TemplateRef("n.sql", 1),
            mapOf("money" to BigDecimal("1000.00"), "big" to 1.0e10, "whole" to BigDecimal("42.000")),
        ) shouldBe "1000.00 10000000000 42.000"
    }

    @Test
    fun `an undefined variable is classified, not thrown, by execute`() {
        val e = engine(TemplateFixtures.version("t.sql", body = "SELECT \${missing}"))
        e.execute(TemplateRef("t.sql", 1), emptyMap()).shouldBeInstanceOf<RenderOutcome.UndefinedVariable>()
    }

    @Test
    fun `render() throws a run-time render exception on failure`() {
        val e = engine(TemplateFixtures.version("t.sql", body = "SELECT \${missing}"))
        val thrown = shouldThrow<DatapipelinesException> { render(e, TemplateRef("t.sql", 1), emptyMap()) }
        thrown.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED
    }

    @Test
    fun `a missing reference carries template_not_found, not template_render_failed`() {
        // templates.md §8.2 splits these two: "Template {id, version} not found →
        // pipeline.node.template_not_found", everything else → template_render_failed. A single
        // catch-all code would tell an operator the template is broken when it is simply absent.
        val e = engine(TemplateFixtures.version("present.sql"))

        val thrown = shouldThrow<DatapipelinesException> { render(e, TemplateRef("absent.sql", 1), emptyMap()) }

        thrown.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.TEMPLATE_NOT_FOUND
    }

    @Test
    fun `a runaway loop trips the render timeout`() {
        val e = engine(TemplateFixtures.version("t.sql", body = "<#list 1..2000000000 as i></#list>"), timeoutMs = 150)
        val outcome = e.execute(TemplateRef("t.sql", 1), emptyMap())
        outcome.shouldBeInstanceOf<RenderOutcome.Failed>().detail shouldContain "timeout"
    }

    @Test
    fun `oversized output trips the size cap`() {
        val e =
            engine(
                TemplateFixtures.version("t.sql", body = "<#list 1..100000 as i>xxxxxxxxxx</#list>"),
                maxOutputChars = 100,
            )
        val outcome = e.execute(TemplateRef("t.sql", 1), emptyMap())
        outcome.shouldBeInstanceOf<RenderOutcome.Failed>().detail shouldContain "cap"
    }

    @Test
    fun `an aborted render really terminates its worker, it is not merely abandoned`() {
        // TPL-SEC-4, the assertion that matters. A plain Thread.interrupt() does NOT abort a
        // Freemarker render — verified against the pinned jar, the worker below stays RUNNABLE
        // indefinitely without the interruption post-processor. Asserting `future.isDone()` (or
        // just the timeout classification) would pass in exactly that broken state, because the
        // *caller* returns either way; only the worker's death proves the guard works.
        //
        // The body writes nothing, so neither the output cap nor the writer can stop it.
        val e = engine(TemplateFixtures.version("t.sql", body = RUNAWAY_SILENT_BODY), timeoutMs = 150)

        e.execute(TemplateRef("t.sql", 1), emptyMap()).shouldBeInstanceOf<RenderOutcome.Failed>()

        withClue("the render worker must unwind after the timeout, not run on burning a core") {
            awaitActiveRendersToDrain(e).shouldBeTrue()
        }
    }

    @Test
    fun `the render pool is configured bounded, with a bounded queue and an abort policy`() {
        // Replaces a tautology (HIGH-1). The old test submitted 8 renders SEQUENTIALLY and asserted
        // `poolThreads <= MAX_CONCURRENT_RENDERS` — i.e. `min(8, MAX) <= MAX`, true by
        // construction. Reverting to `newCachedThreadPool`, the exact TPL-SEC-4 regression, left it
        // green. The pool's CONFIGURATION is what the fix consists of, so that is what is asserted.
        val executor = engine(TemplateFixtures.version("t.sql")).renderExecutor

        withClue("a fixed-size pool: core == max, or the pool grows a thread per concurrent render") {
            executor.corePoolSize shouldBe TemplateEngine.MAX_CONCURRENT_RENDERS
            executor.maximumPoolSize shouldBe TemplateEngine.MAX_CONCURRENT_RENDERS
        }
        withClue("a BOUNDED queue: an unbounded one makes maximumPoolSize unreachable and never rejects") {
            executor.queue.remainingCapacity() shouldBe TemplateEngine.MAX_QUEUED_RENDERS
        }
        withClue("reject rather than run-on-the-caller: a caller-run render cannot be interrupted") {
            executor.rejectedExecutionHandler.shouldBeInstanceOf<ThreadPoolExecutor.AbortPolicy>()
        }
    }

    @Test
    fun `a render submitted beyond pool and queue capacity is rejected, not queued forever`() {
        // The rejection branch had zero coverage. Workers are occupied WITHOUT burning CPU: the
        // registry blocks inside `lookup`, which the loader calls on the worker thread, so exactly
        // MAX_CONCURRENT_RENDERS renders park there and the queue fills deterministically.
        val gate = CountDownLatch(1)
        val registry = BlockingRegistry(gate, TemplateFixtures.version("t.sql", body = "SELECT 1"))
        val e = TemplateEngine(registry, cacheSize = 10, renderTimeoutMs = 30_000, maxOutputChars = 1_000)
        engines += e

        val overCapacity = TemplateEngine.MAX_CONCURRENT_RENDERS + TemplateEngine.MAX_QUEUED_RENDERS + 1
        val outcomes = ConcurrentLinkedQueue<RenderOutcome>()
        val callers =
            (1..overCapacity).map {
                Thread { outcomes += e.execute(TemplateRef("t.sql", 1), emptyMap()) }
                    .apply {
                        isDaemon = true
                        start()
                    }
            }

        try {
            val rejected = awaitRejection(outcomes)
            withClue("one submission beyond the ${overCapacity - 1} slots must be refused, not queued") {
                rejected.shouldNotBeNull().detail shouldContain "render capacity exhausted"
            }
        } finally {
            gate.countDown()
            callers.forEach { it.join(WORKER_DRAIN_TIMEOUT_MS) }
        }
    }

    /** Polls [outcomes] for the capacity refusal until the drain timeout. */
    private fun awaitRejection(outcomes: Collection<RenderOutcome>): RenderOutcome.Failed? {
        val deadline = System.nanoTime() + WORKER_DRAIN_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            outcomes
                .filterIsInstance<RenderOutcome.Failed>()
                .firstOrNull { it.detail.contains("capacity exhausted") }
                ?.let { return it }
            Thread.sleep(WORKER_POLL_MS)
        }
        return null
    }

    @Test
    fun `a per-render output cap overrides the engine default`() {
        // TPL-API-4: `dag` injects this engine (dag-executor §5.2), so it cannot set the staging
        // budget at construction — it must be settable per render. Both directions are asserted,
        // so a signature that silently ignored the argument would fail here.
        val body = "<#list 1..100 as i>0123456789</#list>"
        val e = engine(TemplateFixtures.version("t.sql", body = body), maxOutputChars = 1_000_000)

        e
            .execute(TemplateRef("t.sql", 1), emptyMap())
            .shouldBeInstanceOf<RenderOutcome.Success>()
            .sql.length shouldBe 1_000

        e
            .execute(TemplateRef("t.sql", 1), emptyMap(), maxOutputChars = 100)
            .shouldBeInstanceOf<RenderOutcome.Failed>()
            .detail shouldContain "100-character cap"

        shouldThrow<DatapipelinesException> { e.render(TemplateRef("t.sql", 1), emptyMap(), maxOutputChars = 100) }
    }

    @Test
    fun `every construct section 4-2 permits renders under the hardened configuration`() {
        // TPL-TEST-7. The forbidden list is exercised exhaustively (SstiMatrixTest,
        // ForbiddenConstructSpecDriftTest); this is the other side — proof that hardening §4.3 for
        // the hostile case did not quietly break legitimate authoring. Every permitted construct
        // §4.2 names appears here.
        val body =
            "<#assign label = \"orders\">" +
                "SELECT \${total?c} AS t, '\${name?upper_case}\${name?lower_case}' AS n, \${count?size} AS c" +
                "<#if flag> WHERE 1=1<#elseif other> WHERE 2=2<#else> WHERE 3=3</#if>" +
                "<#list items as i> /*\${i}*/</#list>" +
                "<#switch mode><#case \"a\">A<#break><#default>D</#switch>" +
                " \${missing?default(\"fallback\")} \${label} \${amount?string(\"0.##\")}" +
                "<#if items?has_content> HAS</#if>"
        val e = engine(TemplateFixtures.version("ok.sql", body = body))

        val out =
            render(
                e,
                TemplateRef("ok.sql", 1),
                mapOf(
                    "total" to 5,
                    "name" to "Ab",
                    "count" to listOf(1, 2, 3),
                    "flag" to true,
                    "other" to false,
                    "items" to listOf("x", "y"),
                    "mode" to "a",
                    "amount" to java.math.BigDecimal("1.5"),
                ),
            )

        out shouldBe "SELECT 5 AS t, 'ABab' AS n, 3 AS c WHERE 1=1 /*x*/ /*y*/A fallback orders 1.5 HAS"
    }

    @Test
    fun `a macro and a function defined in a library both render through an alias`() {
        // TPL-TEST-9 round trip, and the running proof that a `<#function>`-only library is
        // usable — the §6.2 reading LibraryBodyCheck pins.
        val lib =
            TemplateFixtures.version(
                "lib_calc.sql",
                isLibrary = true,
                body =
                    "<#function to_cents amount><#return amount * 100></#function>" +
                        "<#macro above column value>\${column} > \${value}</#macro>",
            )
        val main =
            TemplateFixtures.version(
                "calc.sql",
                imports = listOf(TemplateImport("lib_calc.sql", 1, "c")),
                body = "WHERE <@c.above column=\"amount\" value=c.to_cents(3)/>",
            )

        render(engine(lib, main), TemplateRef("calc.sql", 1), emptyMap()) shouldBe "WHERE amount > 300"
    }

    @Test
    fun `a runaway inside an IMPORTED library is aborted too`() {
        // InterruptibleConfiguration claims the transitive `<#import>` path funnels through the
        // overridden 6-arg getTemplate, so an imported library is made interruptible as well. That
        // claim was untested (MEDIUM-3) — and it is the interesting half, because a library is
        // loaded by Freemarker itself at render time, not by this engine's own getTemplate call.
        val lib =
            TemplateFixtures.version(
                "lib_burn.sql",
                isLibrary = true,
                body = "<#macro burn>$RUNAWAY_SILENT_BODY</#macro>",
            )
        val main =
            TemplateFixtures.version(
                "main.sql",
                imports = listOf(TemplateImport("lib_burn.sql", 1, "b")),
                body = "<@b.burn/>",
            )
        val e = engine(lib, main, timeoutMs = 200)

        e.execute(TemplateRef("main.sql", 1), emptyMap()).shouldBeInstanceOf<RenderOutcome.Failed>()

        withClue("the worker running the LIBRARY's loop must unwind, not just the main template's") {
            awaitActiveRendersToDrain(e).shouldBeTrue()
        }
    }

    @Test
    fun `the parsed-template cache is bounded by cacheSize`() {
        // `evictIfOverCap` was unexercised. Rendering more distinct templates than the cache holds
        // must keep working (correctness) — eviction is safe precisely because an evicted key is
        // re-parsed into a fresh, unshared tree.
        val cacheSize = 4
        val versions = (1..(cacheSize * 3)).map { TemplateFixtures.version("t$it.sql", body = "SELECT $it") }
        val e = TemplateEngine(InMemoryTemplateRegistry(versions), cacheSize, 5_000, 1_000_000).also { engines += it }

        repeat(2) {
            versions.forEachIndexed { index, version ->
                withClue("render ${version.id} on pass $it") {
                    e.render(TemplateRef(version.id, 1), emptyMap()) shouldBe "SELECT ${index + 1}"
                }
            }
        }
    }

    private fun awaitActiveRendersToDrain(engine: TemplateEngine): Boolean {
        val deadline = System.nanoTime() + WORKER_DRAIN_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (engine.activeRenders == 0) return true
            Thread.sleep(WORKER_POLL_MS)
        }
        return engine.activeRenders == 0
    }

    private companion object {
        /**
         * A runaway that writes **nothing**, so neither [BoundedWriter] nor the output cap can
         * stop it. Only an interrupt that Freemarker actually honours ends this render.
         */
        const val RUNAWAY_SILENT_BODY = "<#list 1..2000000000 as i><#assign x = i></#list>"

        const val WORKER_DRAIN_TIMEOUT_MS = 5_000L
        const val WORKER_POLL_MS = 25L
    }
}
