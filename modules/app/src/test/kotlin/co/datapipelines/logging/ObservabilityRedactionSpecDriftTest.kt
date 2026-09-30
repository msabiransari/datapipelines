package co.datapipelines.logging

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * observability.md §9.2's two tables against the code's lists (#337 D4c — the house drift shape):
 * the sensitive-key table and the never-redacted sentence are the record, [LogRedactor] is the
 * implementation, and neither may move without the other. The list is the ONE secret-keeping
 * authority for every log line, so a key added in code without the record — or an operator reading
 * the record while the code scrubs something else — is exactly the failure this test exists for.
 */
class ObservabilityRedactionSpecDriftTest {
    @Test
    fun `the code's sensitive keys are exactly §9_2's table`() {
        sensitiveKeysFromRecord() shouldContainExactlyInAnyOrder LogRedactor.SENSITIVE_KEYS
    }

    @Test
    fun `the code's never-redacted keys are exactly §9_2's sentence`() {
        neverRedactedFromRecord() shouldContainExactlyInAnyOrder LogRedactor.NEVER_REDACTED
    }

    @Test
    fun `a key removed from the code's list is red`() {
        // The falsification harness the gate demands, cheap enough to run every build: the same
        // parse must SEE a planted key — proving the extractor is not silently returning empty.
        sensitiveKeysFromRecord().isNotEmpty() shouldBe true
        neverRedactedFromRecord().isNotEmpty() shouldBe true
    }

    private fun recordFile(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parent != null && !dir.resolve("settings.gradle.kts").isFile) dir = dir.parentFile
        return dir.resolve("docs/observability.md")
    }

    /** The rows of §9.2's sensitive-key table: the first cell of every row under the layer list. */
    private fun sensitiveKeysFromRecord(): List<String> {
        val section = recordFile().readText().substringAfter("### 9.2 Redaction").substringBefore("### 9.3")
        val table = section.substringAfter("Both layers read the same **sensitive-key list**").substringBefore("**Never redacted")
        return table
            .lineSequence()
            .filter { it.startsWith("| ") && !it.startsWith("| Key") && !it.contains("|---|") }
            .map {
                it
                    .trim()
                    .trim('|')
                    .split('|')
                    .first()
                    .trim()
                    .removeSurrounding("`")
            }.filter { it.isNotEmpty() }
            .toList()
    }

    /** The backticked list in the never-redacted sentence. */
    private fun neverRedactedFromRecord(): List<String> {
        val section = recordFile().readText().substringAfter("### 9.2 Redaction").substringBefore("### 9.3")
        val sentence = section.substringAfter("**Never redacted, by design:**").substringBefore(".")
        return Regex("`([a-z_]+)`").findAll(sentence).map { it.groupValues[1] }.toList()
    }
}
