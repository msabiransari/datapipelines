package co.datapipelines.web.dependencies

import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The transport table has exactly one row per production family (#875 G4): a ninth production family without a row is
 * red, and a row for a family production does not have is red.
 */
class DependencyFamilyTransportTest {
    private val transport = DependencyFamilyTransport.production(DependenciesRequestTest.LENSES)

    @Test
    fun `the table's families are exactly the production families`() {
        transport.families shouldBe FamilyKey.PRODUCTION.keys
    }

    @Test
    fun `every segment is the family's wire key`() {
        transport.families.forEach { transport.row(it).segment shouldBe it.wire }
    }

    @Test
    fun `the eight not-found codes are the families' own detail-read constants`() {
        transport.families.associate { it.wire to transport.row(it).notFoundCode } shouldBe
            mapOf(
                "pipeline" to PipelineErrorCodes.Execution.NOT_FOUND,
                "template" to PipelineErrorCodes.Template.NOT_FOUND,
                "parameter_set" to PipelineErrorCodes.Parameters.NOT_FOUND,
                "visualization" to PipelineErrorCodes.Visualization.NOT_FOUND,
                "dashboard" to PipelineErrorCodes.Dashboard.NOT_FOUND,
                "api_endpoint" to PipelineErrorCodes.Endpoint.NOT_FOUND,
                "schedule" to PipelineErrorCodes.Schedule.NOT_FOUND,
                "datasource" to PipelineErrorCodes.Datasource.NOT_FOUND,
            )
    }

    @Test
    fun `selector shapes follow the request table - id only for schedule, no version for schedule and datasource`() {
        transport.families.associate { it.wire to transport.row(it).selector } shouldBe
            mapOf(
                "pipeline" to SelectorShape.NAME_AT_VERSION,
                "template" to SelectorShape.NAME_AT_VERSION,
                "parameter_set" to SelectorShape.NAME_AT_VERSION,
                "visualization" to SelectorShape.NAME_AT_VERSION,
                "dashboard" to SelectorShape.NAME_AT_VERSION,
                "api_endpoint" to SelectorShape.NAME_AT_VERSION,
                "schedule" to SelectorShape.ID,
                "datasource" to SelectorShape.NAME,
            )
    }

    @Test
    fun `datasource has no lens - the seven others do`() {
        transport.row(FamilyKey.DATASOURCE).rootLens shouldBe RootLens.None
        (transport.families - FamilyKey.DATASOURCE).forEach { transport.row(it).rootLens.shouldBeInstanceOf<RootLens.Lensed>() }
    }
}
