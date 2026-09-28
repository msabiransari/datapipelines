package co.datapipelines.scripting

import com.dashjoin.jsonata.JException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The guard's own answer to "what escaped?" (#272 item 2), at the level no JSONata body can
 * reach in jsonata 0.9.10: a refusal the library SWALLOWED and went on past. The one library
 * path that swallows a throwable (`$replace` with a function, `Functions.java:858`) then fails
 * on its own null replacement, so no body continues after a swallow — the rule is pinned here,
 * against the day a library version does. The paths a body DOES reach are pinned through the
 * real library in [JsonataEscapedRefusalTest].
 */
class JsonataEvaluationGuardTest {
    /** Depth 2, a frozen clock: only the depth bound can fire. */
    private fun guard() = JsonataEvaluationGuard(EvaluationLimits(Duration.ofSeconds(10), 2), ScriptClock { 0L })

    /** Drives a guard past its depth bound and returns the refusal it threw. */
    private fun breach(guard: JsonataEvaluationGuard): ScriptResourceLimitException {
        guard.onEntry()
        guard.onEntry()
        return shouldThrow<ScriptResourceLimitException> { guard.onEntry() }
    }

    @Test
    fun `a refusal the library went on past never labels a later script error`() {
        val guard = guard()
        breach(guard)
        guard.onExit() // the library ran another step: the refusal was swallowed, not escaped
        val escaped = guard.escaped(JException("D3137", -1, "genuine"))
        escaped.shouldBeInstanceOf<ScriptEvaluationException>()
    }

    @Test
    fun `an entry after the refusal clears it the same way`() {
        val guard = guard()
        breach(guard)
        guard.onExit()
        guard.onExit()
        guard.onEntry() // back under the bound: a fresh step, no fresh refusal
        guard.escaped(JException("D3137", -1, "genuine")).shouldBeInstanceOf<ScriptEvaluationException>()
    }

    @Test
    fun `a refusal still pending answers for a library replacement that cut the chain`() {
        val guard = guard()
        val refusal = breach(guard)
        // The and/or short-circuit's replacement: a JException with no cause, no step after.
        guard.escaped(JException("Unexpected", 7)) shouldBeSameInstanceAs refusal
    }

    @Test
    fun `an engine refusal in the chain is what escaped, whatever is pending`() {
        val guard = guard()
        breach(guard)
        val other = ScriptTimeoutException(Duration.ofSeconds(1), "")
        guard.escaped(RuntimeException(other)) shouldBeSameInstanceAs other
    }

    @Test
    fun `a wrapped library error with nothing pending is the script error's own text`() {
        val guard = guard()
        val escaped = guard.escaped(RuntimeException(JException("D3137", -1, "inner")))
        escaped.shouldBeInstanceOf<ScriptEvaluationException>().cause.shouldBeInstanceOf<JException>()
    }

    @Test
    fun `anything else is an unexpected failure naming itself`() {
        val escaped = guard().escaped(IllegalStateException("boom"))
        escaped.shouldBeInstanceOf<ScriptEvaluationException>().message shouldBe "evaluation failed unexpectedly: boom"
    }
}
