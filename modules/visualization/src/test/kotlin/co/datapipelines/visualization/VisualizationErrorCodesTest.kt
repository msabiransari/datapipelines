package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The module's codes and the catalog's copies are ONE set per family (the `ParameterErrorCodes` /
 * `PipelineErrorCodes.Parameters` precedent): `web`'s `ApiErrorCatalog` reads the copy, the module raises its
 * own, and a code added or dropped on one side only is red here — name for name, not only value for value.
 */
class VisualizationErrorCodesTest {
    @Test
    fun `VisualizationErrorCodes and PipelineErrorCodes-Visualization are the same named set`() {
        CatalogSections.namedConstantsOf(PipelineErrorCodes.Visualization::class.java) shouldBe
            CatalogSections.namedConstantsOf(VisualizationErrorCodes::class.java)
        VisualizationErrorCodes.ALL.size shouldBe EXPECTED_VISUALIZATION_CODES
    }

    @Test
    fun `every code is in its family and follows the segmentation scheme - the entity's not_found alone has two segments`() {
        listOf(
            Triple("visualization.", VisualizationErrorCodes.ALL, VisualizationErrorCodes.NOT_FOUND),
            Triple("dashboard.", DashboardErrorCodes.ALL, DashboardErrorCodes.NOT_FOUND),
        ).forEach { (prefix, codes, notFound) ->
            withClue(prefix) {
                codes.filterNot { it.startsWith(prefix) }.shouldBeEmpty()
                codes.filterNot { CatalogSections.SEGMENTATION.matches(it) }.shouldBeEmpty()
                codes.filter { it.count { c -> c == '.' } == 1 }.toSet() shouldBe setOf(notFound)
            }
        }
    }

    private companion object {
        /** §13.22's rows — the spec's 21 plus the nine lifecycle rows L1a added (pipeline-contract v1.44). */
        const val EXPECTED_VISUALIZATION_CODES = 30
    }
}
