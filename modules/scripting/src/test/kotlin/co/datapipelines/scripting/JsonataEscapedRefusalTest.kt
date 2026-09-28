package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.engine
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * What the seam rethrows is what ESCAPED (#272 item 2). The library reaches the seam three
 * ways, and each is pinned here through the real library:
 *
 *  - verbatim — the engine's own refusal, or the library's `JException`;
 *  - WRAPPED — `$sort`'s comparator catches everything and rethrows it inside a plain
 *    `RuntimeException` (jsonata 0.9.10 `Functions.java:1968`): the cause chain still holds
 *    what escaped, so the seam reads it there;
 *  - REPLACED — the `and`/`or` short-circuit turns a non-`JException` into
 *    `JException("Unexpected")` and `$eval` turns anything into `D3121`, with no cause: the
 *    chain is cut, and only the refusal the engine recorded — still pending because no step
 *    ran after it — can say what escaped.
 *
 * A recorded refusal stops being pending the moment the library runs another step: the
 * evaluation continued past it, so it did not escape (`$replace` with a function swallows
 * a throwable at `Functions.java:858`). That rule is pinned at the guard's own level in
 * [JsonataEvaluationGuardTest]: no path in jsonata 0.9.10 continues after a swallow (the
 * swallowed `$replace` then fails on its null replacement), so no body can reach it.
 */
class JsonataEscapedRefusalTest {
    private val limits = EvaluationLimits(Duration.ofSeconds(10), 50, now = ScriptingTestSupport.FIXED_NOW)

    private val deepFn = "\$d := function(\$n){ \$n <= 0 ? 0 : 1 + \$d(\$n - 1) }"

    private fun failure(
        body: String,
        at: EvaluationLimits = limits,
    ): Throwable =
        runCatching { engine.evaluate(engine.compile(body), null, at) }.exceptionOrNull()
            ?: error("the body evaluated: $body")

    @Test
    fun `a script error inside sort's comparator is the script error, not the comparator's wrapper`() {
        // Pre-fix: "evaluation failed unexpectedly: com.dashjoin.jsonata.JException: inner".
        val err = failure("\$sort([1, 2, 3], function(\$a, \$b){ \$error(\"inner\") })")
        val evaluation = err.shouldBeInstanceOf<ScriptEvaluationException>()
        evaluation.message shouldBe "inner"
        evaluation.message shouldNotContain "unexpectedly"
    }

    @Test
    fun `a depth breach inside sort's comparator escapes with its own code`() {
        val err = failure("( $deepFn; \$sort([1, 2, 3], function(\$a, \$b){ \$d(200) > 0 }) )")
        err.shouldBeInstanceOf<ScriptResourceLimitException>().kind shouldBe ScriptResourceLimitException.Kind.DEPTH
    }

    @Test
    fun `a breach under the and short-circuit's replacement still reads as the breach`() {
        val err = failure("( $deepFn; true and \$d(200) > 0 )")
        err.shouldBeInstanceOf<ScriptResourceLimitException>().kind shouldBe ScriptResourceLimitException.Kind.DEPTH
    }

    @Test
    fun `the clock refusal under the and short-circuit's replacement reads as the clock refusal`() {
        // Pre-fix: the clock shadow's refusal was not recorded, so the replacement's
        // "Unexpected" text was all that reached the caller.
        val unpinned = EvaluationLimits(Duration.ofSeconds(10), 50, now = null)
        val err = failure("true and \$now() = \"x\"", unpinned)
        err.shouldBeInstanceOf<ScriptEvaluationException>().message shouldContain "now() is not available"
    }

    @Test
    fun `a breach inside an eval'd body reads as the breach through eval's D3121 replacement`() {
        val err = failure("( $deepFn; \$eval(\"\$d(200)\") )")
        err.shouldBeInstanceOf<ScriptResourceLimitException>().kind shouldBe ScriptResourceLimitException.Kind.DEPTH
    }
}
