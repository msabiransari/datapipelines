package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.javaScript
import co.datapipelines.scripting.ScriptingTestSupport.js
import co.datapipelines.scripting.ScriptingTestSupport.limits
import co.datapipelines.scripting.ScriptingTestSupport.refusal
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** [JavaScriptEngine.compile] (#8 A.3): parse only, author-relative positions, the depth ceiling. */
class JavaScriptEngineCompileTest {
    @Test
    fun `a syntax error carries the body's own line and column`() {
        val syntax = refusal<ScriptSyntaxException> { javaScript.compile("let a = 1;\nreturn a +;") }
        syntax.line shouldBe 2
        syntax.column shouldBe 11
        syntax.message shouldContain "Expected an operand but found ;"
        syntax.message shouldNotContain JavaScriptEngine.AUTHOR_SOURCE_NAME
    }

    @Test
    fun `an error the wrapper's closing brace reports is placed on the body's last line`() {
        val syntax = refusal<ScriptSyntaxException> { javaScript.compile("let a = 1;\nif (a) {") }
        syntax.line shouldBe 2
        syntax.column shouldBe 1
    }

    @Test
    fun `compile runs nothing - a body's side effect leaves no trace`() {
        javaScript.compile("globalThis.__ran = true; return 1;")
        js("return typeof __ran;") shouldBe "undefined"
    }

    @Test
    fun `compile runs nothing - top-level code past an early-closed main does not execute`() {
        // Were compile to execute the source, this endless top-level loop would never return.
        val started = System.nanoTime()
        javaScript.compile("return 1; } while (true) {} {")
        withClue("compile took too long") { ((System.nanoTime() - started) / 1_000_000 < 5_000) shouldBe true }
    }

    @Test
    fun `a body nested past the parser's ceiling is the catalogued DEPTH refusal`() {
        val deep = "return " + "[".repeat(5000) + "]".repeat(5000) + ";"
        refusal<ScriptResourceLimitException> { javaScript.compile(deep) }.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
    }

    @Test
    fun `a static import is a syntax error - the body is a script, not a module`() {
        refusal<ScriptSyntaxException> { javaScript.compile("import fs from 'fs';\nreturn 1;") }
    }

    @Test
    fun `a handle from the other engine is refused`() {
        val jsonata = ScriptingTestSupport.engine.compile("1")
        refusal<IllegalArgumentException> { javaScript.evaluate(jsonata, null, limits()) }.message shouldContain
            "not produced by this engine (JAVASCRIPT)"
        val compiled = javaScript.compile("return 1;")
        refusal<IllegalArgumentException> { ScriptingTestSupport.engine.evaluate(compiled, null, limits()) }
    }

    @Test
    fun `one compiled script evaluates many times`() {
        val script = javaScript.compile("return input.n * 3;")
        (1..5).map { javaScript.evaluate(script, mapOf("n" to it), limits()) } shouldBe listOf(3L, 6L, 9L, 12L, 15L)
    }
}
