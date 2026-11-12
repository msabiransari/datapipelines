package co.datapipelines.web.incoming

import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.BodyCodec
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardLayout
import co.datapipelines.visualization.DashboardSource
import co.datapipelines.visualization.RendererKind
import co.datapipelines.visualization.RendererSpec
import co.datapipelines.visualization.TransformBinding
import co.datapipelines.visualization.VisualizationBody
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * #877 §C — DETERMINISTIC SQL seeding of the families the complete-answer test reads (templates, pipelines, parameter
 * sets, visualizations, dashboards; one user, one workspace). Every id is derived from its family and name, so the
 * answers the three read surfaces render are byte-stable across runs AND across code versions — the pre-adaptation
 * answers were captured from the base with this very seed. Every version row carries the lifecycle stamps the schema's
 * CHECKs require; a RELEASED row moves its entity's `current_version` to the highest released number (D60). Bodies are
 * the reference-bearing minimum — the reads under test look at references, never validity. A pin is `name to version`.
 *
 * The same shape as `ReleaseArtifactSeed` in `application`'s tests (which this module cannot see), with fixed ids.
 */
class CompleteAnswerSeed(
    private val jdbc: NamedParameterJdbcTemplate,
    /** The seeding user and workspace; [IncomingParityCorpus] writes through this seed under its own pair. */
    val userId: UUID = idOf("user", "seed"),
    val workspaceId: UUID = idOf("workspace", "seed"),
) {
    /** Empties the metadata (every family cascades from `users`) and seeds the fixed user and workspace. */
    fun reset() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject)" +
                " VALUES (:id, 'seed@example.com', 'T', 'google', 'seed')",
            mapOf("id" to userId),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, 'w_seed', 'W', :owner)",
            mapOf("id" to workspaceId, "owner" to userId),
        )
    }

    fun template(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
    ) {
        jdbc.update(
            "INSERT INTO templates (id, name, display_name, description, current_version, workspace_id, created_by)" +
                " VALUES (:id, :name, 'T', '', NULL, :ws, :owner) ON CONFLICT (workspace_id, name) DO NOTHING",
            mapOf("id" to idOf("template", name), "name" to name, "ws" to workspaceId, "owner" to userId),
        )
        jdbc.update(
            "INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, status, body_hash," +
                " created_by, released_by, released_at, discarded_by, discarded_at)" +
                " SELECT t.id, :version, 'freemarker', 'POSTGRES', false, '[]'::jsonb, 'SELECT 1', :status, :hash," +
                " :owner, $RELEASED_BY, $RELEASED_AT, $DISCARDED_BY, $DISCARDED_AT" +
                " FROM templates t WHERE t.name = :name AND t.workspace_id = :ws",
            mapOf("name" to name, "version" to version, "status" to status.name, "hash" to "t-$name-$version") + owner(),
        )
        pointer("templates", name, status, version)
    }

    /** A pipeline version with one DQL node per template pin, then one PIPELINE node per child pin, in order. */
    fun pipeline(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        templates: List<Pair<String, Int>> = emptyList(),
        children: List<Pair<String, Int>> = emptyList(),
    ) {
        val nodes =
            templates.withIndex().map { (index, pin) ->
                Node(
                    id = "n$index",
                    description = "",
                    type = NodeType.DQL,
                    source = "pg",
                    template = TemplateRef(pin.first, pin.second),
                    output = NodeOutput.Caller,
                    dependsOn = emptyList(),
                )
            } +
                children.withIndex().map { (index, pin) ->
                    Node(
                        id = "c$index",
                        description = "",
                        type = NodeType.PIPELINE,
                        source = "",
                        template = TemplateRef(),
                        output = NodeOutput.Tempdb("stg_c$index"),
                        dependsOn = emptyList(),
                        pipeline = PipelineNodeRef(pin.first, pin.second),
                    )
                }
        pipelineNodes(name, version, status, nodes)
    }

    /** A pipeline version whose body holds exactly [nodes] (any node shape a test needs). */
    fun pipelineNodes(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        nodes: List<Node>,
    ) {
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES (:id, :name, 'P', :owner, NULL, :ws) ON CONFLICT (workspace_id, name) DO NOTHING",
            mapOf("id" to idOf("pipeline", name), "name" to name, "owner" to userId, "ws" to workspaceId),
        )
        val body =
            Pipeline(
                schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
                name = name,
                displayName = "P",
                description = "",
                settings = PipelineSettings(),
                parameters = emptyMap(),
                nodes = nodes,
            )
        jdbc.update(
            "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at," +
                " discarded_by, discarded_at)" +
                " SELECT p.id, :version, CAST(:body AS jsonb), :hash, :status, :owner, $RELEASED_BY, $RELEASED_AT, $DISCARDED_BY," +
                " $DISCARDED_AT FROM pipelines p WHERE p.name = :name AND p.workspace_id = :ws",
            mapOf(
                "name" to name,
                "version" to version,
                "body" to PipelineSerializer().write(body),
                "hash" to "p-$name-$version",
                "status" to status.name,
            ) + owner(),
        )
        pointer("pipelines", name, status, version)
    }

    /** A parameter-set version with one SELECT parameter per selector template pin, in order. */
    fun parameterSet(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        selectors: List<Pair<String, Int>>,
    ) {
        val parameters =
            selectors.withIndex().joinToString(",", "[", "]") { (index, pin) ->
                """{"name":"p$index","label":"P$index","type":"INTEGER","kind":"SELECT",""" +
                    """"source":{"template":{"id":"${pin.first}","version":${pin.second}},"datasource":"warehouse"}}"""
            }
        parameterSetBody(name, version, status) { """{"display_name":"$name","parameters":$parameters}""" }
    }

    /** A parameter-set version whose stored body is exactly [body] — for selectors no [parameterSet] shape covers. */
    fun parameterSetBody(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        body: () -> String,
    ) = artifactBody("parameter_sets", "parameter_set_versions", "parameter_set_id", name, version, status, body)

    /** A visualization version whose transform pins [transform]. */
    fun visualization(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        transform: Pair<String, Int>,
    ) {
        val body =
            VisualizationBody(
                displayName = name,
                renderer = RendererSpec(RendererKind.TABLE, "1"),
                inputs = emptyMap(),
                transform = TransformBinding(ArtifactRef(transform.first, transform.second), emptyMap()),
                config = JsonNodeFactory.instance.objectNode(),
            )
        artifactBody("visualizations", "visualization_versions", "visualization_id", name, version, status) {
            BodyCodec.Visualization.write(body)
        }
    }

    /** A dashboard version with one source per pipeline pin, in order. */
    fun dashboard(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        pipelines: List<Pair<String, Int>>,
    ) {
        val body =
            DashboardBody(
                displayName = name,
                parameterSet = null,
                sources =
                    pipelines.withIndex().map { (index, pin) ->
                        DashboardSource("s$index", ArtifactRef(pin.first, pin.second), emptyMap())
                    },
                visualizations = emptyList(),
                layout = DashboardLayout(),
            )
        artifactBody("dashboards", "dashboard_versions", "dashboard_id", name, version, status) { BodyCodec.Dashboard.write(body) }
    }

    private fun owner() = mapOf("owner" to userId, "ws" to workspaceId)

    /** An artifact version of [entities] whose stored body is exactly [body] (the entity row is created on first use). */
    @Suppress("LongParameterList") // the three table names of one family, then the row
    fun artifactBody(
        entities: String,
        versions: String,
        foreignKey: String,
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        body: () -> String,
    ) {
        jdbc.update(
            "INSERT INTO $entities (id, workspace_id, name, display_name, current_version, created_by)" +
                " VALUES (:id, :ws, :name, :name, NULL, :owner) ON CONFLICT (workspace_id, name) DO NOTHING",
            mapOf("id" to idOf(entities, name)) + owner() + mapOf("name" to name),
        )
        jdbc.update(
            "INSERT INTO $versions ($foreignKey, version, body_json, status, body_hash, created_by, released_by, released_at," +
                " discarded_by, discarded_at)" +
                " SELECT e.id, :version, CAST(:body AS jsonb), :status, :hash, :owner, $RELEASED_BY, $RELEASED_AT, $DISCARDED_BY," +
                " $DISCARDED_AT FROM $entities e WHERE e.name = :name AND e.workspace_id = :ws",
            mapOf("name" to name, "version" to version, "body" to body(), "status" to status.name, "hash" to "a-$name-$version") + owner(),
        )
        pointer(entities, name, status, version)
    }

    private fun pointer(
        entities: String,
        name: String,
        status: PipelineVersionStatus,
        version: Int,
    ) {
        if (status != PipelineVersionStatus.RELEASED) return
        jdbc.update(
            "UPDATE $entities SET current_version = GREATEST(COALESCE(current_version, 0), :version)" +
                " WHERE name = :name AND workspace_id = :ws",
            mapOf("version" to version, "name" to name, "ws" to workspaceId),
        )
    }

    companion object {
        /** The fixed id of [name] in [family] — stable across runs and code versions. */
        fun idOf(
            family: String,
            name: String,
        ): UUID = UUID.nameUUIDFromBytes("$family:$name".toByteArray())

        private const val RELEASED_BY = "CASE WHEN :status <> 'DRAFT' THEN CAST(:owner AS uuid) END"
        private const val RELEASED_AT = "CASE WHEN :status <> 'DRAFT' THEN NOW() END"
        private const val DISCARDED_BY = "CASE WHEN :status = 'DISCARDED' THEN CAST(:owner AS uuid) END"
        private const val DISCARDED_AT = "CASE WHEN :status = 'DISCARDED' THEN NOW() END"
    }
}
