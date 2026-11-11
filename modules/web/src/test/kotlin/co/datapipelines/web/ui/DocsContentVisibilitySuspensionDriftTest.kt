package co.datapipelines.web.ui

import co.datapipelines.web.TestRepoFiles
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #713 owner ruling, 2026-10-25 — `scripts/site-lighthouse.sh` measures the docs pages'
 * accessibility on a render where every `content-visibility` rule in `docs.css` is
 * suspended (`/__cv-off/` injection), because Lighthouse 12.8.2's axe-based contrast and
 * target-size audits mis-read `content-visibility`-skipped subtrees (stale coordinates
 * pair dark ink with an unpainted band colour; real rendered contrast is ~13:1; measured
 * in notes/evidence/713-docs-pages-lighthouse-floor/a11y-contrast-artifact.md).
 *
 * That suspension is only honest while the guard's selector list mirrors the sheet: a
 * `content-visibility: auto` (skip) rule added to `docs.css` without a matching entry in
 * `CV_SUSPEND_SELECTORS` would ship skip-rendering the guard never audits. This drift
 * test fails on exactly that — every selector the sheet gives a SKIP value must appear
 * in the guard's suspension list. A `content-visibility: visible` rule does the
 * opposite of skipping (the sheet's `.doc-part.is-rendered` fragment-landing rule,
 * applied by the site chrome's docs-anchors block) and cannot hide anything from the
 * audit, so it is deliberately out of scope.
 */
class DocsContentVisibilitySuspensionDriftTest {
    private val css: String = javaClass.classLoader.getResource("static/css/docs.css")!!.readText()
    private val guard: String = TestRepoFiles.read("scripts/site-lighthouse.sh")

    /** Selectors of SKIP rules — the ones whose skipping the audit cannot see through. */
    private val cssSkipSelectors: Set<String> =
        css
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .let { stripped ->
                Regex("""([^{}]+)\{[^{}]*content-visibility\s*:\s*(auto|hidden)""")
                    .findAll(stripped)
                    .map { it.groupValues[1].trim() }
                    .toSet()
            }

    /** All cv rules regardless of value — the non-vacuity floor counts these. */
    private val cssCvRuleCount: Int =
        Regex("""[^{}]+\{[^{}]*content-visibility\s*:""")
            .findAll(css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " "))
            .count()

    private val suspensionSelectors: Set<String> =
        Regex("""CV_SUSPEND_SELECTORS\s*=\s*"([^"]+)"""")
            .find(guard)!!
            .groupValues[1]
            .split(",")
            .map { it.trim() }
            .toSet()

    @Test
    fun `the sheet still carries the ladder this ruling is about`() {
        withClue(
            "docs.css content-visibility extraction found nothing — " +
                "the extraction regex or the sheet changed; re-derive before trusting this audit",
        ) {
            cssCvRuleCount shouldBe 4 // three ladder skip rules + site.js's .is-rendered rule
        }
        withClue(
            "the sheet's SKIP rules vanished — the ladder is gone but the audit claims to suspend it",
        ) {
            cssSkipSelectors shouldHaveSize 3
        }
    }

    @Test
    fun `every content-visibility skip rule in docs css is suspended by the guard's accessibility pass`() {
        withClue(
            "a content-visibility skip rule in docs.css is missing from " +
                "scripts/site-lighthouse.sh CV_SUSPEND_SELECTORS — the guard would skip-render " +
                "content its accessibility audit never sees",
        ) {
            (cssSkipSelectors - suspensionSelectors).shouldBeEmpty()
        }
    }

    @Test
    fun `the guard's injected override actually overrides`() {
        val style = Regex("""CV_SUSPEND_STYLE = \((.*?)\)\.encode\(\)""", RegexOption.DOT_MATCHES_ALL).find(guard)!!.groupValues[1]
        style.shouldContain("""{content-visibility:visible !important}</style>""")
    }
}
