package co.datapipelines.parameters

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.inverse.InverseRequest
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope
import co.datapipelines.pipeline.inverse.InverseSql
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * One parameter-set pin of a template version, from ONE stored set version (the record's §8.4 —
 * the `TemplatePin` shape with a set where a pipeline stands and the PARAMETER where a node
 * stands). `parameter` is the load-bearing field the pipeline analogue derives from a node id:
 * it names the one definition an author edits to move the pin.
 */
data class ParameterSetPin(
    val setId: UUID,
    val setName: String,
    val parameter: String,
    val setVersion: Int,
    val versionStatus: PipelineVersionStatus,
    val pinnedVersion: Int,
)

/**
 * The parameters side of the templates reverse arrow (record §8.4, #194 lane D) — the same two
 * questions [co.datapipelines.templates.TemplateUsageService] asks of pipelines, asked of
 * parameter sets. ADDITIVE to the frozen repository (a new class over the same tables; no
 * frozen method touched):
 *
 *  - **Who pins `templateId@version` right now?** [workingVersionPins] scans each live set's
 *    WORKING version — the draft when one exists, else the pointer — so a draft that just
 *    adopted the pin is counted. It drives `templates_used_by` and the template screen's
 *    per-version in-use counts.
 *  - **Is it safe to remove the template?** [anyVersionPins] scans EVERY version, ever — a set
 *    version is immutable and executable (an explicit `version` evaluate reads any stored
 *    body), so a reference from a historical version is a real reference. It drives the
 *    delete guard's `details.referencing_parameter_sets`.
 *
 * The statement lives beside [ParameterSetRepository] (same tables, same discipline) because
 * the JSONB predicate is the body shape's property — and the composition with the pipeline
 * scan is `application`'s (the record's §8.4), which sees only this class.
 *
 * #877 §B.3: every question pages the ONE native bounded query [ParameterSetRepository.findTemplatePinOccurrences] — the
 * same statement the template family's parameter-set inverse arm reads — to exhaustion, and answers in its existing order.
 */
class ParameterSetTemplatePins(
    jdbc: NamedParameterJdbcTemplate,
) {
    private val sets = ParameterSetRepository(jdbc)

    /** One row per pinning parameter of one live set's working version, at exactly `templateId@version`. */
    fun workingVersionPins(
        workspaceId: UUID,
        templateId: String,
        version: Int,
    ): List<ParameterSetPin> = pins(workspaceId, templateId, version, InverseScope.WORKING).sortedWith(BY_NAME_THEN_PARAMETER)

    /**
     * One row per pinning parameter of every LIVE (DRAFT or RELEASED — never DISCARDED) set
     * version at exactly `templateId@version` — the discard/purge guard's exact-pin evidence,
     * the twin of `PipelineRepository.findLiveVersionsPinningTemplateVersion`.
     */
    fun liveVersionPins(
        workspaceId: UUID,
        templateId: String,
        version: Int,
    ): List<ParameterSetPin> = pins(workspaceId, templateId, version, InverseScope.LIVE).sortedWith(BY_NAME_NEWEST_THEN_PARAMETER)

    /** The working-version pins of ANY version of `templateId` — the screen counts' lensed re-derivation. */
    fun workingVersionPins(
        workspaceId: UUID,
        templateId: String,
    ): List<ParameterSetPin> = pins(workspaceId, templateId, null, InverseScope.WORKING).sortedWith(BY_NAME_THEN_PARAMETER)

    /** One row per pinning parameter of ANY version ever stored, any pin of this template id. */
    fun anyVersionPins(
        workspaceId: UUID,
        templateId: String,
    ): List<ParameterSetPin> = pins(workspaceId, templateId, null, InverseScope.ANY).sortedWith(BY_NAME_NEWEST_THEN_PARAMETER)

    /** The working-version pins grouped by pinned version, the SET as the unit — the screen-count twin. */
    fun countWorkingPinsByPinnedVersion(
        workspaceId: UUID,
        templateId: String,
    ): Map<Int, Int> =
        pins(workspaceId, templateId, null, InverseScope.WORKING)
            .let(::countByPinnedVersion)

    private fun pins(
        workspaceId: UUID,
        templateId: String,
        version: Int?,
        scope: InverseScope,
    ): List<ParameterSetPin> =
        InverseSql
            .exhaust(InverseSql.EXHAUST_PAGE) { after ->
                sets.findTemplatePinOccurrences(InverseRequest(workspaceId, templateId, version, scope, after, InverseSql.EXHAUST_PAGE))
            }.let(::pinsOf)

    /**
     * #877 §C — the set arm's rows as this class's answers: ONE mapping and ONE order, read by these scans (which page
     * the native query in place) and by the application adapter over `findIncoming` (which receives the same rows).
     */
    companion object {
        /** The working listings' order: set name, then parameter. */
        val BY_NAME_THEN_PARAMETER: Comparator<ParameterSetPin> = compareBy<ParameterSetPin> { it.setName }.thenBy { it.parameter }

        /** The live and any-version scans' order: set name, newest set version first, then parameter. */
        val BY_NAME_NEWEST_THEN_PARAMETER: Comparator<ParameterSetPin> =
            compareBy<ParameterSetPin> { it.setName }.thenByDescending { it.setVersion }.thenBy { it.parameter }

        /** Rows as pins, unordered. The native scans cast the stored version; a non-integral one never counted as a pin. */
        fun pinsOf(rows: List<InverseRow>): List<ParameterSetPin> =
            rows.mapNotNull { row ->
                row.storedVersion?.let { pinned ->
                    ParameterSetPin(
                        setId = row.dependentUuid,
                        setName = row.dependentName,
                        parameter = row.elementId.orEmpty(),
                        setVersion = row.dependentVersion,
                        versionStatus = checkNotNull(row.status) { "a set version has a status" },
                        pinnedVersion = pinned,
                    )
                }
            }

        /** Distinct sets per pinned template version (the template screen's per-version count). */
        fun countByPinnedVersion(pins: List<ParameterSetPin>): Map<Int, Int> =
            pins.groupBy { it.pinnedVersion }.mapValues { (_, pinning) -> pinning.map { it.setId }.distinct().size }
    }
}
