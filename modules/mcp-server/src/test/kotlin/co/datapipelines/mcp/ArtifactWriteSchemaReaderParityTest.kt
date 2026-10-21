package co.datapipelines.mcp

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.visualization.DashboardReader
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationService
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test

/**
 * Every visualization and dashboard WRITE tool advertises exactly the reader's document keys (#640).
 *
 * `ArtifactTools.document` keeps only the reader's `DOCUMENT_KEYS` from a call's arguments, and the schemas declare
 * `"additionalProperties": false`: a property the schema names that the reader does not is dropped without a word,
 * and a reader key the schema omits is one no schema-following agent can send. The script-node rename renamed the
 * visualization block's property to `script` while the reader kept `transform` (owner ruling 2026-10-20: the block's
 * key stays), so a template pin sent the way the schema said was silently lost. The schema's properties minus each
 * tool's own arguments — the 094 confirmation on a create, the id and hash precondition on an update — must equal
 * the reader's keys.
 */
class ArtifactWriteSchemaReaderParityTest {
    private val lens = mockk<PromoterLens>()

    @Test
    fun `each write tool's document properties are its reader's DOCUMENT_KEYS`() {
        val visualizations = mockk<VisualizationService>()
        val dashboards = mockk<DashboardService>()
        val visualizationReader = VisualizationReader()
        val dashboardReader = DashboardReader()
        val cases =
            listOf(
                Case(VisualizationsCreateTool(visualizations, visualizationReader, lens), CREATE_ARGS, VisualizationReader.DOCUMENT_KEYS),
                Case(VisualizationsUpdateTool(visualizations, visualizationReader), UPDATE_ARGS, VisualizationReader.DOCUMENT_KEYS),
                Case(DashboardsCreateTool(dashboards, dashboardReader, lens), CREATE_ARGS, DashboardReader.DOCUMENT_KEYS),
                Case(DashboardsUpdateTool(dashboards, dashboardReader), UPDATE_ARGS, DashboardReader.DOCUMENT_KEYS),
            )

        assertSoftly {
            cases.forEach { case ->
                val properties = case.tool.definition.properties()
                withClue("${case.tool.definition.name()}: its own arguments are declared") {
                    properties.intersect(case.toolArgs) shouldBe case.toolArgs
                }
                withClue("${case.tool.definition.name()}: document properties vs the reader's DOCUMENT_KEYS") {
                    (properties - case.toolArgs).sorted() shouldBe case.documentKeys.sorted()
                }
            }
        }
    }

    private data class Case(
        val tool: McpTool,
        val toolArgs: Set<String>,
        val documentKeys: Set<String>,
    )

    private fun McpSchema.Tool.properties(): Set<String> =
        requireNotNull(inputSchema()["properties"] as? Map<*, *>) { "${name()} declares no properties" }
            .keys
            .map { it.toString() }
            .toSet()

    private companion object {
        /** `NewRootConfirmation.require` reads it on both create tools. */
        val CREATE_ARGS = setOf(NewRootConfirmation.ARG)

        /** `args.requiredUuid("id")` and `args.requiredString("expected_hash")` on both update tools. */
        val UPDATE_ARGS = setOf("id", "expected_hash")
    }
}
