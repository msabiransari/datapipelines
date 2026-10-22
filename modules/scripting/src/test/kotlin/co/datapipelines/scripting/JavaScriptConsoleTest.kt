package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.js
import co.datapipelines.scripting.ScriptingTestSupport.limits
import co.datapipelines.scripting.ScriptingTestSupport.refusal
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The console (#8 A.3): every level on one stream, delivered in order and bounded. */
class JavaScriptConsoleTest {
    private fun capture(
        body: String,
        input: Any? = null,
    ): Pair<Result<Any?>, List<ConsoleLine>> {
        val lines = ArrayList<ConsoleLine>()
        val outcome = runCatching { js(body, input, limits(console = { lines.add(it) })) }
        return outcome to lines
    }

    @Test
    fun `the levels arrive in the order the body wrote them`() {
        val (result, lines) =
            capture(
                "console.log('a'); console.warn('b'); console.error('c'); console.info('d'); console.debug('e'); return 1;",
            )
        result.getOrNull() shouldBe 1L
        lines shouldContainExactly
            listOf(
                ConsoleLine(ConsoleLevel.LOG, "a"),
                ConsoleLine(ConsoleLevel.WARN, "b"),
                ConsoleLine(ConsoleLevel.ERROR, "c"),
                ConsoleLine(ConsoleLevel.INFO, "d"),
                ConsoleLine(ConsoleLevel.DEBUG, "e"),
            )
    }

    @Test
    fun `arguments are joined and non-strings shown as JSON`() {
        capture("console.log('n', 1, {a: [1, 2n]}, null, undefined, true); return 1;").second shouldContainExactly
            listOf(ConsoleLine(ConsoleLevel.LOG, "n 1 {\"a\":[1,\"2\"]} null undefined true"))
    }

    @Test
    fun `a multi-line text is one line`() {
        capture("console.warn('a\\nb'); console.log('c'); return 1;").second shouldContainExactly
            listOf(ConsoleLine(ConsoleLevel.WARN, "a\nb"), ConsoleLine(ConsoleLevel.LOG, "c"))
    }

    @Test
    fun `lines are delivered when the body fails too`() {
        val (result, lines) = capture("console.log('before'); throw new Error('after');")
        (result.exceptionOrNull() is ScriptEvaluationException) shouldBe true
        lines shouldContainExactly listOf(ConsoleLine(ConsoleLevel.LOG, "before"))
    }

    @Test
    fun `at most 200 lines of at most 2000 characters, then the drop notice`() {
        val (_, lines) = capture("for (let i = 0; i < 250; i++) console.log(i === 0 ? 'y'.repeat(5000) : 'line ' + i); return 1;")
        lines shouldHaveSize ConsoleSink.MAX_LINES + 1
        lines.first().text.length shouldBe ConsoleSink.MAX_LINE_CHARS
        lines[ConsoleSink.MAX_LINES - 1].text shouldBe "line 199"
        lines.last() shouldBe ConsoleLine(ConsoleLevel.LOG, "… 50 more lines dropped")
    }

    @Test
    fun `without a sink the lines are discarded`() {
        js("console.log('nobody listens'); return 1;") shouldBe 1L
    }

    @Test
    fun `console has exactly the five methods and print is gone`() {
        js("return [Object.keys(console).sort().join(','), typeof print, typeof printErr];") shouldBe
            listOf("debug,error,info,log,warn", "undefined", "undefined")
        refusal<ScriptEvaluationException> { js("console.table([1]); return 1;") }
    }

    @Test
    fun `the JSONata engine ignores the console field`() {
        val lines = ArrayList<ConsoleLine>()
        val engine = ScriptingTestSupport.engine
        engine.evaluate(engine.compile("1 + 1"), null, limits(console = { lines.add(it) })) shouldBe 2
        lines shouldBe emptyList()
    }
}
