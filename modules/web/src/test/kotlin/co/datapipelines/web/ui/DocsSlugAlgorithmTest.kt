package co.datapipelines.web.ui

import co.datapipelines.web.TestRepoFiles
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 033 §A3 — the rendered heading ids and scripts/docs-audit.sh's anchor checker MUST agree:
 * an anchor the audit calls live must resolve in-product. The audit's `gh_slug` is
 * re-implemented here verbatim (the Python, translated line for line) and both
 * implementations are run against the SAME inputs — every heading of every packaged doc,
 * plus the punctuation-heavy cases the dropping rules exist for.
 */
class DocsSlugAlgorithmTest {
    /** scripts/docs-audit.sh's gh_slug, translated line for line — do not "improve" either side. */
    private fun auditSlug(heading: String): String {
        val h = heading.trim().lowercase().replace("`", "")
        val out = StringBuilder()
        for (ch in h) {
            if (ch.isLetterOrDigit() || ch in "_- ") out.append(ch)
            // other chars (., :, (, ), ?, ↔, —, /, |, ⊂ …) are dropped
        }
        return out.toString().replace(" ", "-")
    }

    @Test
    fun `the renderer slug and the audit slug agree on every heading of every packaged doc`() {
        val headings =
            PathMatchingResourcePatternResolver(javaClass.classLoader)
                .getResources("classpath*:docs/*.md")
                .filter { it.filename != null }
                .onEach { TestRepoFiles.requireInSources(it.filename!!, "docs") }
                .flatMap { res ->
                    HEADING
                        .findAll(res.inputStream.readBytes().decodeToString())
                        .map { it.groupValues[1] }
                        .toList()
                }
        headings.shouldNotBeEmpty()

        val disagreements =
            headings
                .map { it to DocsCatalog.githubSlug(it) }
                .filter { (heading, rendered) -> rendered != auditSlug(heading) }
                .map { (heading, rendered) -> "'$heading' → renderer '$rendered' vs audit '${auditSlug(heading)}'" }
        disagreements shouldBe emptyList()
    }

    @Test
    fun `the tricky cases - punctuation dropped, double dashes kept, code spans stripped`() {
        // Real headings with real anchors linked across the doc set.
        DocsCatalog.githubSlug("7.6 Scope & operation matrix (authoritative)") shouldBe
            "76-scope--operation-matrix-authoritative"
        DocsCatalog.githubSlug("6.2.3 `pipelines_execute`") shouldBe "623-pipelines_execute"
        DocsCatalog.githubSlug("4.2 Error envelope") shouldBe "42-error-envelope"
    }

    @Test
    fun `duplicate headings take GitHub's numeric suffix, matching the audit`() {
        // No packaged doc currently HAS a duplicate heading — exercise the rule directly
        // rather than leaving it covered only by the audit's Python.
        val seen = mutableMapOf<String, Int>()
        DocsCatalog.uniqueHeadingId("overview", seen) shouldBe "overview"
        DocsCatalog.uniqueHeadingId("overview", seen) shouldBe "overview-1"
        DocsCatalog.uniqueHeadingId("overview", seen) shouldBe "overview-2"
        DocsCatalog.uniqueHeadingId("other", seen) shouldBe "other"
    }

    private companion object {
        val HEADING = Regex("^#{1,6}\\s+(.+?)\\s*$", RegexOption.MULTILINE)
    }
}
