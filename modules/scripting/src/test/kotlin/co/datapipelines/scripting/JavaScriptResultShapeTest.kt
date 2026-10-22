package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.js
import co.datapipelines.scripting.ScriptingTestSupport.refusal
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The return-shape contract (#8 A.3): what main may return, and the host's decode as the boundary. */
class JavaScriptResultShapeTest {
    private fun refusedWith(
        body: String,
        message: String,
    ) {
        withClue(body) { refusal<ScriptEvaluationException> { js(body) }.message shouldBe message }
    }

    @Test
    fun `JSON-shaped values pass, recursively`() {
        js("return {a: null, b: [true, 'x', 1.5], c: {d: []}, e: Object.create(null)};") shouldBe
            mapOf(
                "a" to null,
                "b" to listOf(true, "x", java.math.BigDecimal("1.5")),
                "c" to mapOf("d" to emptyList<Any>()),
                "e" to emptyMap<String, Any>(),
            )
    }

    @Test
    fun `non-finite numbers are refused with their path`() {
        refusedWith("return {x: NaN};", "result is not JSON-shaped: result.x is NaN")
        refusedWith("return [1, Infinity];", "result is not JSON-shaped: result[1] is Infinity")
        refusedWith("return {a: {b: [-Infinity]}};", "result is not JSON-shaped: result.a.b[0] is -Infinity")
    }

    @Test
    fun `nothing returned is refused`() {
        refusedWith("return undefined;", "main returned nothing")
        refusedWith("let a = 1;", "main returned nothing")
    }

    @Test
    fun `an undefined array element or a hole is refused, an undefined property is dropped`() {
        refusedWith("return [1, undefined];", "result is not JSON-shaped: result[1] is undefined")
        refusedWith("return [1, , 3];", "result is not JSON-shaped: result[1] is a hole")
        js("return {a: 1, b: undefined};") shouldBe mapOf("a" to 1L)
    }

    @Test
    fun `functions and symbols are refused anywhere`() {
        refusedWith("return {f: () => 1};", "result is not JSON-shaped: result.f is a function")
        refusedWith("return [Symbol('s')];", "result is not JSON-shaped: result[0] is a symbol")
    }

    @Test
    fun `a Promise or thenable is refused - main returns synchronously`() {
        refusedWith("return Promise.resolve(1);", "result is not JSON-shaped: result is a Promise (main must return synchronously)")
        refusedWith("return {then() {}};", "result is not JSON-shaped: result is a Promise (main must return synchronously)")
        refusedWith("return import('x');", "result is not JSON-shaped: result is a Promise (main must return synchronously)")
    }

    @Test
    fun `objects stringify would silently repair are refused`() {
        refusedWith("return new Map();", "result is not JSON-shaped: result is a Map")
        refusedWith("return {s: new Set([1])};", "result is not JSON-shaped: result.s is a Set")
        refusedWith("return new Uint8Array(2);", "result is not JSON-shaped: result is a Uint8Array")
        refusedWith("return new ArrayBuffer(2);", "result is not JSON-shaped: result is a ArrayBuffer")
        refusedWith("return new Date(0);", "result is not JSON-shaped: result is a Date")
        refusedWith("class P { constructor() { this.x = 1; } } return new P();", "result is not JSON-shaped: result is a P")
    }

    @Test
    fun `a cycle is refused, a shared value is not`() {
        refusedWith("const a = {}; a.self = a; return a;", "result is not JSON-shaped: result.self is a cycle back to result")
        js("const s = {v: 1}; return [s, s];") shouldBe listOf(mapOf("v" to 1L), mapOf("v" to 1L))
    }

    @Test
    fun `a reassigned JSON stringify changes nothing - the prelude captured its own`() {
        js("JSON.stringify = () => '{\"forged\":true}'; return {real: 1};") shouldBe mapOf("real" to 1L)
    }

    @Test
    fun `a getter that answers the check and stringify differently still yields only JSON`() {
        // The check is an aid: here it sees 1, stringify sees a Map (written as {}). The host decodes
        // strict JSON, so the worst a body gets past the aid is a JSON value it did not mean.
        js("let n = 0; return { get a() { return n++ === 0 ? 1 : new Map(); } };") shouldBe mapOf("a" to emptyMap<String, Any>())
    }

    @Test
    fun `the host decode is the boundary - strict JSON only`() {
        listOf("NaN", "{\"a\":1,\"a\":2}", "1 2", "[1,]", "'x'", "").forEach { text ->
            withClue(text) { refusal<ScriptingException> { JavaScriptValues.decodeResult(text) } }
        }
        refusal<ScriptResourceLimitException> { JavaScriptValues.decodeResult("[".repeat(1001) + "]".repeat(1001)) }.kind shouldBe
            ScriptResourceLimitException.Kind.DEPTH
        JavaScriptValues.decodeResult("[".repeat(1000) + "]".repeat(1000))
    }

    @Test
    fun `a result nested past the decoder's cap is DEPTH`() {
        refusal<ScriptResourceLimitException> { js("let v = []; for (let i = 0; i < 2000; i++) v = [v]; return v;") }.kind shouldBe
            ScriptResourceLimitException.Kind.DEPTH
    }
}
