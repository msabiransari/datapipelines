package co.datapipelines.parameters

import co.datapipelines.parameters.ParametersTestDb.AUTHOR
import co.datapipelines.parameters.ParametersTestDb.OTHER_WORKSPACE
import co.datapipelines.parameters.ParametersTestDb.WORKSPACE
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #873 §F.3 e — the datasource name lock's parameter-set scan ([ParameterSetRepository.findAnyVersionDatasourceRefs]) on
 * real rows: every stored version of a LIVE set (one with a DRAFT or RELEASED version — the pipeline scan's rule),
 * DISCARDED versions included, one row per selector occurrence with its index, newest version first; a set whose every
 * version is DISCARDED is not live and locks nothing (the pipeline arm's parity); another workspace's scan sees nothing.
 */
class ParameterSetDatasourceRefsIntegrationTest {
    private lateinit var h: ParametersHarness

    @BeforeEach
    fun reset() {
        ParametersTestDb.reset()
        h = ParametersHarness(datasources = FakeDatasources(mapOf("warehouse" to Dialect.POSTGRES, "mart" to Dialect.POSTGRES)))
    }

    private val setName = "acme/sales/region_filters"

    /** A set whose `state` selector (parameters[1]) reads [datasource]; v1 DRAFT. */
    private fun created(datasource: String = "warehouse"): ParameterSetVersion {
        val pin = h.template("acme/sales/states_of_country.sql", "SELECT :country AS value, :country AS display_value")
        return h.create(h.document(body(pin.version, datasource)))
    }

    private fun body(
        templateVersion: Int,
        datasource: String,
    ): String =
        ParameterSetFixtures.setJson(
            ParameterSetFixtures.countryJson(),
            ParameterSetFixtures
                .stateJson()
                .replace("\"version\": 3", "\"version\": $templateVersion")
                .replace("\"datasource\": \"warehouse\"", "\"datasource\": \"$datasource\""),
        )

    private fun refs(
        datasource: String,
        workspaceId: UUID = WORKSPACE,
    ) = h.repository.findAnyVersionDatasourceRefs(workspaceId, datasource)

    @Test
    fun `every stored version of a live set - DISCARDED included, newest first, the selector's index and path`() {
        val v1 = created()
        val id = v1.record.id

        refs("warehouse") shouldContainExactly listOf(ParameterSetDatasourceRef(setName, 1, DRAFT, 1))
        h.service.release(WORKSPACE, id, v1.detail.bodyHash, AUTHOR)
        val released = checkNotNull(h.repository.findWorking(WORKSPACE, id))
        h.service.write(WORKSPACE, id, h.document(body(1, "mart")), released.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
        h.service.discardVersion(WORKSPACE, id, 1, AUTHOR)

        refs("warehouse") shouldContainExactly listOf(ParameterSetDatasourceRef(setName, 1, DISCARDED, 1))
        refs("warehouse").single().path shouldBe "parameters[1].source.datasource"
        refs("mart") shouldContainExactly listOf(ParameterSetDatasourceRef(setName, 2, DRAFT, 1))
        refs("warehouse", OTHER_WORKSPACE).shouldBeEmpty()
        refs("ware%").shouldBeEmpty()
    }

    @Test
    fun `a set whose every version is DISCARDED is not live and locks nothing - the pipeline scan's rule`() {
        val v1 = created()
        h.service.release(WORKSPACE, v1.record.id, v1.detail.bodyHash, AUTHOR)
        refs("warehouse") shouldContainExactly listOf(ParameterSetDatasourceRef(setName, 1, RELEASED, 1))

        h.service.discardVersion(WORKSPACE, v1.record.id, 1, AUTHOR)

        refs("warehouse").shouldBeEmpty()
    }
}
