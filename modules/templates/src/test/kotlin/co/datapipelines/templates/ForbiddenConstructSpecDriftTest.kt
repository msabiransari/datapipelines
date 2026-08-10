package co.datapipelines.templates

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test

/**
 * Drift guard for the **forbidden-construct list itself** — templates.md §4.2, parsed out of the
 * document rather than restated here.
 *
 * `ForbiddenConstructScannerTest` proves the scanner is hard to *evade*; this proves it is
 * *complete*. The distinction matters because the failure mode is silent in opposite directions:
 * a hand-maintained payload list stays green forever while §4.2 grows a construct nobody
 * implemented. That is exactly what happened with `?interpret` — the spec gained it at v1.3 after
 * it was found missing, and a hardcoded list would not have noticed.
 *
 * So the spec drives the assertions: every construct §4.2's **Forbidden** section names must have
 * an attack payload here, and every payload must be rejected. Adding a construct to §4.2 fails
 * this test until someone supplies a payload for it and the scanner rejects that payload.
 */
class ForbiddenConstructSpecDriftTest {
    private val forbidden: Set<String> = parseForbiddenConstructs()

    @Test
    fun `the section 4-2 forbidden list parses to a plausible set`() {
        // Guards the guard: a reformat that parsed to nothing would make the sweep below vacuous.
        withClue("§4.2 forbidden list parsed to $forbidden") {
            forbidden.size shouldBeGreaterThanOrEqual MINIMUM_FORBIDDEN_CONSTRUCTS
        }
    }

    @Test
    fun `every construct the spec forbids has an attack payload and is rejected`() {
        val unattacked = forbidden.filterNot { PAYLOADS.containsKey(it) }
        withClue(
            "templates.md §4.2 forbids these, but this test carries no payload for them — " +
                "add one (and make the scanner reject it) rather than deleting the entry",
        ) {
            unattacked.sorted().shouldBeEmpty()
        }

        forbidden.forEach { construct ->
            val payload = PAYLOADS.getValue(construct)
            withClue("§4.2 forbids '$construct' — the scanner must reject: $payload") {
                ForbiddenConstructScanner.scan(payload).isNotEmpty().shouldBeTrue()
            }
        }
    }

    private companion object {
        const val SPEC_PATH = "docs/templates.md"
        const val SECTION_START = "**Forbidden**"
        const val SECTION_END = "### 4.3"

        /**
         * §4.2 names these ten constructs as of v1.4; the floor never decreases.
         *
         * Raised from 6 when the gadget-class span was generalized (TPL-TEST-3): the old pattern
         * hardcoded `Execute` and `ObjectConstructor`, so §4.2's third gadget — `JythonRuntime` —
         * needed no payload and was never asserted. A hand-listed pattern makes the *guard* drift
         * exactly the way the guard exists to prevent.
         */
        const val MINIMUM_FORBIDDEN_CONSTRUCTS = 10

        /**
         * Constructs are cited in §4.2 as inline code spans. Only the ones naming a *construct*
         * are collected — a built-in (`?x`), a directive (`<#x>`), or a gadget **class**, matched
         * by shape (a `CamelCase` identifier, or the tail of a dotted class name) rather than by
         * name, so a gadget added to §4.2 arrives here without an edit.
         */
        val CONSTRUCT_SPAN =
            Regex("`(\\?[a-zA-Z_]+|<#(?:import|include)>|(?:[a-z0-9_.]*\\.)?([A-Z][A-Za-z0-9]+))`")

        /**
         * Class spans in §4.2 that are **not** body constructs a scanner can match, each with the
         * reason it is exempt. Audited: anything appearing in §4.2 and not listed here must have
         * a payload, so the ignore-list is where an unexamined new span shows up.
         */
        val NOT_A_BODY_CONSTRUCT =
            mapOf(
                // Excluded structurally by §4.3 (no loader of that kind is ever configured), not
                // by scanning a body for its name.
                "FileTemplateLoader" to "no such loader is configured; §4.3 structural exclusion",
                "ClassTemplateLoader" to "no such loader is configured; §4.3 structural exclusion",
                // A prose reference to Freemarker's own API surface, not a construct.
                "Configurable" to "prose reference to the Freemarker setting list",
            )

        /**
         * One attack payload per construct. Deliberately *not* derived from the scanner's own
         * regexes — a test that reuses the implementation's patterns proves only self-consistency.
         */
        val PAYLOADS =
            mapOf(
                "?eval" to "\${\"1+1\"?eval}",
                "?eval_json" to "\${payload?eval_json}",
                "?interpret" to "<@\"\${payload}\"?interpret />",
                "?api" to "\${obj?api.getClass()}",
                "?new" to "\${\"java.lang.String\"?new(\"x\")}",
                "?evalJson" to "\${payload?evalJson}",
                "Execute" to "\${\"freemarker.template.utility.Execute\"?new()(\"id\")}",
                "ObjectConstructor" to "\${\"freemarker.template.utility.ObjectConstructor\"?new()}",
                "JythonRuntime" to "\${\"freemarker.template.utility.JythonRuntime\"?new()}",
                "<#import>" to "<#import \"x.sql@1\" as x>",
                "<#include>" to "<#include \"/etc/passwd\">",
            )

        fun parseForbiddenConstructs(): Set<String> {
            val text = TemplateFixtures.repoFile(SPEC_PATH).readText()
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val end = text.indexOf(SECTION_END, start)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START'" }
            return CONSTRUCT_SPAN
                .findAll(text.substring(start, end))
                // A dotted class span (`freemarker.template.utility.JythonRuntime`) is reported
                // under its simple name, which is how a payload names it.
                .map { it.groupValues[2].ifEmpty { it.groupValues[1] } }
                .filterNot { it in NOT_A_BODY_CONSTRUCT }
                .toSet()
        }
    }
}
