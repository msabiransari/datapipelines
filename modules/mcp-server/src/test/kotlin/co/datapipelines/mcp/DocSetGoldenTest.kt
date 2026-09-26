package co.datapipelines.mcp

import co.datapipelines.mcp.docs.DocContext
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Golden test for the NARRATIVE documents (the 242a record §5, second row): rendered under a
 * fixed [DocContext], each equals a checked-in expectation. A deliberate placeholder change —
 * a new [DocContext] field, a renamed key, an edited default — turns this red with a printed
 * diff, and the same flag that regenerates refuses to pass silently.
 *
 * Re/regenerate with the diff printed (an env var, so no build plumbing needs to carry it):
 * ```
 * DOCS_GOLDEN_REGENERATE=true ./gradlew :modules:mcp-server:test --tests 'co.datapipelines.mcp.DocSetGoldenTest'
 * ```
 * That run WRITES the expectations, prints the diff, and FAILS — re-run without the flag to
 * see green. A regenerate that passed would let a placeholder change ship unread.
 *
 * Only the narrative resources are golden. The generated documents (tools references, error
 * codes, calculators) are held to their catalogs by [DocSetStructureTest], which is the
 * stronger claim: a golden file would pin their prose while the catalogs move.
 */
class DocSetGoldenTest {
    private val goldenDir = File(SpecFiles.root, "modules/mcp-server/src/test/resources/docs-golden")

    @Test
    fun `every narrative document renders exactly its checked-in expectation`() {
        val docs = renderedNarrative()
        docs.size shouldBeGreaterThan 5 // non-vacuity: the render saw the real resources
        val regenerate = System.getenv("DOCS_GOLDEN_REGENERATE") == "true"
        for ((name, markdown) in docs) {
            val expectation = File(goldenDir, "$name.md")
            if (regenerate) {
                if (expectation.exists()) {
                    printDiff(name, expectation.readText(), markdown)
                }
                expectation.parentFile.mkdirs()
                expectation.writeText(markdown)
                continue
            }
            withClue("no golden expectation for $name — run once with DOCS_GOLDEN_REGENERATE=true") {
                expectation.exists() shouldBe true
            }
            val expected = expectation.readText()
            withClue("rendered $name drifted from the expectation (diff above only on regenerate)") {
                markdown shouldBe expected
            }
        }
        if (regenerate) {
            throw AssertionError("expectations regenerated under DOCS_GOLDEN_REGENERATE=true — re-run without the flag")
        }
    }

    /** The rendered narrative documents, in stable name order. */
    private fun renderedNarrative(): List<Pair<String, String>> {
        val docSet = DocSetTestSupport.renderedDocSet()
        val resourceNames =
            File(SpecFiles.root, SpecFiles.SKILL_RESOURCES)
                .listFiles { file -> file.isFile && file.extension == "md" }
                .orEmpty()
                .map { it.nameWithoutExtension }
                .sorted()
        return resourceNames.map { name -> name to docSet.get(name).markdown }
    }

    private fun printDiff(
        name: String,
        expected: String,
        actual: String,
    ) {
        println("=== golden diff: $name ===")
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        var i = 0
        while (i < maxOf(expectedLines.size, actualLines.size)) {
            val e = expectedLines.getOrNull(i)
            val a = actualLines.getOrNull(i)
            if (e != a) {
                println("- $e")
                println("+ $a")
            }
            i++
        }
    }
}
