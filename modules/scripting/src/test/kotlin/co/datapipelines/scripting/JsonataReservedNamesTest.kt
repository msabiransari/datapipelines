package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.engine
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The reserved prefix (#272): the library installs the engine's depth and wall-clock hooks
 * as the frame variables `__evaluate_entry`/`__evaluate_exit` and looks them up through the
 * frame chain, so a body that BINDS either name to null switches both bounds off for every
 * deeper step. Measured on the pre-fix engine (2026-09-28, jsonata 0.9.10, max depth 50): a
 * 200-deep non-tail recursion returned 200 behind each of the rebinds below.
 *
 * Every binding form the parser produces is refused at COMPILE, read from the parsed AST —
 * never a regex over the source: `:=`, a lambda parameter, a focus `@$x`, an index `#$i` (on
 * a step, and as the stage form after a predicate). `$eval` parses at evaluate time, so its
 * string is checked there, before it runs, with the same walk.
 */
class JsonataReservedNamesTest {
    private val limits = EvaluationLimits(Duration.ofSeconds(10), 50, now = ScriptingTestSupport.FIXED_NOW)

    /** A non-tail recursion 200 deep: over the depth bound of 50 unless the hooks are off. */
    private val deep = "( \$d := function(\$n){ \$n <= 0 ? 0 : 1 + \$d(\$n - 1) }; \$d(200) )"

    private fun compileRefusal(body: String): ScriptSyntaxException {
        val caught = runCatching { engine.compile(body) }.exceptionOrNull()
        withClue("compile must refuse: $body") { caught.shouldNotBeNull() }
        return caught.shouldBeInstanceOf<ScriptSyntaxException>()
    }

    @Test
    fun `a block bind of the entry hook is refused at compile, naming the reserved name`() {
        val refusal = compileRefusal("( \$__evaluate_entry := \$nonexistent; $deep )")
        refusal.code shouldBe ScriptSyntaxException.CODE
        refusal.message shouldContain "\$__evaluate_entry"
        refusal.message shouldContain "reserved"
    }

    @Test
    fun `every binding form the parser produces is refused at compile`() {
        val forms =
            mapOf(
                "bind" to "( \$__evaluate_exit := \$nonexistent; $deep )",
                "lambda parameter" to "(function(\$__evaluate_entry){ $deep })(\$nonexistent)",
                "focus" to "[1, 2]@\$__x.( $deep )",
                "index on a step" to "[1, 2]#\$__i.( $deep )",
                "index as a stage" to "items[0]#\$__i.( $deep )",
                "any other __ name" to "( \$__anything := 1; \$__anything )",
                "nested in an object value" to "{ \"a\": ( \$__evaluate_entry := null; 1 ) }",
                "nested in a lambda body" to "\$map([1], function(\$v){ ( \$__evaluate_exit := null; \$v ) })",
            )
        forms.forEach { (form, body) ->
            withClue("form '$form'") {
                compileRefusal(body).message shouldContain "reserved"
            }
        }
    }

    @Test
    fun `the refusal points at the bind's position`() {
        val refusal = compileRefusal("(\n  \$x := 1;\n  \$__evaluate_entry := null;\n  \$x\n)")
        refusal.line shouldBe 3
    }

    @Test
    fun `ordinary binds of every form still compile and evaluate - the walk refuses only the prefix`() {
        val body =
            "( \$_one := 1; \$a__b := 2; \$f := function(\$p){ \$p + \$_one + \$a__b }; " +
                "{ \"indexed\": items#\$i.{ \"v\": \$f(\$), \"i\": \$i }, \"focused\": items@\$o.(\$o * 2) } )"
        val input = linkedMapOf("items" to listOf(10, 20))
        engine.evaluate(engine.compile(body), input, limits) shouldBe
            linkedMapOf(
                "indexed" to listOf(linkedMapOf("v" to 13, "i" to 0), linkedMapOf("v" to 23, "i" to 1)),
                "focused" to listOf(20, 40),
            )
    }

    @Test
    fun `reading a reserved name is not a bind and compiles`() {
        // Only a BIND can shadow the hooks; a read cannot switch anything off.
        engine.compile("\$exists(\$__evaluate_entry)").shouldNotBeNull()
    }

    @Test
    fun `an eval'd bind of the entry hook is refused before it runs - the calling frame keeps its hooks`() {
        // Pre-fix: `$eval` bound the name in the CALLING frame, and the recursion after it
        // ran with no bounds (returned 200).
        val body = "( \$eval(\"\$__evaluate_entry := \$nonexistent\"); $deep )"
        val caught = runCatching { engine.evaluate(engine.compile(body), null, limits) }.exceptionOrNull()
        val refusal = caught.shouldBeInstanceOf<ScriptEvaluationException>()
        refusal.message shouldContain "\$__evaluate_entry"
        refusal.message shouldContain "reserved"
    }

    @Test
    fun `an eval'd lambda parameter and an eval'd string assembled at run time are refused the same way`() {
        val bodies =
            listOf(
                "\$eval(\"(function(\$__evaluate_entry){ $deep })(\$nonexistent)\")",
                "( \$eval(\"\$__evaluate\" & \"_entry := \$nonexistent\"); $deep )",
            )
        bodies.forEach { body ->
            withClue(body) {
                val caught = runCatching { engine.evaluate(engine.compile(body), null, limits) }.exceptionOrNull()
                caught.shouldBeInstanceOf<ScriptEvaluationException>().message shouldContain "reserved"
            }
        }
    }

    @Test
    fun `an ordinary eval still evaluates in the calling frame`() {
        engine.evaluate(engine.compile("( \$x := 2; \$eval(\"\$x * 21\") )"), null, limits) shouldBe 42
        engine.evaluate(engine.compile("\$eval(\"\$ + 1\", 41)"), null, limits) shouldBe 42
    }

    @Test
    fun `an eval'd body still meets the depth bound - the shadow keeps it inside the hooks`() {
        val caught = runCatching { engine.evaluate(engine.compile("\$eval(\"$deep\")"), null, limits) }.exceptionOrNull()
        caught.shouldBeInstanceOf<ScriptResourceLimitException>().kind shouldBe ScriptResourceLimitException.Kind.DEPTH
    }

    @Test
    fun `an eval'd string that does not parse keeps the library's own refusal`() {
        val caught = runCatching { engine.evaluate(engine.compile("\$eval(\"1 +\")"), null, limits) }.exceptionOrNull()
        caught.shouldBeInstanceOf<ScriptEvaluationException>().message shouldContain "eval"
    }
}
