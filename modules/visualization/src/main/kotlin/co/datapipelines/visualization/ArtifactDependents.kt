package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * One pin found by [ArtifactDependents]: the stored version of one visualization or dashboard ([name]@[version],
 * with its lifecycle [status]) that pins [pinnedVersion] of the artifact the question asked about.
 */
data class ArtifactPin(
    val artifactId: UUID,
    val name: String,
    val version: Int,
    val status: PipelineVersionStatus,
    val pinnedVersion: Int,
)

/**
 * Which stored versions of a dependent count when a reverse arrow is asked — the three questions the graph rules of
 * versioning §3.5 put to a dependent, the `ParameterSetTemplatePins` split carried over.
 */
enum class PinScope {
    /**
     * Each live dependent's WORKING version — its draft when one exists, else its pointer — so a draft that just adopted
     * the pin is counted. `templates_used_by` and the screens' in-use counts ask this.
     */
    WORKING,

    /** Every DRAFT or RELEASED version of a dependent — the discard and purge guards' exact-pin evidence (graph rule 1). */
    LIVE,

    /**
     * Every version ever stored, DISCARDED included, of a dependent that still has a live version — the entity purge's
     * evidence (graph rule 3, owner ruling R12: a discarded version can be restored and must keep what it pins).
     */
    ANY,
}

/**
 * The reverse arrows INTO other families from the two artifact families this module persists — "who pins this?"
 * for a transform template (a visualization's `transform.template`), a pipeline release (a dashboard source's
 * `pipeline`) and a parameter-set release (a dashboard's `parameter_set`). #320: the design record §4.2 ("deletion and
 * purge guards keep referenced releases") for the dependencies OUTSIDE this module; [DashboardRepository.livePinsOf]
 * is the in-module arrow it sits beside.
 *
 * Declared here, over `visualization_versions` and `dashboard_versions`, because the JSONB predicate is the body
 * shape's property; the modules that own the pinned artifact reach it through a port `application` implements (no
 * module's SQL names another module's tables — graph rule 1's composition is `application`'s).
 *
 * **Every statement is workspace-scoped** — the pinned name is per workspace, so a same-named artifact in another
 * workspace never matches, and every probe is a BOUND JSON value (no JSON path is built from input). **No index**:
 * `body_json` has no GIN index (V42) and none is proposed; one scan per arrow per verb over a workspace's stored
 * versions is the cost, measured in the #320 evidence.
 *
 * **Public and UNLENSED, by design (#340):** every row comes back, drafts and hidden names included, because the guards
 * that ask it are author-or-above verbs. A consumer that answers a caller whose lens narrows (a promoter) applies BOTH
 * halves itself — the name through the family's lens, and RELEASED rows only (178b: a draft's number never reaches a
 * promoter; see `TemplateUsage.visible` and the Usage tab's dashboards half) — or it leaks what the lens hides.
 */
class ArtifactDependents(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** The visualization versions that pin transform template [templateId] — at [version], or at any version when null. */
    fun visualizationsPinningTemplate(
        workspaceId: UUID,
        templateId: String,
        version: Int?,
        scope: PinScope,
    ): List<ArtifactPin> =
        scan(
            workspaceId,
            ArtifactKind.VISUALIZATION,
            scope,
            objectProbe("transform", "template", ref(templateId, version)),
            "(v.body_json -> 'transform' -> 'template' ->> 'version')::int",
            "v.body_json @> CAST(:probe AS jsonb)",
        )

    /** The dashboard versions with a source that pins pipeline [pipelineName] — at [version], or at any version when null. */
    fun dashboardsPinningPipeline(
        workspaceId: UUID,
        pipelineName: String,
        version: Int?,
        scope: PinScope,
    ): List<ArtifactPin> =
        scan(
            workspaceId,
            ArtifactKind.DASHBOARD,
            scope,
            objectProbe("pipeline", null, ref(pipelineName, version)),
            "(src -> 'pipeline' ->> 'version')::int",
            "src @> CAST(:probe AS jsonb)",
            "CROSS JOIN LATERAL jsonb_array_elements(v.body_json -> 'sources') AS src",
        )

    /** The dashboard versions that pin parameter set [setName] — at [version], or at any version when null. */
    fun dashboardsPinningParameterSet(
        workspaceId: UUID,
        setName: String,
        version: Int?,
        scope: PinScope,
    ): List<ArtifactPin> =
        scan(
            workspaceId,
            ArtifactKind.DASHBOARD,
            scope,
            objectProbe("parameter_set", null, ref(setName, version)),
            "(v.body_json -> 'parameter_set' ->> 'version')::int",
            "v.body_json @> CAST(:probe AS jsonb)",
        )

    /**
     * The one statement shape: the [kind]'s versions joined to their index row (the workspace filter), narrowed by
     * [scope], matched by [match] against the bound `:probe`. Only closed constants — the kind's table names and the
     * callers' fixed expressions — are interpolated; every value travels as a bind.
     */
    @Suppress("LongParameterList") // one statement shape; the three arrows differ only in these fragments
    private fun scan(
        workspaceId: UUID,
        kind: ArtifactKind,
        scope: PinScope,
        probe: JsonNode,
        pinnedVersion: String,
        match: String,
        lateral: String = "",
    ): List<ArtifactPin> =
        jdbc.query(
            """
            SELECT DISTINCT s.id AS artifact_id, s.name AS name, v.version AS version, v.status AS status,
                   $pinnedVersion AS pinned_version
              FROM ${kind.versions} v
              JOIN ${kind.index} s ON s.id = v.${kind.fk}
              $lateral
             WHERE s.workspace_id = :workspaceId AND $match AND ${scopePredicate(kind, scope)}
             ORDER BY s.name, v.version DESC
            """.trimIndent(),
            mapOf("workspaceId" to workspaceId, "probe" to ArtifactJson.mapper.writeValueAsString(probe)),
        ) { rs, _ ->
            ArtifactPin(
                artifactId = UUID.fromString(rs.getString("artifact_id")),
                name = rs.getString("name"),
                version = rs.getInt("version"),
                status = PipelineVersionStatus.fromWire(rs.getString("status")),
                pinnedVersion = rs.getInt("pinned_version"),
            )
        }

    /** The [scope]'s predicate over the versions alias `v` and the index alias `s`. */
    private fun scopePredicate(
        kind: ArtifactKind,
        scope: PinScope,
    ): String {
        val entityIsLive =
            "EXISTS (SELECT 1 FROM ${kind.versions} lv WHERE lv.${kind.fk} = s.id AND lv.status IN ('DRAFT', 'RELEASED'))"
        return when (scope) {
            PinScope.LIVE -> {
                "v.status IN ('DRAFT', 'RELEASED')"
            }

            PinScope.ANY -> {
                entityIsLive
            }

            PinScope.WORKING -> {
                "$entityIsLive AND v.version = COALESCE(" +
                    "(SELECT d.version FROM ${kind.versions} d WHERE d.${kind.fk} = s.id AND d.status = 'DRAFT' LIMIT 1), " +
                    "s.current_version)"
            }
        }
    }

    private fun ref(
        name: String,
        version: Int?,
    ): ObjectNode =
        ArtifactJson.mapper
            .createObjectNode()
            .put("name", name)
            .also { node -> version?.let { node.put("version", it) } }

    /**
     * The containment probe for a pin at `outer[.inner]`: `{outer: {inner: ref}}`, `{outer: ref}` when [inner] is null.
     * For an array-element match (`src @> probe`) the probe is the element's shape, so the outer key IS the field.
     */
    private fun objectProbe(
        outer: String,
        inner: String?,
        pin: ObjectNode,
    ): JsonNode {
        val mapper = ArtifactJson.mapper
        val body = if (inner == null) pin else mapper.createObjectNode().set<JsonNode>(inner, pin)
        return mapper.createObjectNode().set<JsonNode>(outer, body)
    }
}
