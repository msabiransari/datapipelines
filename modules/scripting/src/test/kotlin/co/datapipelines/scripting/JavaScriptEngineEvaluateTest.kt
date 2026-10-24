package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.js
import co.datapipelines.scripting.ScriptingTestSupport.refusal
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** [JavaScriptEngine.evaluate] (#8 A.3): the body as `main(input)`, guest errors, the sandbox's surface. */
class JavaScriptEngineEvaluateTest {
    @Test
    fun `the body is main's statements and input is its argument`() {
        js(
            "const rows = input.rows;\nreturn rows.map(r => ({ id: r.id, total: r.a + r.b }));",
            mapOf("rows" to listOf(mapOf("id" to "x", "a" to 1, "b" to 2))),
        ) shouldBe
            listOf(mapOf("id" to "x", "total" to 3L))
    }

    @Test
    fun `a guest error carries its message and the body's own line`() {
        val failed = refusal<ScriptEvaluationException> { js("let a = 1;\nthrow new TypeError('boom');") }
        failed.message shouldBe "TypeError: boom (line 2)"
        failed.code shouldBe ScriptEvaluationException.CODE
    }

    @Test
    fun `an error thrown in a helper reports the innermost line in the body`() {
        val failed = refusal<ScriptEvaluationException> { js("function f() {\n  return null.x;\n}\nreturn f();") }
        failed.message shouldContain "TypeError"
        failed.message shouldContain "(line 2)"
    }

    @Test
    fun `a thrown non-error value is the message`() {
        refusal<ScriptEvaluationException> { js("throw 'plain';") }.message shouldBe "plain (line 1)"
    }

    @Test
    fun `a guest message is bounded`() {
        refusal<ScriptEvaluationException> { js("throw new Error('x'.repeat(10000));") }.message!!.length shouldBe
            ScriptEvaluationException.MAX_MESSAGE_CHARS
    }

    @Test
    fun `a host class is not reachable - Java is a ReferenceError, not a TypeError`() {
        val failed = refusal<ScriptEvaluationException> { js("return Java.type('java.lang.Runtime');") }
        failed.message shouldContain "ReferenceError: Java is not defined"
        failed.message shouldNotContain "host class"
    }

    @Test
    fun `eval and Function run inside the same sandbox`() {
        js("return eval('1 + 2') + new Function('a', 'return a * 2')(5);") shouldBe 13L
    }

    @Test
    fun `a body may declare a nested main - it shadows nothing the engine calls`() {
        js("function main() { return 'inner'; }\nreturn main() + '!';") shouldBe "inner!"
    }

    @Test
    fun `a body that closes main's brace early runs its top-level code under the same sandbox`() {
        // The frame is a convention, not a fence: the top-level line runs when the source evaluates.
        js("return typeof globalThis.leaked; } globalThis.leaked = 1; {") shouldBe "number"
    }

    @Test
    fun `each evaluation starts from a fresh global - nothing crosses evaluations`() {
        js("globalThis.carry = 1; return 1;")
        js("return typeof carry;") shouldBe "undefined"
    }
}
