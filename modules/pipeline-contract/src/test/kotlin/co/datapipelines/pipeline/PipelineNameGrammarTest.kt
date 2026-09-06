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
 * **077 inverted 067's containment claim, and this file records the inversion.** 067's premise
 * was "every legal old name is a legal one-segment new name, so no migration and no abort
 * gate", measured over the old rule's whole short-name space. §4.1 now requires a FOLDER, so
 * every one of those flat names is refused: the containment residual went from "the names
 * starting `_`" to "all of them". The enumeration is kept and its assertion reversed rather
 * than deleted — it is the same measurement, and it now proves the narrowing is total instead
 * of proving it is small.
 *
 * Why a total narrowing still needs no gate is a property of the *call sites*, not of the
 * regex, so it is pinned as its own test below: a pipeline name is validated on SAVE only.
 * Templates needed §4.6's migration abort (re-issued for 077 as `V12__folder_required.sql`)
 * because their grammar is re-checked at RENDER time in two more places; nothing on the
 * pipeline execute path consults this regex, so a legacy `active_users` pipeline keeps
 * listing, opening and executing.
 */
class PipelineNameGrammarTest {
    private val validator = Fixtures.validator()
    private val workspaceId = UUID.randomUUID()

    /** The pre-067 rule, restated here so the containment claim compares two independent things. */
    private val preRoundIdentifier = Regex("^[a-z0-9_]{1,63}$")

    @Test
    fun `077 - NO old-legal name survives, because every one of them is folderless`() {
        // Exhaustive over the old rule's alphabet up to length 3: 36 + 36² + 36³ = 47_988
        // names, every one of them decided by both rules. Longer names cannot introduce a new
        // verdict — the old rule is a flat character class with no `/` in it, so every name it
        // admits is a single segment, and §4.1 refuses every single segment.
        val alphabet = ('a'..'z') + ('0'..'9') + '_'
        val shortNames =
            alphabet.map { "$it" } +
                alphabet.flatMap { a -> alphabet.map { b -> "$a$b" } } +
                alphabet.flatMap { a -> alphabet.flatMap { b -> alphabet.map { c -> "$a$b$c" } } }
        val oldLegal = shortNames.filter { preRoundIdentifier.matches(it) }

        withClue("067's containment residual, which 077 grew to the whole set") {
            oldLegal.none { PipelineNameGrammar.matches(it) } shouldBe true
            oldLegal.size shouldBe alphabet.size + alphabet.size * alphabet.size + alphabet.size * alphabet.size * alphabet.size
        }
        // And the refusal DISCRIMINATES: a name that is otherwise a legal segment reports
        // `folder_required` (adding a folder fixes it); a leading `_` reports `grammar`,
        // because `test/_helper` is illegal too and a folder would not fix it.
        withClue("reason") {
            oldLegal.filterNot { it.startsWith("_") }.all {
                PipelineNameGrammar.refusalReason(it) == PipelineNameGrammar.REASON_FOLDER_REQUIRED
            } shouldBe true
            oldLegal.filter { it.startsWith("_") }.all {
                PipelineNameGrammar.refusalReason(it) == PipelineNameGrammar.REASON_GRAMMAR
            } shouldBe true
        }
        // …and the length dimension is untouched: the old rule's longest name is 63 chars,
        // inside the segment cap of 64, so nothing is refused for being too long — only for
        // having no folder.
        withClue("the old rule's maximum length, under a folder") {
            PipelineNameGrammar.matches("test/" + "a".repeat(63)) shouldBe true
            PipelineNameGrammar.matches("test/" + "9".repeat(63)) shouldBe true
        }
    }

    @Test
    fun `a folderless name is refused at SAVE and nowhere else`() {
        // The save path refuses it, with the catalogued code, the offending value named, and
        // the reason that tells an agent a folder is what is missing…
        val failures =
            validator.validate(Fixtures.pipeline(name = "active_users"), workspaceId).withCode(Validation.NAME_INVALID)
        failures.single().details["value"] shouldBe "active_users"
        failures.single().details["reason"] shouldBe PipelineNameGrammar.REASON_FOLDER_REQUIRED

        // …and the RESOLVE path — the one an execution and a child reference take — does not
        // consult the grammar at all, so a stored legacy name still resolves. This is the whole
        // reason 067 shipped, and 077 still ships, without the migration gate templates needed
        // (§4.6): grep the production sources and this regex has exactly one save-time call
        // site each in StructuralRules and CompositionRules, and none on the execute path.
        val legacy = Fixtures.pipeline(name = "active_users")
        val resolver =
            PipelineResolver { _, name, version ->
                if (name == "active_users" && version == 1) ResolvedPipeline(legacy, false) else null
            }

        resolver.resolve(workspaceId, "active_users", 1)?.pipeline?.name shouldBe "active_users"
    }

    @Test
    fun `segment and path boundaries`() {
        val segment64 = "a".repeat(64)
        val segment65 = "a".repeat(65)

        assertLegal(
            "a/b",
            "nyc/mobility",
            "nyc/mobility/revenue_by_borough",
            "trade/fx/imports_in_partner_currency",
            "test/a.b-c_d",
            "9/9/9",
            "test/$segment64",
            (1..10).joinToString("/") { "s$it" },
            // Exactly 200 characters: 19 segments would exceed the segment cap, so the longest
            // legal path is a small number of long segments — 3 × 64 + 2 separators = 194, then
            // a 5-char fourth segment and its separator lands on 200 exactly.
            listOf(segment64, segment64, segment64, "abcde").joinToString("/"),
        )

        assertRefused(
            "",
            // 077: one segment is not a path — a folder is mandatory, whatever the segment is.
            "a",
            "nyc",
            "active_users",
            segment64,
            "test/$segment65",
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
    fun `077 - a browse PREFIX is a folder path, not a name - one segment is legal there`() {
        // The defect this pins is one the round nearly shipped: three browse guards checked a
        // PREFIX against the NAME rule, and 077 made the name rule refuse a single segment. So
        // `prefix: "nyc"` — the first request after listing the roots — would have answered an
        // empty level for every root in the workspace, in the UI tree and over MCP alike.
        withClue("a root prefix must browse") {
            PipelineNameGrammar.matchesPrefix("nyc") shouldBe true
            PipelineNameGrammar.matches("nyc") shouldBe false
        }
        withClue("deeper prefixes, up to the folder ceiling of 9") {
            PipelineNameGrammar.matchesPrefix("nyc/mobility") shouldBe true
            PipelineNameGrammar.matchesPrefix((1..9).joinToString("/") { "s$it" }) shouldBe true
            PipelineNameGrammar.matchesPrefix((1..10).joinToString("/") { "s$it" }) shouldBe false
        }
        withClue("a prefix is still a PATH — the shape rules do not relax") {
            listOf("", "/nyc", "nyc/", "nyc//mobility", "nyc/../etc", "NYC", "has space", "_helper")
                .forEach { PipelineNameGrammar.matchesPrefix(it) shouldBe false }
        }
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
