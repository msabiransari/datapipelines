package co.datapipelines.mcp

import co.datapipelines.calculators.CalculatorRegistry
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The skill's core rules are METHOD, never data (120, owner ruling R1).
 *
 * 2026-09-12, audit-log verified: an agent asked "which … last quarter?" built the quarter
 * CONTAINING its anchor date, because rule 13's own wording described a containing-period shape
 * and named a `quarter_bounds`-style function that does not exist — the prescriptive sentence
 * overrode the catalog the agent had already listed. The ruling: the skill never enumerates
 * interpretations of a phrase and never names kinds; it teaches the agent to look the phrase up
 * in `calculators_list`, whose kinds carry the phrases they answer, and to say which reading it
 * chose. This test holds rule 13 to that, plus the three other clauses the same round added
 * (learn-first made checkable, measured numbers, assumptions recorded as `asserted`).
 *
 * What it deliberately does NOT assert: any meaning of "last", "this", "trailing" or any other
 * phrase. R1 applies to the test as much as to the text — a test that pins an interpretation
 * would be the same defect wearing a guard's clothes. Falsified by reverting each clause with a
 * reversible edit: every assertion here went red, and green again on restore.
 */
class SkillCoreRulesTest {
    private val lines = SpecFiles.read(SpecFiles.SKILL_PATH).lines()

    @Test
    fun `rule 13 resolves relative phrases through calculators_list and names no kind`() {
        val block = ruleThirteen()
        block.size shouldBeGreaterThan 1

        // The tool is the lookup path; it must be named.
        val toolLine = block.firstOrNull { it.second.contains("calculators_list") }
        withClue("rule 13 does not name `calculators_list` — the phrase lookup path") {
            (toolLine != null) shouldBe true
        }

        // …and not one kind: the catalog is the only list of kinds. The registry's names are
        // joined by the literal the old text invented, so neither a real kind nor a hallucinated
        // one can return wearing a "style" suffix.
        val offenders =
            (CalculatorRegistry.NAMES + QUARTER_BOUNDS).mapNotNull { name ->
                val found = block.firstOrNull { wordBounded(it.second, name) }
                found?.let { "SKILL.md:${it.first}: kind name `$name` in rule 13" }
            }
        withClue("rule 13 names calculator kinds — the skill teaches the lookup, never the list") {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `the core says to name the window the same way everywhere`() {
        assertClause("name the window the same way")
    }

    @Test
    fun `learn-first is checkable - the agent must state which calls it made`() {
        assertClause("a table you may not read")
    }

    @Test
    fun `a number the agent did not measure is not a number`() {
        assertClause("is not a number")
    }

    @Test
    fun `an assumption is recorded WITHOUT evidence so it lands as asserted`() {
        assertClause("lands as `asserted`")
    }

    /** Finds [clause] somewhere in SKILL.md and fails naming the line when absent. */
    private fun assertClause(clause: String) {
        val found = lines.indexOfFirst { it.contains(clause) }
        withClue("SKILL.md lost the clause \"$clause\" — it was at the heart of a 120 rule") {
            (found >= 0) shouldBe true
        }
    }

    /** Rule 13's lines as (1-based line number, text), from its `13. ` header to the next rule. */
    private fun ruleThirteen(): List<Pair<Int, String>> {
        val start = lines.indexOfFirst { it.startsWith("13. ") }
        require(start >= 0) { "SKILL.md has no line starting with '13. ' — the best-practices numbering moved" }
        val end =
            lines
                .withIndex()
                .drop(start + 1)
                .firstOrNull { RULE_START.matches(it.value) }
                ?.index
                ?: lines.size
        return lines.withIndex().drop(start).take(end - start).map { (i, line) -> i + 1 to line }
    }

    /** Whole-word for identifier-shaped names (`prior_period` inside `prior_periods` must not match). */
    private fun wordBounded(
        line: String,
        name: String,
    ): Boolean = Regex("(?<![a-z0-9_])" + Regex.escape(name) + "(?![a-z0-9_])").containsMatchIn(line)

    private companion object {
        /** The kind the pre-120 rule 13 invented — asserted absent alongside the real ones. */
        const val QUARTER_BOUNDS = "quarter_bounds"

        /** A best-practices rule header: `13. `, `13½. `, `14. ` — numbered and half-numbered forms. */
        val RULE_START = Regex("^[0-9]+[½0-9]*\\. ")
    }
}
