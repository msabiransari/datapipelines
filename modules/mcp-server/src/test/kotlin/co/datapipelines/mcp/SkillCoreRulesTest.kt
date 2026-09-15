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

    @Test
    fun `the record is the primary act - the reply is not what the next session finds`() {
        assertClause("not your reply")
    }

    @Test
    fun `a last-N-periods phrase anchors on the day AFTER the data's last date - 125 A1`() {
        assertClause("the day AFTER the data's last date")
    }

    @Test
    fun `a description never carries claims about the agent's own process - 125 A2`() {
        assertClause("never claims about your own process")
    }

    /**
     * 129 §A.1 put the sentence in; 135 §A.1 moved it to the FRONT. Two of four acceptance
     * pipelines (2026-09-14) opened with raw `start_date`/`end_date` doors for "2024" and
     * "2023 to 2024" while the clause sat LAST in rule 13 — presence passed and did not stop
     * the miss, so this is a POSITION pin: the clause must begin inside the first
     * [RULE_OPENING_CHARS] characters of rule 13's body (the text after its bold title, lines
     * joined by one space) and before the anchor-date prose (`calculators_list`) that used to
     * open the rule.
     */
    @Test
    fun `two raw dates are an input to a template, never the door of a pipeline - 129 A1, opening since 135 A1`() {
        val body = ruleThirteenBody()
        val at = body.indexOf(DOOR_CLAUSE)
        withClue("rule 13 lost the clause \"$DOOR_CLAUSE\"") { (at >= 0) shouldBe true }
        withClue("rule 13 no longer OPENS with the period case: \"$DOOR_CLAUSE\" begins at $at, past $RULE_OPENING_CHARS") {
            (at < RULE_OPENING_CHARS) shouldBe true
        }
        val anchorProse = body.indexOf("calculators_list")
        withClue("the period case must precede the anchor-date prose that opened rule 13 before 135") {
            (anchorProse > at) shouldBe true
        }
    }

    /** 135 §A.2 — six sightings: lookups and pinned templates' tables read only through probes. */
    @Test
    fun `step 1 counts a lookup, and a pinned template's tables, as tables the SQL reads - 135 A2`() {
        assertClause("a LOOKUP is a table the SQL reads")
    }

    /** 135 §A.3 — three copies of one lookup template in one workspace by the end of the run. */
    @Test
    fun `step 2 finds the lookup template before minting it - 135 A3`() {
        assertClause("PIN what exists")
    }

    /** 135 §A.4 — a template created after the pipeline exists ran without a render, twice. */
    @Test
    fun `step 3 renders a template created mid-loop before the next execute - 135 A4`() {
        assertClause("the run is not its render")
    }

    /** 135 §A.5 — a handback named a server mechanism the agent could not see (the cause was a cache). */
    @Test
    fun `rule 13½ never names the server's mechanism - 135 A5`() {
        assertClause("never name the server's mechanism")
    }

    /**
     * 135 §A.8 — the reuse half of 129 §A.2, stated in step 1 where the listing is read; 136
     * §A.3 names the block the server now carries (`definitions`) and closes with the verb the
     * acceptance run's agent lacked: never re-choose.
     */
    @Test
    fun `step 1 reads the listing's definitions as rules earlier pipelines chose, never re-choose - 135 A8, 136 A3`() {
        assertClause("`definitions` on the listing are rules earlier pipelines chose")
        assertClause("never re-choose")
    }

    /** 135 §C — `templates_update` without `dialect` was refused three times in one day. */
    @Test
    fun `step 2 says templates_update inherits the dialect - 135 C`() {
        assertClause("the dialect is inherited")
    }

    /** 129 §A.2 — the reuse half: a definition an earlier pipeline recorded is the one to reuse. */
    @Test
    fun `a definition an earlier pipeline recorded is the one to reuse - 129 A2`() {
        assertClause("is the one to reuse")
    }

    /** 139 §A — learn-before-you-write is now a server refusal, and step 1 says so. */
    @Test
    fun `step 1 says the server refuses a pipeline naming a table the key never read - 139 A`() {
        assertClause("naming a table you never `_get_columns`'d is refused")
        assertClause("`pipeline.validation.table_not_learned` — the refusal lists the calls")
    }

    /** 139 §B — render-before-you-run is now a server refusal, and step 5 says so. */
    @Test
    fun `step 5 says pipelines_execute refuses an unrendered draft template - 139 B`() {
        assertClause("updated after its last render")
        assertClause("`pipeline.execution.template_unrendered`) — render, then run")
    }

    /** 139 §C — the door flag is a decision; passing it to silence the refusal is the miss. */
    @Test
    fun `rule 13 says the raw-date door needs door_acknowledged and only honestly - 139 C`() {
        assertClause("refused until you pass `door_acknowledged: true`")
        assertClause("passing it to silence the refusal is the miss it exists to catch")
    }

    /**
     * 126 §A.3 — step 1's first sentence makes the listing the learn-first read: the facts
     * arrive on the call the agent already makes, so "call `datasources_get` for every
     * datasource" has nothing left to instruct.
     */
    @Test
    fun `step 1 starts with the listing - the facts ride the call the agent already makes`() {
        assertClause("`datasources_list` is your first call and your first read")
    }

    // ---- 138 §C / §E.2 — what the same-model re-run (2026-09-14/15) left.

    @Test
    fun `step 0 - confirm_new_root on the first create of EITHER kind - 138 C1`() {
        assertClause("on the FIRST create of either kind")
    }

    @Test
    fun `step 4 - every node carries a one-sentence description with its grain - 138 C5`() {
        assertClause("what it ships and at what grain")
    }

    @Test
    fun `step 4 - the description is a document in labelled sections, Verification among them - 138 E2`() {
        // The six labels, in the order the skill fixes them, all inside step 4 — a reader who
        // stops after Question and Window knows what the pipeline is, and Verification is the
        // owner's second ask in its cheap form: the probe SQL and its numbers live with the
        // pipeline, not in a transcript.
        val stepFour = golden(step = 4)
        val positions = DESCRIPTION_LABELS.map { label -> label to stepFour.indexOf(label) }
        withClue("step 4 lost a description label: ${positions.filter { it.second < 0 }.map { it.first }}") {
            positions.all { it.second >= 0 } shouldBe true
        }
        withClue("the labels are out of order: $positions") {
            positions.map { it.second } shouldBe positions.map { it.second }.sorted()
        }
        assertClause("a document, not a paragraph")
    }

    @Test
    fun `step 6 - a truncated tool result is paged, never reasoned over - 138 C6`() {
        assertClause("never over a partial view")
    }

    /** Finds [clause] somewhere in SKILL.md and fails naming the line when absent. */
    private fun assertClause(clause: String) {
        val found = lines.indexOfFirst { it.contains(clause) }
        withClue("SKILL.md lost the clause \"$clause\" — it was at the heart of a 120 rule") {
            (found >= 0) shouldBe true
        }
    }

    /** Golden-path step [step]'s text, from its `N. ` header to the next step's, joined by spaces. */
    private fun golden(step: Int): String {
        val start = lines.indexOfFirst { it.startsWith("$step. ") }
        require(start >= 0) { "SKILL.md has no golden-path line starting with '$step. '" }
        val end =
            lines
                .withIndex()
                .drop(start + 1)
                .firstOrNull { RULE_START.matches(it.value) || it.value.startsWith("## ") }
                ?.index
                ?: lines.size
        return lines.subList(start, end).joinToString(" ") { it.trim() }
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
        return lines
            .withIndex()
            .drop(start)
            .take(end - start)
            .map { (i, line) -> i + 1 to line }
    }

    /**
     * Rule 13's BODY as one string: the lines joined by a single space, with the `13. ` prefix
     * and the bold title (the rule's name, a fixed ~80 characters) removed — so the opening
     * window measures the rule's text, not its heading.
     */
    private fun ruleThirteenBody(): String {
        val joined = ruleThirteen().joinToString(" ") { it.second.trim() }
        val titleEnd = joined.indexOf("**", joined.indexOf("**") + 2)
        require(titleEnd > 0) { "rule 13 no longer opens with a bold title" }
        return joined.substring(titleEnd + 2).trimStart()
    }

    /** Whole-word for identifier-shaped names (`prior_period` inside `prior_periods` must not match). */
    private fun wordBounded(
        line: String,
        name: String,
    ): Boolean = Regex("(?<![a-z0-9_])" + Regex.escape(name) + "(?![a-z0-9_])").containsMatchIn(line)

    private companion object {
        /** The kind the pre-120 rule 13 invented — asserted absent alongside the real ones. */
        const val QUARTER_BOUNDS = "quarter_bounds"

        /** 135 §A.1 — the clause that must OPEN rule 13, and the window it must begin inside. */
        const val DOOR_CLAUSE = "never the door of a pipeline"
        const val RULE_OPENING_CHARS = 300

        /** 138 §E.2 — the description's sections, in the order step 4 fixes them. */
        val DESCRIPTION_LABELS =
            listOf("**Question**", "**Window and door**", "**Sources and grain**", "**Interpretation**", "**Verification**", "**Caveats**")

        /** A best-practices rule header: `13. `, `13½. `, `14. ` — numbered and half-numbered forms. */
        val RULE_START = Regex("^[0-9]+[½0-9]*\\. ")
    }
}
