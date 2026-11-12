package co.datapipelines.pipeline.inverse

import co.datapipelines.pipeline.DatasourceRef
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplatePin

/**
 * #877 §C — the pipeline arms' rows as the answers [co.datapipelines.pipeline.PipelineRepository]'s pin scans have
 * always returned: ONE mapping and ONE order per answer, read by the repository's own scans (which page the native
 * query in place) and by the application adapters over `findIncoming` (which receive the same rows through the
 * provider). Two readers, one definition — an adapter can never order or dedupe a pin list differently from the scan
 * it replaces.
 */
object PipelinePinRows {
    /** The used-by, working and live listings' order: pipeline name, then node. */
    val BY_NAME_THEN_NODE: Comparator<TemplatePin> = compareBy<TemplatePin> { it.pipelineName }.thenBy { it.nodeId }

    /** The any-version scans' order: pipeline name, newest version first, then node. */
    val BY_NAME_NEWEST_THEN_NODE: Comparator<TemplatePin> =
        compareBy<TemplatePin> { it.pipelineName }.thenByDescending { it.pipelineVersion }.thenBy { it.nodeId }

    /** A template- or child-pin row as the [TemplatePin] it reports (a stored version the body does not carry reads 0). */
    fun templatePin(row: InverseRow): TemplatePin =
        TemplatePin(
            row.dependentUuid,
            row.dependentName,
            row.dependentVersion,
            statusOf(row),
            row.elementId.orEmpty(),
            row.storedVersion ?: 0,
        )

    /**
     * Distinct pipelines per stored template version — the template screen's per-version in-use count (040 D6); a row
     * that stores no integral version counts toward none.
     */
    fun countByPinnedVersion(rows: List<InverseRow>): Map<Int, Int> =
        rows
            .filter { it.storedVersion != null }
            .groupBy { checkNotNull(it.storedVersion) }
            .mapValues { (_, pinning) -> pinning.map { it.dependentId }.distinct().size }

    /** Child-pin rows as the PARENTS of [pipelineName]: a pipeline's own versions never count as its parents. */
    fun parents(
        rows: List<InverseRow>,
        pipelineName: String,
    ): List<TemplatePin> = rows.filter { it.dependentName != pipelineName }.map(::templatePin).sortedWith(BY_NAME_THEN_NODE)

    /**
     * Datasource rows as the delete guard's [DatasourceRef]s: one per NODE (a node naming the datasource as both source
     * and output is one reference), pipeline name, newest version, node order.
     */
    fun datasourceRefs(rows: List<InverseRow>): List<DatasourceRef> =
        rows
            .distinctBy { Triple(it.dependentId, it.dependentVersion, it.elementId) }
            .map { DatasourceRef(it.dependentUuid, it.dependentName, it.dependentVersion, statusOf(it), it.elementId.orEmpty()) }
            .sortedWith(compareBy<DatasourceRef> { it.pipelineName }.thenByDescending { it.pipelineVersion }.thenBy { it.nodeId })

    private fun statusOf(row: InverseRow): PipelineVersionStatus = checkNotNull(row.status) { "a pipeline version has a status" }
}
