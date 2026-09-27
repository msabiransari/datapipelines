package co.datapipelines.parameters

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * pipeline-contract.md **§13.20** versus [ParameterErrorCodes] — the way
 * `PipelineErrorCodesSpecDriftTest` holds §12/§13 and `PipelineErrorCodes` together: the table is
 * parsed out of the document by heading, and the assertions are driven from the parsed values, so a
 * code documented and not declared (or declared and not documented) is red in either direction.
 */
class ParameterErrorCodesSpecDriftTest {
    private val documented: Map<String, String> = parseSection()

    @Test
    fun `the parse found the section - both halves of the family and the lifecycle rows`() {
        // Guards the guard: a heading rename or a table reformat that emptied the parse would
        // otherwise pass every comparison below vacuously.
        documented.size shouldBeGreaterThan 70
        listOf("parameter.validation.", "parameter.evaluate.", "parameter.version.", "parameter.not_found").forEach { prefix ->
            withClue("§13.20 has no row starting '$prefix'") { documented.keys.any { it.startsWith(prefix) } shouldBe true }
        }
    }

    @Test
    fun `every code in §13-20 has a constant, and every constant is a row of §13-20`() {
        withClue("documented in §13.20, no ParameterErrorCodes constant") {
            (documented.keys - ParameterErrorCodes.ALL).sorted().shouldBeEmpty()
        }
        withClue("ParameterErrorCodes constants §13.20 does not define") {
            (ParameterErrorCodes.ALL - documented.keys).sorted().shouldBeEmpty()
        }
    }

    @Test
    fun `the documented statuses are the family's - validation 400 but duplicate_name, per-parameter evaluate codes never a response`() {
        documented
            .filterKeys { it.startsWith("parameter.validation.") && it != ParameterErrorCodes.DUPLICATE_NAME }
            .filterValues { it != "400" }
            .keys
            .shouldBeEmpty()
        documented[ParameterErrorCodes.DUPLICATE_NAME] shouldBe "409"
        documented[ParameterErrorCodes.NOT_FOUND] shouldBe "404"
        documented[ParameterErrorCodes.EVALUATE_TIMEOUT] shouldBe "504"
        documented[ParameterErrorCodes.EVALUATE_RESPONSE_TOO_LARGE] shouldBe "413"
        documented[ParameterErrorCodes.EVALUATE_REQUIRED_MISSING] shouldBe "—"
        documented[ParameterErrorCodes.AUTHORING_DISABLED] shouldBe "403"
    }

    private companion object {
        const val SPEC_PATH = "docs/pipeline-contract.md"
        const val SECTION_START = "### 13.20 Parameter sets"

        /** `| \`code\` | HTTP | … |` — group 1 the code, group 2 the status cell. */
        val ROW = Regex("^\\|\\s*`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`\\s*\\|\\s*([^|]+?)\\s*\\|")

        fun parseSection(): Map<String, String> {
            val text = ParametersTestFiles.read(SPEC_PATH)
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val rest = text.substring(start + SECTION_START.length)
            val end = listOf(rest.indexOf("\n### "), rest.indexOf("\n## ")).filter { it >= 0 }.minOrNull() ?: rest.length
            return rest
                .substring(0, end)
                .lineSequence()
                .mapNotNull { ROW.find(it) }
                .associate { it.groupValues[1] to it.groupValues[2] }
        }
    }
}
