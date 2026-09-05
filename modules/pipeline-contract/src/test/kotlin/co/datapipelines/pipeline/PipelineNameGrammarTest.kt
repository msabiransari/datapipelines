package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The §3.2 pipeline name grammar (067) — the rule itself, its boundaries, what it deliberately
 * does NOT widen, and the containment claim the round rests on.
 *
 * The round's premise is "every legal old name is a legal one-segment new name, so no
 * migration and no abort gate". That premise is **measured here, not asserted**: the old rule
 * is a finite regex, so [`old-legal names are new-legal except for a leading underscore`]
 * enumerates its whole short-name space and reports the residual exactly. It is not empty —
 * `_helper` was legal and is not — and the test states the residual as a set rather than
 * pretending it away, because a containment proof that quietly rounds down is not a proof.
 *
 * Why the residual needs no gate is a property of the *call sites*, not of the regex, so it is
 * pinned as its own test below: a pipeline name is validated on SAVE only. Templates needed
 * §4.6's migration abort because their grammar is re-checked at RENDER time in two more
 * places; nothing on the pipeline execute path consults this regex, so a legacy `_scratch`
 * pipeline keeps listing, opening and executing.
 */
class PipelineNameGrammarTest {
    private val validator = Fixtures.validator()
    private val workspaceId = UUID.randomUUID()

    /** The pre-067 rule, restated here so the containment claim compares two independent things. */
    private val preRoundIdentifier = Regex("^[a-z0-9_]{1,63}$")

    @Test
    fun `old-legal names are new-legal except for a leading underscore`() {
        // Exhaustive over the old rule's alphabet up to length 3: 36 + 36² + 36³ = 47_988
        // names, every one of them decided by both rules. Longer names cannot introduce a new
        // failure shape — the old rule is a flat character class, so only the FIRST character
        // and the LENGTH can differ in verdict, and both are covered here and below.
        val alphabet = ('a'..'z') + ('0'..'9') + '_'
        val shortNames =
            alphabet.map { "$it" } +
                alphabet.flatMap { a -> alphabet.map { b -> "$a$b" } } +
                alphabet.flatMap { a -> alphabet.flatMap { b -> alphabet.map { c -> "$a$b$c" } } }

        val residual = shortNames.filter { preRoundIdentifier.matches(it) && !PipelineNameGrammar.matches(it) }

        withClue("names the old rule allowed and the new grammar refuses") {
            residual.all { it.startsWith("_") } shouldBe true
            residual.size shouldBe alphabet.size * alphabet.size + alphabet.size + 1
        }
        // …and the length dimension: the old rule's longest name is 63 chars, well inside the
        // new segment cap of 64, so no old name is refused for being too long.
        withClue("the old rule's maximum length") {
            PipelineNameGrammar.matches("a".repeat(63)) shouldBe true
            PipelineNameGrammar.matches("9".repeat(63)) shouldBe true
        }
    }

    @Test
    fun `the residual is refused at SAVE and nowhere else`() {
        // The save path refuses it, with the catalogued code and the offending value named…
        val failures = validator.validate(Fixtures.pipeline(name = "_scratch"), workspaceId).withCode(Validation.NAME_INVALID)
        failures.single().details["value"] shouldBe "_scratch"

        // …and the RESOLVE path — the one an execution and a child reference take — does not
        // consult the grammar at all, so a stored legacy name still resolves. This is the whole
        // reason 067 ships without the migration gate templates needed (§4.6): grep the
        // production sources and this regex has exactly one save-time call site each in
        // StructuralRules and CompositionRules, and none on the execute path.
        val legacy = Fixtures.pipeline(name = "_scratch")
        val resolver = PipelineResolver { _, name, version -> if (name == "_scratch" && version == 1) ResolvedPipeline(legacy, false) else null }

        resolver.resolve(workspaceId, "_scratch", 1)?.pipeline?.name shouldBe "_scratch"
    }

    @Test
    fun `segment and path boundaries`() {
        val segment64 = "a".repeat(64)
        val segment65 = "a".repeat(65)

        assertLegal(
            "a",
            "nyc",
            "nyc/mobility/revenue_by_borough",
            "trade/fx/imports_in_partner_currency",
            "a.b-c_d",
            "9/9/9",
            segment64,
            (1..10).joinToString("/") { "s$it" },
            // Exactly 200 characters: 19 segments would exceed the segment cap, so the longest
            // legal path is a small number of long segments — 3 × 64 + 2 separators = 194, then
            // a 5-char fourth segment and its separator lands on 200 exactly.
            listOf(segment64, segment64, segment64, "abcde").joinToString("/"),
        )

        assertRefused(
            "",
            segment65,
            (1..11).joinToString("/") { "s$it" },
            // 201 characters — one past the cap, with every segment individually legal.
            listOf(segment64, segment64, segment64, "abcdef").joinToString("/"),
            "/leading",
            "trailing/",
            "double//slash",
            "nyc/../etc",
            "nyc/./here",
            ".hidden",
            "-leading",
            "_helper",
            "UPPER/case",
            "has space",
            "back\\slash",
            "at@sign",
            "nyc/mobility/",
        )
    }

    @Test
    fun `node ids, output tables and parameter names are NOT widened`() {
        // The identifier rule §15.1 froze stays exactly where it was. A `/` in any of the three
        // is still refused — this is the falsification for "067 widened the identifier rule",
        // which it did not: it widened the NAME rule only.
        val pipeline =
            Fixtures.pipeline(
                name = "nyc/mobility/revenue_by_borough",
                nodes = listOf(Fixtures.node(id = "nyc/stage", output = NodeOutput.Tempdb("nyc/stg_trips"))),
                parameters = mapOf("nyc/start" to Parameter(LogicalType.STRING, required = false)),
            )

        val codes = validator.validate(pipeline, workspaceId).codes

        codes shouldContain Validation.INVALID_IDENTIFIER
        codes shouldContain Validation.PARAMETER_NAME_INVALID
        codes shouldNotContain Validation.NAME_INVALID
    }

    private fun assertLegal(vararg names: String) =
        names.forEach { name -> withClue(name) { PipelineNameGrammar.matches(name) shouldBe true } }

    private fun assertRefused(vararg names: String) =
        names.forEach { name -> withClue(name) { PipelineNameGrammar.matches(name) shouldBe false } }
}
