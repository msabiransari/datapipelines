package co.datapipelines.web.incoming

import co.datapipelines.application.dependencies.EndpointDependencySource
import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.application.dependencies.StoredVersion
import co.datapipelines.application.dependencies.dashboard.DashboardReleaseProvider
import co.datapipelines.application.dependencies.parameterset.ParameterSetReleaseBody
import co.datapipelines.application.dependencies.parameterset.ParameterSetReleaseProvider
import co.datapipelines.application.dependencies.prerequisites.DatasourceEdges
import co.datapipelines.application.dependencies.schedule.ExecutorTarget
import co.datapipelines.application.dependencies.template.TemplateReleaseProvider
import co.datapipelines.application.dependencies.visualization.VisualizationReleaseProvider
import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Relationship
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateImport
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.BodyCodec
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.web.schedules.PipelineGraphTarget
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import java.util.UUID

/** One stored reference occurrence — the A27 comparison unit (the brief's tuple, plus the dependent's name). */
data class ParityOccurrence(
    val dependentFamily: FamilyKey,
    val dependentId: String,
    val dependentName: String,
    val dependentVersion: Int,
    val relationship: Relationship,
    val sourcePath: String?,
    val targetFamily: FamilyKey,
    val targetKey: String,
    val storedVersion: Int?,
)

/**
 * An extracted occurrence with the facts the test's scope predicate reads: the dependent version's [status] (null for an
 * unversioned schedule), whether its entity still has a DRAFT or RELEASED version ([entityLive]), whether it is the
 * entity's working version — the DRAFT, else the current pointer ([working]) — and, for a schedule, [deleted].
 */
data class OracleRow(
    val occurrence: ParityOccurrence,
    val status: PipelineVersionStatus?,
    val entityLive: Boolean,
    val working: Boolean,
    val deleted: Boolean,
)

/**
 * #877 §E.1 side (b) — the A27 oracle: reads EVERY stored version row of every dependent family of [workspaceId] (all
 * statuses, plain SQL written here, never a repository's inverse query), builds the body and runs the family's raw-body
 * EXTRACTION function on it — the four `references(stored)` overrides (template, parameter set, visualization,
 * dashboard), `PipelineDependencySource.pinsOf` + `pinReferences` and `DatasourceEdges.ofPipeline` for pipelines,
 * `EndpointDependencySource.pinsOf` + `pinReferences` for API versions, and the pipeline executor's own
 * `ExecutorTargetExtractor.targetOf` ([PipelineGraphTarget]) for schedules. It never calls `resolveMany` or the walker, so
 * the walk's DISCARDED-is-terminal rule does not apply: a DISCARDED body's references are extracted like any other.
 */
class IncomingParityOracle(
    private val jdbc: NamedParameterJdbcTemplate,
    private val workspaceId: UUID,
    private val userId: UUID,
) {
    // The extraction functions are pure over a loaded body; the providers' repositories and ceiling are never reached.
    private val templates = TemplateReleaseProvider(TemplateRepository(jdbc), StatementCeiling.NONE)
    private val sets = ParameterSetReleaseProvider(ParameterSetRepository(jdbc), TemplateRepository(jdbc), StatementCeiling.NONE)
    private val visualizations = VisualizationReleaseProvider(VisualizationRepository(jdbc), StatementCeiling.NONE)
    private val dashboards = DashboardReleaseProvider(DashboardRepository(jdbc), StatementCeiling.NONE)
    private val json = ObjectMapper()

    /** Every stored occurrence of every dependent family, with its scope facts. */
    fun enumerate(): List<OracleRow> =
        versioned(FamilyKey.PIPELINE, "pipelines", "pipeline_versions", "pipeline_id", "e.name", "v.body_json::text") { row ->
            val body = PipelineDeserializer().readOrThrow(row.body)
            ReleaseDependencyFamilies.pinReferences(ReleaseFamily.PIPELINE, PipelineDependencySource.pinsOf(workspaceId, body)) +
                DatasourceEdges.ofPipeline(workspaceId, body)
        } +
            versioned(FamilyKey.TEMPLATE, "templates", "template_versions", "template_id", "e.name", IMPORTS) { row ->
                templates.references(stored(row, ReleaseFamily.TEMPLATE, template(row)))
            } +
            versioned(FamilyKey.PARAMETER_SET, "parameter_sets", "parameter_set_versions", "parameter_set_id", "e.name", BODY) { row ->
                sets.references(stored(row, ReleaseFamily.PARAMETER_SET, ParameterSetReleaseBody(ParameterSetJson.readBody(row.body))))
            } +
            versioned(FamilyKey.VISUALIZATION, "visualizations", "visualization_versions", "visualization_id", "e.name", BODY) { row ->
                visualizations.references(stored(row, ReleaseFamily.VISUALIZATION, BodyCodec.Visualization.read(row.body)))
            } +
            versioned(FamilyKey.DASHBOARD, "dashboards", "dashboard_versions", "dashboard_id", "e.name", BODY) { row ->
                dashboards.references(stored(row, ReleaseFamily.DASHBOARD, BodyCodec.Dashboard.read(row.body)))
            } +
            versioned(
                FamilyKey.API_ENDPOINT,
                "published_endpoints",
                "published_endpoint_versions",
                "endpoint_id",
                "e.path_pattern",
                API,
            ) { row ->
                val stored = json.readTree(row.body)
                val body =
                    EndpointVersionBody(
                        stored.path("pipeline_name").asText(),
                        stored.path("pipeline_version").asInt(),
                        stored.path("timeout_seconds").asInt(),
                        stored.path("description").asText(),
                    )
                ReleaseDependencyFamilies.pinReferences(ReleaseFamily.ENDPOINT, EndpointDependencySource.pinsOf(workspaceId, body))
            } +
            schedules()

    private data class StoredRow(
        val id: String,
        val name: String,
        val currentVersion: Int?,
        val version: Int,
        val status: PipelineVersionStatus,
        val body: String,
    )

    @Suppress("LongParameterList") // one family's three table names, its name column, its body expression and its extractor
    private fun versioned(
        family: FamilyKey,
        entities: String,
        versions: String,
        fk: String,
        name: String,
        body: String,
        extract: (StoredRow) -> List<Reference>,
    ): List<OracleRow> {
        val rows =
            jdbc.query(
                "SELECT e.id::text AS id, $name AS name, e.current_version AS current_version, v.version AS version," +
                    " v.status AS status, $body AS body FROM $entities e JOIN $versions v ON v.$fk = e.id WHERE e.workspace_id = :ws",
                mapOf("ws" to workspaceId),
            ) { rs, _ ->
                StoredRow(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getObject("current_version") as Int?,
                    rs.getInt("version"),
                    PipelineVersionStatus.valueOf(rs.getString("status")),
                    rs.getString("body"),
                )
            }
        return rows.groupBy { it.id }.values.flatMap { entity ->
            val live = entity.any { it.status in LIVE }
            val workingVersion = entity.firstOrNull { it.status == PipelineVersionStatus.DRAFT }?.version ?: entity.first().currentVersion
            entity.flatMap { row ->
                extract(row).map { reference ->
                    OracleRow(
                        occurrence(family, row.id, row.name, row.version, reference),
                        row.status,
                        live,
                        live && row.version == workingVersion,
                        false,
                    )
                }
            }
        }
    }

    private fun schedules(): List<OracleRow> =
        jdbc.query(
            "SELECT id::text AS id, name, payload_schema_version, payload_json::text AS payload, deleted_at IS NOT NULL AS deleted" +
                " FROM schedules WHERE workspace_id = :ws",
            mapOf("ws" to workspaceId),
        ) { rs, _ ->
            val target = PipelineGraphTarget.of(rs.getInt("payload_schema_version"), json.readTree(rs.getString("payload")))
            check(target is ExecutorTarget.Pipeline) { "the corpus plants no unreadable payload, read $target" }
            OracleRow(
                ParityOccurrence(
                    FamilyKey.SCHEDULE,
                    rs.getString("id"),
                    rs.getString("name"),
                    0,
                    Relationship.EXECUTOR_TARGET,
                    target.sourcePath,
                    FamilyKey.PIPELINE,
                    target.name,
                    target.selector.number,
                ),
                status = null,
                entityLive = true,
                working = true,
                deleted = rs.getBoolean("deleted"),
            )
        }

    private fun occurrence(
        family: FamilyKey,
        id: String,
        name: String,
        version: Int,
        reference: Reference,
    ) = ParityOccurrence(
        family,
        id,
        name,
        version,
        reference.relationship,
        reference.sourcePath,
        reference.request.address.family,
        reference.request.address.lookupKey,
        reference.request.selector.number,
    )

    private fun <B : Any> stored(
        row: StoredRow,
        family: ReleaseFamily,
        body: B,
    ) = StoredVersion(
        identity = ReleaseNodeIdentity(workspaceId, family, row.name, row.version),
        entityUuid = UUID.fromString(row.id),
        name = row.name,
        status = row.status,
        bodyHash = "",
        currentVersion = row.currentVersion,
        body = body,
    )

    /** The stored template version as the release provider reads it: its id, version and `imports_json` occurrences. */
    private fun template(row: StoredRow): Template =
        Template(
            id = row.name,
            version = row.version,
            dialect = null,
            displayName = row.name,
            description = "",
            imports =
                json
                    .readTree(
                        row.body,
                    ).map { TemplateImport(it.path("id").asText(), it.path("version").asInt(), it.path("alias").asText()) },
            body = "",
            createdAt = Instant.EPOCH,
            createdBy = userId,
            status = row.status,
        )

    private companion object {
        val LIVE = setOf(PipelineVersionStatus.DRAFT, PipelineVersionStatus.RELEASED)
        const val BODY = "v.body_json::text"
        const val IMPORTS = "COALESCE(v.imports_json, '[]'::jsonb)::text"
        const val API =
            "jsonb_build_object('pipeline_name', v.pipeline_name, 'pipeline_version', v.pipeline_version," +
                " 'timeout_seconds', v.timeout_seconds, 'description', v.description)::text"
    }
}
