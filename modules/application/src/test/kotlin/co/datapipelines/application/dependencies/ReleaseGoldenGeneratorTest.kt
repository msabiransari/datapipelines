package co.datapipelines.application.dependencies

import co.datapipelines.pipeline.release.ReleaseGraph
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Today's release behavior, frozen BEFORE any neutral-core change (#868 section A): every [ReleaseGoldenCases] case is
 * walked through the CURRENT [ReleaseGraph] and `TreeHash`, and the rendering must equal the committed golden byte for
 * byte. A mismatch is a red test — never a regeneration: the generator writes a golden only when the file does not
 * exist AND [ReleaseGoldens.WRITE_FLAG] is `1` (used once, in the commit that introduced the files).
 *
 * The parity half (`ReleaseGoldenParityTest`) renders the same cases through the neutral walk behind the release adapter
 * and compares against these same files.
 */
class ReleaseGoldenGeneratorTest {
    private val writeMissing = System.getenv(ReleaseGoldens.WRITE_FLAG) == "1"

    @Test
    fun `every case walked by the current release graph equals its committed golden`() {
        val cases = ReleaseGoldenCases.ALL.map { it() }
        cases.size shouldBe EXPECTED_CASES // non-vacuity: the table is the one the brief enumerates
        cases.map { it.name }.toSet().size shouldBe cases.size
        cases.forEach { case ->
            val rendered = ReleaseGoldens.render(case) { root -> ReleaseGraph(case.sources, case.bounds).walk(root) }
            val file = ReleaseGoldens.file(case.name)
            if (!file.exists() && writeMissing) file.also { it.parentFile.mkdirs() }.writeText(rendered)
            withClue("golden ${file.name} is missing; set ${ReleaseGoldens.WRITE_FLAG}=1 once to create it") { file.exists() shouldBe true }
            withClue("golden ${file.name} differs from today's release walk") { rendered shouldBe file.readText() }
        }
    }

    @Test
    fun `the golden directory holds exactly one file per case, none stale`() {
        val dir = File(ReleaseGoldens.file("x").parentFile.path)
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension }
        files.size shouldBeGreaterThan 0
        files shouldContainExactlyInAnyOrder ReleaseGoldenCases.ALL.map { it().name }
    }

    @Test
    fun `the refusal and limit goldens carry the expected outcomes`() {
        val outcomes =
            ReleaseGoldenCases.ALL.map { it() }.associate { case ->
                case.name to ReleaseGoldens.file(case.name).readText()
            }
        listOf("self-cycle", "three-node-cycle").forEach { outcomes.getValue(it) shouldContainCode "pipeline.release.dependency_cycle" }
        listOf("depth-over-limit", "versions-over-limit", "edges-over-limit", "long-path-into-memoised-subtree-over-depth").forEach {
            outcomes.getValue(it) shouldContainCode "pipeline.release.dependency_limit"
        }
        outcomes.getValue("wire-over-limit") shouldContainCode "pipeline.release.dependency_limit"
        outcomes.getValue("unresolved-root").contains("\"outcome\" : \"unresolved_root\"") shouldBe true
        listOf("depth-at-limit", "versions-at-limit", "edges-at-limit", "wire-at-limit").forEach {
            outcomes.getValue(it).contains("\"digest\" : \"v1:") shouldBe true
        }
    }

    private infix fun String.shouldContainCode(code: String) {
        withClue("expected $code") { contains("\"code\" : \"$code\"") shouldBe true }
    }

    private companion object {
        const val EXPECTED_CASES = 18
    }
}
