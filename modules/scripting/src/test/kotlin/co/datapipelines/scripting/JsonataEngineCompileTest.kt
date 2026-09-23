package co.datapipelines.scripting

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Compile-time contract: parse only, syntax errors carry 1-based line/column, nothing
 * else fails at compile (semantic errors are evaluate's job — §4.1).
 */
class JsonataEngineCompileTest {
    private val engine get() = ScriptingTestSupport.engine

    @Test
    fun `a valid body compiles and reports the engine type`() {
        engine.compile("$") shouldNotBeNull { }
        engine.type shouldBe ScriptLanguage.JSONATA
    }

    @Test
    fun `a syntax error carries line and column`() {
        // The library's offset for S0201 on "foo bar" points past the path (position
        // 7, 0-based) - the gate derives 1-based column 8. Pinned to the library's own
        // behaviour, which is the position a template author sees.
        val caught = runCatching { engine.compile("foo bar") }.exceptionOrNull()
        val syntax = caught.shouldNotBeNull() as ScriptSyntaxException
        syntax.line shouldBe 1
        syntax.column shouldBe 8
        syntax.message shouldContain "syntax error"
    }

    @Test
    fun `a syntax error on a later line reports the line`() {
        val body = "\$\n  \$.a\n  2 +\n"
        val caught = runCatching { engine.compile(body) }.exceptionOrNull()
        val syntax = caught.shouldNotBeNull() as ScriptSyntaxException
        syntax.line shouldBe 2
        syntax.column shouldBe 4
    }

    @Test
    fun `an empty body is a syntax error`() {
        val caught = runCatching { engine.compile("") }.exceptionOrNull()
        (caught.shouldNotBeNull()) as ScriptSyntaxException
    }
}
