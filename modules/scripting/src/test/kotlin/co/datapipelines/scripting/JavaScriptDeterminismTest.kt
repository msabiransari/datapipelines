package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.FIXED_NOW
import co.datapipelines.scripting.ScriptingTestSupport.javaScript
import co.datapipelines.scripting.ScriptingTestSupport.js
import co.datapipelines.scripting.ScriptingTestSupport.limits
import co.datapipelines.scripting.ScriptingTestSupport.refusal
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Cooperative determinism (#8 A.3): the pinned clock, the refused entropy, UTC and en-US. */
class JavaScriptDeterminismTest {
    private val unpinned = limits(now = null)

    @Test
    fun `every no-argument read of now returns the pinned instant`() {
        val millis = FIXED_NOW.toEpochMilli()
        js("return Date.now();") shouldBe millis
        js("return new Date().toISOString();") shouldBe "2026-09-23T12:00:00.000Z"
        js("return Date();") shouldBe "Wed Sep 23 2026 12:00:00 GMT+0000 (GMT+0)"
        js("return new Intl.DateTimeFormat('en-US').format();") shouldBe "9/23/2026"
        js("return new Intl.DateTimeFormat('en-US').formatToParts().map(p => p.value).join('');") shouldBe "9/23/2026"
    }

    @Test
    fun `a date from arguments is the date it names, and Date keeps its statics and identity`() {
        js("return new Date(0).toISOString();") shouldBe "1970-01-01T00:00:00.000Z"
        js("return Date.UTC(2020, 0, 1);") shouldBe 1_577_836_800_000L
        js("return Date.parse('2020-01-01T00:00:00Z');") shouldBe 1_577_836_800_000L
        js("return [new Date() instanceof Date, new Date(5).constructor === Date, typeof Date.prototype.getTime];") shouldBe
            listOf(true, true, "function")
        js("class Stamp extends Date { label() { return 'at ' + this.getTime(); } } return new Stamp(7).label();") shouldBe "at 7"
    }

    @Test
    fun `without a pinned clock every read of now is refused - even when the body catches it`() {
        listOf("return Date.now();", "return new Date().getTime();", "return Date();", "return new Intl.DateTimeFormat().format();")
            .forEach { body ->
                refusal<ScriptEvaluationException> { js(body, limits = unpinned) }.message!!.contains("refused: no pinned clock") shouldBe
                    true
            }
        refusal<ScriptEvaluationException> { js("try { Date.now(); } catch (e) {} return 1;", limits = unpinned) }.message shouldBe
            "Date.now() refused: no pinned clock"
        js("return new Date(0).getTime();", limits = unpinned) shouldBe 0L
    }

    @Test
    fun `Math random is always refused - no seed exists`() {
        refusal<ScriptEvaluationException> { js("return Math.random();") }.message shouldBe
            "Math.random() refused: a script is a pure function of its inputs"
        refusal<ScriptEvaluationException> { js("try { Math.random(); } catch (e) {} return 1;") }
    }

    @Test
    fun `the zone is UTC and the locale en-US`() {
        js("return [new Date(0).getTimezoneOffset(), (1234.5).toLocaleString(), new Date(0).toLocaleDateString()];") shouldBe
            listOf(0L, "1,234.5", "1/1/1970")
        js("return Intl.DateTimeFormat().resolvedOptions().timeZone;") shouldBe "UTC"
    }

    @Test
    fun `no other time or entropy source exists in the guest`() {
        js(
            "return ['performance', 'crypto', 'setTimeout', 'setInterval', 'queueMicrotask', 'process', 'Temporal', 'require']" +
                ".filter(n => typeof globalThis[n] !== 'undefined');",
        ) shouldBe emptyList<String>()
    }

    @Test
    fun `parallel evaluations of one compiled script are deterministic`() {
        val script = javaScript.compile("return input.map(n => n * n).reduce((a, b) => a + b, 0) + Date.now();")
        val inputs = (0 until 64).map { i -> List(50) { it + i } }
        val expected = inputs.map { input -> input.sumOf { (it * it).toLong() } + FIXED_NOW.toEpochMilli() }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(inputs.map { input -> Callable { javaScript.evaluate(script, input, limits()) } })
            results.map { it.get() } shouldContainExactly expected
        } finally {
            executor.shutdown()
            executor.awaitTermination(30, TimeUnit.SECONDS)
        }
    }
}
