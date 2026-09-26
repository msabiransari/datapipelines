package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.regex.Pattern

/**
 * The `pattern` constraint's two ReDoS defences (parameter-engine record §3.5, P31): the static
 * scan refuses the constructs a budget cannot reason about, and the read budget ends ANY match —
 * including a catastrophic one the scan lets through — after a bounded amount of work.
 */
class PatternGuardTest {
    @Test
    fun `the scan refuses backreferences, lookaround, possessive quantifiers, atomic groups and COMMENTS`() {
        val refused =
            listOf(
                "(a)\\1",
                "(?<n>a)\\k<n>",
                "a(?=b)",
                "a(?!b)",
                "(?<=a)b",
                "(?<!a)b",
                "(?>a+)b",
                "a*+",
                "a++",
                "a?+",
                "a{2}+",
                "(?x)a b",
                "(?ix)a",
                "(?x:a)",
            )
        refused.forEach { pattern -> withClue(pattern) { PatternGuard.unsafeConstruct(pattern).shouldNotBeNull() } }
    }

    @Test
    fun `the scan reads escapes and classes as the parser does - look-alikes are allowed`() {
        val allowed =
            listOf(
                "\\+",
                "[+]+",
                "\\\\1",
                "(?<name>a)",
                "(?:a)",
                "(?i)abc",
                "\\Q(?=\\E",
                "[(?=]",
                "a+?",
                "[0-9]{4}",
                "[a\\]]+",
            )
        allowed.forEach { pattern -> withClue(pattern) { PatternGuard.unsafeConstruct(pattern).shouldBeNull() } }
    }

    @Test
    fun `a pattern is at most 256 characters, and must compile`() {
        PatternGuard.compile("a".repeat(PatternGuard.MAX_PATTERN_LENGTH)).shouldBeInstanceOf<PatternGuard.Compiled.Safe>()
        (PatternGuard.compile("a".repeat(PatternGuard.MAX_PATTERN_LENGTH + 1)) as PatternGuard.Compiled.Refused).reason shouldBe "too_long"
        (PatternGuard.compile("(") as PatternGuard.Compiled.Refused).reason shouldBe "syntax"
        (PatternGuard.compile("a(?=b)") as PatternGuard.Compiled.Refused).reason shouldBe "unsafe_construct"
    }

    /**
     * The adversarial case the scan cannot see: `(.*a){12}` — a counted repetition of a group
     * holding `.*` — has no refused construct and backtracks polynomially (n^12) on `aaaa…!`. The
     * budget ends it within its reads.
     *
     * Measured on the pinned JDK 21 (2026-09-26, lane 194a): the textbook `(a+)+b` is NOT
     * adversarial here — `java.util.regex` memoises that loop, 441 reads for 20 `a`s — and neither
     * are `(a|aa)+b`, `(a*)*b` or `(\w+\s?)+$`; `(.*a){12}` needs 5.1 million reads for the same
     * input. So the test is non-vacuous by construction, not by folklore: the same match run
     * unbudgeted is measured and must need at least ten times the budget, or it proves nothing.
     * Reads, not time, are the assertion (MISTAKES: never synchronise on time); the elapsed time
     * is printed as evidence, and the preemptive timeout only stops a broken budget from hanging
     * the suite.
     */
    @Test
    fun `a nested-quantifier pattern on adversarial input is refused within the read budget`() {
        val pattern = "(.*a){12}"
        val adversarial = "a".repeat(ADVERSARIAL_RUN) + "!"
        val unbudgeted = ReadBudgetCharSequence(adversarial, Long.MAX_VALUE)
        Pattern.compile(pattern).matcher(unbudgeted).matches() shouldBe false
        val budget = ParameterValueLimits.DEFAULT_MAX_REGEX_READS
        withClue("the adversarial input must need far more reads than the budget, or the test is vacuous") {
            unbudgeted.reads shouldBeGreaterThan budget * VACUITY_FACTOR
        }

        val validator = ParameterValueValidator()
        val declaration = ParameterDeclaration(LogicalType.STRING, constraints = ParameterConstraints(pattern = pattern))
        val started = System.nanoTime()
        val outcome =
            assertTimeoutPreemptively(Duration.ofSeconds(PREEMPTIVE_SECONDS)) { validator.validate(declaration, TextNode(adversarial)) }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        println(
            "regex budget: pattern=$pattern input=a×$ADVERSARIAL_RUN+'!' " +
                "unbudgeted reads=${unbudgeted.reads} budget=$budget elapsed=${elapsedMs}ms",
        )

        val refusal = outcome.shouldBeInstanceOf<ParameterValueOutcome.Refused>().refusal
        refusal.rule shouldBe ParameterValueRule.CONSTRAINT_VIOLATION
        refusal.reason shouldBe "pattern_budget"
    }

    @Test
    fun `the budget is the bound - exactly enough reads accepts, one fewer refuses`() {
        val pattern = "[a-z]+[0-9]{2}"
        val text = "abc12"
        val counting = ReadBudgetCharSequence(text, Long.MAX_VALUE)
        Pattern.compile(pattern).matcher(counting).matches() shouldBe true
        val needed = counting.reads
        val declaration = ParameterDeclaration(LogicalType.STRING, constraints = ParameterConstraints(pattern = pattern))

        ParameterValueValidator(ParameterValueLimits(maxRegexReads = needed))
            .validate(declaration, TextNode(text))
            .shouldBeInstanceOf<ParameterValueOutcome.Accepted>()
        val short = ParameterValueValidator(ParameterValueLimits(maxRegexReads = needed - 1)).validate(declaration, TextNode(text))
        short.shouldBeInstanceOf<ParameterValueOutcome.Refused>().refusal.reason shouldBe "pattern_budget"
    }

    @Test
    fun `an alternation inside a repetition that outruns the stack before the budget is refused as the budget - never an Error`() {
        // `(a|b)*` recurses once per iteration in java.util.regex: on a 512 KiB stack it overflows after a
        // few thousand characters, thousands of reads before the 100,000-read budget (the 194a security
        // pass, finding 1). The guard must report it as the budget refusal; an Error escaping the matcher
        // is a 500 on every surface and a schedule run retried forever.
        val declaration = ParameterDeclaration(LogicalType.STRING, constraints = ParameterConstraints(pattern = "(a|b)*"))
        val outcome = onSmallStack { ParameterValueValidator().validate(declaration, TextNode("a".repeat(STACK_RUN))) }
        val refusal = outcome.shouldBeInstanceOf<ParameterValueOutcome.Refused>().refusal
        refusal.rule shouldBe ParameterValueRule.CONSTRAINT_VIOLATION
        refusal.reason shouldBe "pattern_budget"
    }

    @Test
    fun `a Unicode property class is read as the parser reads it - not as a possessive quantifier`() {
        listOf("\\p{L}+", "\\P{Alpha}+", "[\\p{L}\\p{N}]+", "\\p{IsLatin}{2,}").forEach { pattern ->
            withClue(pattern) { PatternGuard.unsafeConstruct(pattern).shouldBeNull() }
        }
    }

    /** Runs [block] on a thread with a small stack; an Error that escaped the guard fails the test here. */
    private fun <T> onSmallStack(block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread(null, { result = runCatching(block) }, "pattern-guard-small-stack", SMALL_STACK_BYTES)
        thread.start()
        thread.join()
        return checkNotNull(result) { "the small-stack thread produced no result" }.getOrThrow()
    }

    private companion object {
        /** Enough characters to overflow a 512 KiB stack under `(a|b)*` (measured: ~3,500 reads suffice). */
        const val STACK_RUN = 10_000
        const val SMALL_STACK_BYTES = 512L * 1024

        /** Long enough that the unbudgeted match needs millions of reads (5.1M), short enough to finish in tens of ms. */
        const val ADVERSARIAL_RUN = 20
        const val VACUITY_FACTOR = 10L
        const val PREEMPTIVE_SECONDS = 30L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
