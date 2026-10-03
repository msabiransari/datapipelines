package co.datapipelines.web.ui

import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRecord
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.ParameterSetVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import java.time.Instant
import java.util.UUID

/**
 * The Parameter Sets screens' shared test fixtures (#374): one record, one body and one version row per
 * request, built from the REAL contract types so a test asserts the page over a body the engine would
 * accept — never over a mock's invented shape.
 */
internal object ParameterSetsUiFixtures {
    val workspaceId: UUID = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()
    private val at: Instant = Instant.parse("2026-10-02T00:00:00Z")

    /** A one-parameter body; [displayName] is the field a hostile value rides in. */
    fun body(displayName: String = "Geo filters"): ParameterSetBody =
        ParameterSetJson.mapper.treeToValue(
            ParameterSetJson.mapper.readTree(
                ParameterSetJson.mapper.writeValueAsString(
                    mapOf(
                        "display_name" to displayName,
                        "parameters" to
                            listOf(
                                mapOf(
                                    "name" to "country",
                                    "label" to "Country",
                                    "type" to "STRING",
                                    "kind" to "SELECT",
                                    "cardinality" to "SINGLE",
                                    "source" to
                                        mapOf(
                                            "constants" to
                                                listOf(mapOf("value" to "USA", "display_value" to "United States", "is_default" to true)),
                                        ),
                                ),
                            ),
                    ),
                ),
            ),
            ParameterSetBody::class.java,
        )

    fun record(
        id: UUID,
        name: String = "acme/geo_filters",
        displayName: String = "Geo filters",
        currentVersion: Int? = 1,
    ) = ParameterSetRecord(id, workspaceId, name, displayName, "", currentVersion, at, at, userId)

    fun detail(
        id: UUID,
        version: Int,
        status: PipelineVersionStatus,
    ) = ParameterSetVersionDetail(id, version, status, "hash-$version", at, userId)

    fun version(
        record: ParameterSetRecord,
        version: Int,
        status: PipelineVersionStatus,
        displayName: String = record.displayName,
    ) = ParameterSetVersion(record, detail(record.id, version, status), body(displayName))
}
