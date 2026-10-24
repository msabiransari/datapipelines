package co.datapipelines.visualization

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * pipeline-contract.md **§13.22** versus [VisualizationErrorCodes] — parsed out of the document by heading,
 * the assertions driven from the parsed values, so a code documented and not declared (or declared and not
 * documented) is red in either direction.
 */
class VisualizationErrorCodesSpecDriftTest {
    private val documented: Map<String, String> = CatalogSections.parse("### 13.22 Visualizations")

    @Test
    fun `the parse found the section - every sub-family has a row`() {
        documented.size shouldBeGreaterThan 20
        listOf("validation.", "version.", "release.", "import.", "authoring.", "test.", "not_found").forEach { part ->
            withClue("§13.22 has no row for 'visualization.$part'") {
                documented.keys.any { it.startsWith("visualization.$part") } shouldBe true
            }
        }
    }

    @Test
    fun `every code in §13-22 has a constant, and every constant is a row of §13-22`() {
        withClue("documented in §13.22, no VisualizationErrorCodes constant") {
            (documented.keys - VisualizationErrorCodes.ALL).sorted().shouldBeEmpty()
        }
        withClue("VisualizationErrorCodes constants §13.22 does not define") {
            (VisualizationErrorCodes.ALL - documented.keys).sorted().shouldBeEmpty()
        }
    }

    @Test
    fun `the documented statuses are the family's`() {
        documented
            .filterKeys { it.startsWith("visualization.validation.") && it != VisualizationErrorCodes.NAME_TAKEN }
            .filterValues { it != "400" }
            .keys
            .shouldBeEmpty()
        documented
            .filterKeys { it.startsWith("visualization.version.") || it.startsWith("visualization.release.") }
            .filterKeys { it !in RELEASE_PLAN_NON_CONFLICT }
            .filterValues { it != "409" }
            .keys
            .shouldBeEmpty()
        // The release-dependency plan's three non-409 codes (release-dependencies record §14, #517).
        documented[VisualizationErrorCodes.RELEASE_PLAN_REQUIRED] shouldBe "428"
        documented[VisualizationErrorCodes.RELEASE_PLAN_INVALID] shouldBe "400"
        documented[VisualizationErrorCodes.RELEASE_DEPENDENCY_LIMIT] shouldBe "422"
        documented[VisualizationErrorCodes.NAME_TAKEN] shouldBe "409"
        documented[VisualizationErrorCodes.NOT_FOUND] shouldBe "404"
        documented[VisualizationErrorCodes.IMPORT_ID_TAKEN] shouldBe "409"
        documented[VisualizationErrorCodes.IMPORT_MISSING_TEMPLATE] shouldBe "400"
        documented[VisualizationErrorCodes.AUTHORING_DISABLED] shouldBe "403"
        documented[VisualizationErrorCodes.TEST_SESSION_NOT_FOUND] shouldBe "404"
        documented[VisualizationErrorCodes.TEST_SESSION_EXPIRED] shouldBe "410"
        documented[VisualizationErrorCodes.TEST_SCREENSHOT_TOO_LARGE] shouldBe "413"
        documented[VisualizationErrorCodes.TEST_SCREENSHOT_INVALID] shouldBe "400"
    }

    private companion object {
        /** The release-plan refusals whose status is not the family's 409 (record §14): 428, 400, 422. */
        val RELEASE_PLAN_NON_CONFLICT =
            setOf(
                VisualizationErrorCodes.RELEASE_PLAN_REQUIRED,
                VisualizationErrorCodes.RELEASE_PLAN_INVALID,
                VisualizationErrorCodes.RELEASE_DEPENDENCY_LIMIT,
            )
    }
}
