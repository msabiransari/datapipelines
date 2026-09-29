package co.datapipelines.typesystem

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #297 acceptance plant — scratch branch ONLY, never merged. One failure whose message carries
 * `<WORD>` tokens (the summary must render both), and one passing test that leaves a TRUNCATED
 * result file where the summary step globs, standing in for a test JVM killed mid-write.
 */
class CiAcceptancePlantTest {
    @Test
    fun `the summary renders both tokens of this message`() {
        "UNBOUNDED" shouldBe "REFUSED"
    }

    @Test
    fun `a truncated result file sits beside the real ones`() {
        val dir = File("build/test-results/planted").also { it.mkdirs() }
        File(dir, "TEST-co.datapipelines.typesystem.Truncated.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<testsuite name=\"co.datapipelines.typesystem.Truncated\" tests=\"40\">\n" +
                "  <testcase name=\"one()\" classname=\"co.datapipelines.typesyst",
        )
    }
}
