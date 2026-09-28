package co.datapipelines.parameters

import co.datapipelines.pipeline.PipelineVersionStatus
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
 */
class ParameterSetTemplatePins(private val jdbc: NamedParameterJdbcTemplate) {
    /** One row per pinning parameter of one live set's working version, at exactly `templateId@version`. */
    fun workingVersionPins(
        workspaceId: UUID,
        templateId: String,
        version: Int,
    ): List<ParameterSetPin> =
        jdbc.query(
            WORKING_PINS_SQL,
            mapOf("workspaceId" to workspaceId, "templateId" to templateId, "pinnedVersion" to version),
            MAPPER,
        )

    /**
     * One row per pinning parameter of every LIVE (DRAFT or RELEASED — never DISCARDED) set
     * version at exactly `templateId@version` — the discard/purge guard's exact-pin evidence,
     * the twin of `PipelineRepository.findLiveVersionsPinningTemplateVersion`.
     */
    fun liveVersionPins(
        workspaceId: UUID,
        templateId: String,
        version: Int,
    ): List<ParameterSetPin> =
        jdbc.query(
            LIVE_VERSION_PINS_SQL,
            mapOf("workspaceId" to workspaceId, "templateId" to templateId, "pinnedVersion" to version),
            MAPPER,
        )

    /** The working-version pins of ANY version of `templateId` — the screen counts' lensed re-derivation. */
    fun workingVersionPins(
        workspaceId: UUID,
        templateId: String,
    ): List<ParameterSetPin> =
        jdbc.query(
            COUNT_WORKING_PINS_ROWS_SQL,
            mapOf("workspaceId" to workspaceId, "templateId" to templateId),
            MAPPER,
        )

    /** One row per pinning parameter of ANY version ever stored, any pin of this template id. */
    fun anyVersionPins(
        workspaceId: UUID,
        templateId: String,
    ): List<ParameterSetPin> =
        jdbc.query(
            ANY_PINS_SQL,
            mapOf("workspaceId" to workspaceId, "templateId" to templateId),
            MAPPER,
        )

    /** The working-version pins grouped by pinned version, the SET as the unit — the screen-count twin. */
    fun countWorkingPinsByPinnedVersion(
        workspaceId: UUID,
        templateId: String,
    ): Map<Int, Int> =
        jdbc
            .query(
                COUNT_WORKING_PINS_SQL,
                mapOf("workspaceId" to workspaceId, "templateId" to templateId),
            ) { rs, _ -> rs.getInt("pinned_version") to rs.getInt("sets") }
            .toMap()

    private companion object {
        /**
         * The derived-ACTIVE predicate, the same expression [ParameterSetRepository] spells
         * (`LIVE_S`) — repeated here because that constant is private to the frozen file and
         * this scanner is a separate additive class over the same tables.
         */
        private const val LIVE_S =
            "EXISTS (SELECT 1 FROM parameter_set_versions lv WHERE lv.parameter_set_id = %s.id" +
                " AND lv.status IN ('DRAFT','RELEASED'))"

        private val WORKING_LIVE = LIVE_S.format("s")

        /** The set analogue of `TemplateRepository`'s pin scans: the working version is the draft else the pointer. */
        private val WORKING_PINS_SQL =
            """
            SELECT s.id AS set_id, s.name AS set_name, p->>'name' AS parameter,
                   v.version AS set_version, v.status AS version_status,
                   (p->'source'->'template'->>'version')::int AS pinned_version
              FROM parameter_sets s
              JOIN parameter_set_versions v
                ON v.parameter_set_id = s.id
               AND v.version = COALESCE(
                       (SELECT d.version FROM parameter_set_versions d
                         WHERE d.parameter_set_id = s.id AND d.status = 'DRAFT' LIMIT 1),
                       s.current_version)
             CROSS JOIN LATERAL jsonb_array_elements(v.body_json->'parameters') AS p
             WHERE s.workspace_id = :workspaceId AND $WORKING_LIVE
               AND p->'source'->'template'->>'id' = :templateId
               AND (p->'source'->'template'->>'version')::int = :pinnedVersion
             ORDER BY s.name, p->>'name'
            """.trimIndent()

        /** The record's §8.4 delete-guard evidence: every version ever, any pin of this template id. */
        private val ANY_PINS_SQL =
            """
            SELECT s.id AS set_id, s.name AS set_name, p->>'name' AS parameter,
                   v.version AS set_version, v.status AS version_status,
                   (p->'source'->'template'->>'version')::int AS pinned_version
              FROM parameter_sets s
              JOIN parameter_set_versions v ON v.parameter_set_id = s.id
             CROSS JOIN LATERAL jsonb_array_elements(v.body_json->'parameters') AS p
             WHERE s.workspace_id = :workspaceId AND $WORKING_LIVE
               AND p->'source'->'template'->>'id' = :templateId
             ORDER BY s.name, v.version DESC, p->>'name'
            """.trimIndent()

        /** The discard/purge guard's evidence: LIVE set versions at this exact pin (D60's rule 1, sets included). */
        private val LIVE_VERSION_PINS_SQL =
            """
            SELECT s.id AS set_id, s.name AS set_name, p->>'name' AS parameter,
                   v.version AS set_version, v.status AS version_status,
                   (p->'source'->'template'->>'version')::int AS pinned_version
              FROM parameter_sets s
              JOIN parameter_set_versions v ON v.parameter_set_id = s.id
             CROSS JOIN LATERAL jsonb_array_elements(v.body_json->'parameters') AS p
             WHERE s.workspace_id = :workspaceId AND $WORKING_LIVE
               AND v.status IN ('DRAFT','RELEASED')
               AND p->'source'->'template'->>'id' = :templateId
               AND (p->'source'->'template'->>'version')::int = :pinnedVersion
             ORDER BY s.name, v.version DESC, p->>'name'
            """.trimIndent()

        /** The working-version scan, every pinned version as rows — the screen counts' lensed re-derivation. */
        private val COUNT_WORKING_PINS_ROWS_SQL =
            """
            SELECT s.id AS set_id, s.name AS set_name, p->>'name' AS parameter,
                   v.version AS set_version, v.status AS version_status,
                   (p->'source'->'template'->>'version')::int AS pinned_version
              FROM parameter_sets s
              JOIN parameter_set_versions v
                ON v.parameter_set_id = s.id
               AND v.version = COALESCE(
                       (SELECT d.version FROM parameter_set_versions d
                         WHERE d.parameter_set_id = s.id AND d.status = 'DRAFT' LIMIT 1),
                       s.current_version)
             CROSS JOIN LATERAL jsonb_array_elements(v.body_json->'parameters') AS p
             WHERE s.workspace_id = :workspaceId AND $WORKING_LIVE
               AND p->'source'->'template'->>'id' = :templateId
             ORDER BY s.name, p->>'name'
            """.trimIndent()

        /** The working-version scan grouped by pinned version, sets as the unit (the screen-count twin). */
        private val COUNT_WORKING_PINS_SQL =
            """
            SELECT (p->'source'->'template'->>'version')::int AS pinned_version,
                   COUNT(DISTINCT s.id)::int AS sets
              FROM parameter_sets s
              JOIN parameter_set_versions v
                ON v.parameter_set_id = s.id
               AND v.version = COALESCE(
                       (SELECT d.version FROM parameter_set_versions d
                         WHERE d.parameter_set_id = s.id AND d.status = 'DRAFT' LIMIT 1),
                       s.current_version)
             CROSS JOIN LATERAL jsonb_array_elements(v.body_json->'parameters') AS p
             WHERE s.workspace_id = :workspaceId AND $WORKING_LIVE
               AND p->'source'->'template'->>'id' = :templateId
             GROUP BY 1
            """.trimIndent()

        private val MAPPER = { rs: java.sql.ResultSet, _: Int ->
            ParameterSetPin(
                setId = UUID.fromString(rs.getString("set_id")),
                setName = rs.getString("set_name"),
                parameter = rs.getString("parameter"),
                setVersion = rs.getInt("set_version"),
                versionStatus = PipelineVersionStatus.valueOf(rs.getString("version_status")),
                pinnedVersion = rs.getInt("pinned_version"),
            )
        }
    }
}
