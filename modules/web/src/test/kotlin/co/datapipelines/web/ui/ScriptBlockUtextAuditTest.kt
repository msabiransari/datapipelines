package co.datapipelines.web.ui

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.File

/**
 * 185 — every JSON blob a template inserts into a `<script>` block with `th:utext` is
 * written through `ScriptSafeJson`, and the allowlist of `th:utext` slots is closed.
 *
 * `th:utext` is the unescaped insertion point: whatever the model carries reaches the
 * document as markup. Today that is legitimate for exactly seven slots — the two rendered
 * Markdown bodies (a spec-drift round of its own, #190) and the five JSON-LD / JSON script
 * blocks, which are safe ONLY because their writers escape. The sweep pins both halves:
 * no NEW unescaped slot can arrive silently, and the editor's two blobs cannot lose their
 * writer-side escaping without this audit going red.
 *
 * Shape follows [AlpineCloakAuditTest] (classpath sweep + non-vacuity + pinned allowlist)
 * and [InlineWidthAuditTest] (the source walk, module-relative so a lane worktree's copy
 * is never read as this tree's).
 */
class ScriptBlockUtextAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val templates: Map<String, String> =
        resolver
            .getResources("classpath*:templates/**/*.html")
            .toList()
            .filter { it.filename != null }
            .associate { it.uri.path.substringAfter("/templates/") to it.inputStream.readBytes().decodeToString() }

    /** `th:utext` occurrences per template — the insertion points the allowlist enumerates. */
    private val utextSlots: Map<String, Int> =
        templates.mapValues { (_, source) -> UTEXT.findAll(source).count() }.filterValues { it > 0 }

    @Test
    fun `the sweep covers the templates and finds the utext slots`() {
        // Non-vacuity, twice (MISTAKES.md, "Coverage is not existence"): a resolver that
        // stopped matching would audit nothing, and a regex that matched nothing would
        // report a clean allowlist on a template tree that had no slots at all.
        templates.size shouldBeGreaterThanOrEqual 30
        utextSlots.values.sum() shouldBeGreaterThanOrEqual 7
    }

    @Test
    fun `every th-utext slot is in the allowlist, at its pinned count`() {
        utextSlots.filterKeys { it !in ALLOWED } shouldBe emptyMap()
        utextSlots shouldBe ALLOWED
    }

    /**
     * The allowlist is the audit's weak point — with the counts copied from today's tree
     * it would stay green while a fifth slot was argued in by editing this file. Pinning
     * its size makes that a deliberate, reviewed act (the AlpineCloakAuditTest rule).
     */
    @Test
    fun `the allowlist is exactly the five files argued for`() {
        ALLOWED.size shouldBe 5
    }

    @Test
    fun `the editor controller writes both json blobs through ScriptSafeJson`() {
        val source = controllerSource().readText()
        // Both shapes, pinned: the pipeline blob escapes at its serialisation, the
        // lifecycle blob at its model attribute. A slot that stops matching its shape
        // fails here loudly instead of drifting back to a raw writeValueAsString.
        val pipelineBlob =
            Regex("""val pipelineJson = ScriptSafeJson\.forScriptBlock\(mapper\.writeValueAsString\(fullTree\)\)""")
        val lifecycleBlob =
            Regex("""model\.addAttribute\(\s*"lifecycleJson",\s*ScriptSafeJson\.forScriptBlock\(""")
        source shouldContainAssignment pipelineBlob
        source shouldContainAssignment lifecycleBlob
    }

    private fun controllerSource(): File =
        File("src/main/kotlin/co/datapipelines/web/ui/PipelineEditorController.kt")
            .also { require(it.isFile) { "PipelineEditorController.kt not found from ${File(".").absolutePath}" } }

    private infix fun String.shouldContainAssignment(regex: Regex) {
        require(regex.containsMatchIn(this)) {
            "the editor's JSON escaping is not wired as the audit pins it — expected /$regex/ in PipelineEditorController.kt"
        }
    }

    private companion object {
        val UTEXT = Regex("""\sth:utext=""")

        /**
         * Every `th:utext` slot, with the count each file may carry. All five writers are
         * safe TODAY: `docs/doc.html` and `docs/doc-public.html` render packaged Markdown
         * (#190 tracks their own review), and the three JSON writers go through
         * [ScriptSafeJson] — the docs' JSON-LD via `DocJsonLd`, the FAQ blocks via
         * `FaqJsonLd`, the editor's two blobs via `PipelineEditorController`.
         */
        val ALLOWED =
            mapOf(
                "docs/doc.html" to 1,
                "docs/doc-public.html" to 2,
                "pipelines/editor.html" to 2,
                "site/_layout.html" to 1,
                "site/faq.html" to 1,
            )
    }
}
