package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.engine
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The evaluate contract: JSON-shaped in, JSON-shaped out, nested arbitrarily (§4.1's
 * input shape round-trip), the pinned clock reproducible (A.3 choice (a)).
 */
class JsonataEngineEvaluateTest {
    private val limits get() = ScriptingTestSupport.DEFAULT_LIMITS

    private fun unpinnedLimits() =
        EvaluationLimits(
            wallClock = limits.wallClock,
            maxDepth = limits.maxDepth,
            now = null,
        )

    @Test
    fun `the identity body round-trips every JSON shape`() {
        val shapes: List<Any?> =
            listOf(
                null,
                "text",
                42,
                3.14,
                true,
                false,
                listOf(1, "two", null, true),
                linkedMapOf("a" to 1, "b" to listOf("x"), "c" to linkedMapOf("d" to null)),
            )
        shapes.forEach { shape ->
            engine.evaluate(engine.compile("$"), shape, limits) shouldBe shape
        }
    }

    @Test
    fun `a deeply nested input round-trips eight levels down`() {
        var value: Any? = "leaf"
        repeat(8) { value = linkedMapOf("n$it" to value) }
        engine.evaluate(engine.compile("$"), value, limits) shouldBe value
    }

    @Test
    fun `a body computes over the input - nested paths and sequences`() {
        val input =
            linkedMapOf(
                "rows" to
                    listOf(
                        linkedMapOf("price" to 2, "qty" to 3),
                        linkedMapOf("price" to 10, "qty" to 4),
                    ),
            )
        engine.evaluate(engine.compile("[rows.(price * qty)]"), input, limits) shouldBe listOf(6, 40)
    }

    @Test
    fun `a body matching nothing evaluates to null`() {
        engine.evaluate(engine.compile("nothing.here"), linkedMapOf("a" to 1), limits).shouldBeNull()
    }

    @Test
    fun `an undefined function is an evaluation failure with a bounded message`() {
        val caught =
            runCatching {
                engine.evaluate(engine.compile("foo(1)"), null, limits)
            }.exceptionOrNull()
        val failure = caught.shouldNotBeNull() as ScriptEvaluationException
        failure.code shouldBe "pipeline.transform.evaluation_failed"
        val message = failure.message.shouldNotBeNull()
        (message.length <= ScriptEvaluationException.MAX_MESSAGE_CHARS + 60) shouldBe true
    }

    @Test
    fun `a numeric result keeps the library's own fitting`() {
        engine.evaluate(engine.compile("2 + 2"), null, limits) shouldBe 4
        engine.evaluate(engine.compile("1 / 3"), null, limits) shouldBe 1.0 / 3.0
    }

    @Test
    fun `a foreign CompiledScript is refused`() {
        val other = object : CompiledScript {}
        val caught = runCatching { engine.evaluate(other, null, limits) }.exceptionOrNull()
        val foreign = caught.shouldNotBeNull()
        check(foreign is IllegalArgumentException) { "expected IAE, was $foreign" }
    }

    // --- The clock (A.3 (a)): pinned $now()/$millis() --------------------------------

    @Test
    fun `the pinned now returns the same instant twice a second apart`() {
        val pinned = Instant.parse("2026-09-23T12:00:00Z")
        val script = engine.compile("\$now() & \"|\" & \$millis()")
        val pinnedLimits =
            EvaluationLimits(
                wallClock = limits.wallClock,
                maxDepth = limits.maxDepth,
                now = pinned,
            )
        // The library's default $now() picture renders three fractional digits.
        val expected = "2026-09-23T12:00:00.000Z|${pinned.toEpochMilli()}"
        val first = engine.evaluate(script, null, pinnedLimits)
        Thread.sleep(1_000)
        val second = engine.evaluate(script, null, pinnedLimits)
        first shouldBe expected
        second shouldBe expected
    }

    @Test
    fun `the pinned now honours the picture-string form`() {
        val out =
            engine.evaluate(
                engine.compile("\$now(\"[Y0001]-[M01]-[D01]T[h01]:[m01]\")"),
                null,
                limits,
            )
        out shouldBe "2026-09-23T12:00"
    }

    @Test
    fun `an unpinned clock call is a pure-function refusal`() {
        val now =
            runCatching { engine.evaluate(engine.compile("\$now()"), null, unpinnedLimits()) }
                .exceptionOrNull()
        val failure = now.shouldNotBeNull() as ScriptEvaluationException
        failure.message shouldContain "pure function of its inputs"
        val millis =
            runCatching { engine.evaluate(engine.compile("\$millis()"), null, unpinnedLimits()) }
                .exceptionOrNull()
        millis.shouldNotBeNull() as ScriptEvaluationException
        // An unevaluated branch never touches the clock: laziness is legal.
        engine.evaluate(engine.compile("false ? \$now() : 42"), null, unpinnedLimits()) shouldBe 42
    }
}
