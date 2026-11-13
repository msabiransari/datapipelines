package co.datapipelines.application.dependencies

import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.BodyCodec
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardLayout
import co.datapipelines.visualization.DashboardObjectType
import co.datapipelines.visualization.DashboardSource
import co.datapipelines.visualization.ParameterBinding
import co.datapipelines.visualization.RendererKind
import co.datapipelines.visualization.RendererSpec
import co.datapipelines.visualization.TransformBinding
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationOccurrence
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * SQL seeding of the five artifact families for the #873 release-provider tests on a real PostgreSQL (one user, one
 * workspace). Every version row carries the lifecycle stamps the schema's CHECKs require, and every RELEASED row moves its
 * entity's `current_version` to the highest released number — the state the shipped verbs leave. Bodies are the
 * reference-bearing minimum: the providers read references only, never validity.
 *
 * A pin is `name to version`; a template's name is its id.
 */
class ReleaseArtifactSeed(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    var userId: UUID = UUID.randomUUID()
        private set
    var workspaceId: UUID = UUID.randomUUID()
        private set

    /** Empties the metadata (every family cascades from `users`) and seeds a fresh user and workspace. */
    fun reset() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to workspaceId, "name" to "w_${workspaceId.toString().replace("-", "")}", "owner" to userId),
        )
    }

    fun identity(
        family: ReleaseFamily,
        name: String,
        version: Int,
    ) = ReleaseNodeIdentity(workspaceId, family, name, version)

    /** A template version importing [imports] in order (alias [aliases]`[index]`, by default `i<index>`). */
    fun template(
        name: String,
        version: Int,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
        imports: List<Pair<String, Int>> = emptyList(),
        library: Boolean = false,
        aliases: List<String> = imports.indices.map { "i$it" },
    ) {
        jdbc.update(
            "INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)" +
                " VALUES (:name, 'T', '', NULL, :ws, :owner) ON CONFLICT (workspace_id, name) DO NOTHING",
            mapOf("name" to name, "ws" to workspaceId, "owner" to userId),
        )
        val importsJson =
            imports.withIndex().joinToString(",", "[", "]") { (index, pin) ->
                """{"id":"${pin.first}","version":${pin.second},"alias":"${aliases[index]}"}"""
            }
        jdbc.update(
            "INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, status, body_hash," +
                " created_by, released_by, released_at, discarded_by, discarded_at)" +
                " SELECT t.id, :version, 'freemarker', 'POSTGRES', :library, CAST(:imports AS jsonb), 'SELECT 1', :status, :hash," +
                " :owner, $RELEASED_BY, $RELEASED_AT, $DISCARDED_BY, $DISCARDED_AT" +
                " FROM templates t WHERE t.name = :name AND t.workspace_id = :ws",
            mapOf(
                "name" to name,
                "version" to version,
                "library" to library,
                "imports" to importsJson,
                "status" to status.name,
                "hash" to "t-$name-$version",
                "ws" to workspaceId,
                "owner" to userId,
            ),
        )
        pointer("templates", name, status, version)
    }

    /** A parameter-set version with one SELECT parameter per selector template pin, in order. */
    fun parameterSet(
        name: String,
        version: Int,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
        selectors: List<Pair<String, Int>> = emptyList(),
        parameterNames: List<String> = selectors.indices.map { "p$it" },
    ) {
        val parameters =
            selectors.withIndex().joinToString(",", "[", "]") { (index, pin) ->
                """{"name":"${parameterNames[index]}","label":"P$index","type":"INTEGER","kind":"SELECT",""" +
                    """"source":{"template":{"id":"${pin.first}","version":${pin.second}},"datasource":"warehouse"}}"""
            }
        artifact("parameter_sets", "parameter_set_versions", "parameter_set_id", name, version, status) {
            """{"display_name":"$name","parameters":$parameters}"""
        }
    }

    /** A visualization version whose transform pins [transform], or a transform-free chart. */
    fun visualization(
        name: String,
        version: Int,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
        transform: Pair<String, Int>? = null,
    ) {
        val body =
            VisualizationBody(
                displayName = name,
                renderer = RendererSpec(RendererKind.TABLE, "1"),
                inputs = emptyMap(),
                transform = transform?.let { TransformBinding(ArtifactRef(it.first, it.second), emptyMap()) },
                config = JsonNodeFactory.instance.objectNode(),
            )
        artifact("visualizations", "visualization_versions", "visualization_id", name, version, status) {
            BodyCodec.Visualization.write(body)
        }
    }

    /**
     * A dashboard version: its set, one source per pipeline (binding [bound] set parameters and [literals] to the literal
     * `1`), one occurrence per visualization. Written past the save validator: a bound name the set lacks is stored as is.
     */
    fun dashboard(
        name: String,
        version: Int,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
        set: Pair<String, Int>? = null,
        pipelines: List<Pair<String, Int>> = emptyList(),
        visualizations: List<Pair<String, Int>> = emptyList(),
        bound: List<String> = emptyList(),
        literals: List<String> = emptyList(),
    ) {
        val body =
            DashboardBody(
                displayName = name,
                parameterSet = set?.let { ArtifactRef(it.first, it.second) },
                sources =
                    pipelines.withIndex().map { (index, pin) ->
                        DashboardSource(
                            "s$index",
                            ArtifactRef(pin.first, pin.second),
                            bound.associateWith { ParameterBinding(parameter = it) } +
                                literals.associateWith { ParameterBinding(value = JsonNodeFactory.instance.numberNode(1)) },
                        )
                    },
                visualizations =
                    visualizations.withIndex().map { (index, pin) ->
                        VisualizationOccurrence("v$index", DashboardObjectType.VISUALIZATION, ArtifactRef(pin.first, pin.second))
                    },
                layout = DashboardLayout(),
            )
        artifact("dashboards", "dashboard_versions", "dashboard_id", name, version, status) { BodyCodec.Dashboard.write(body) }
    }

    /**
     * A pipeline version with one DQL node per template pin, each reading datasource [source], then one PIPELINE node per
     * child pin (output to tempdb), in order.
     */
    fun pipeline(
        name: String,
        version: Int,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
        templates: List<Pair<String, Int>> = emptyList(),
        children: List<Pair<String, Int>> = emptyList(),
        source: String = "pg",
    ) {
        pipelineNodes(
            name,
            version,
            status,
            templates.withIndex().map { (index, pin) ->
                Node(
                    id = "n$index",
                    description = "",
                    type = NodeType.DQL,
                    source = source,
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
                },
        )
    }

    /** A pipeline version whose body holds exactly [nodes] (#877: any node shape the inverse tests need). */
    fun pipelineNodes(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        nodes: List<Node>,
    ) {
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES (:id, :name, 'P', :owner, NULL, :ws) ON CONFLICT (workspace_id, name) DO NOTHING",
            mapOf("id" to UUID.randomUUID(), "name" to name, "owner" to userId, "ws" to workspaceId),
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
                "owner" to userId,
                "ws" to workspaceId,
            ),
        )
        pointer("pipelines", name, status, version)
    }

    /**
     * A test run of visualization [name]@[version] as the session service stores it (V42): [bodyHash] is the hash it ran
     * against, [startedSecondsAgo] orders runs (the latest is the one judged), a RUNNING row has no `completed_at`.
     */
    fun testRun(
        name: String,
        version: Int,
        status: String,
        bodyHash: String = "a-$name-$version",
        expiresInSeconds: Long = HOUR_SECONDS,
        startedSecondsAgo: Long = 0,
    ) {
        jdbc.update(
            "INSERT INTO visualization_test_runs (id, visualization_id, version, body_hash, session_id, expires_at, started_by," +
                " started_at, completed_at, status)" +
                " SELECT :id, v.id, :version, :hash, :session, NOW() + make_interval(secs => :expires)," +
                " :owner, NOW() - make_interval(secs => :ago)," +
                " CASE WHEN :status = 'RUNNING' THEN NULL ELSE NOW() END, :status" +
                " FROM visualizations v WHERE v.name = :name AND v.workspace_id = :ws",
            mapOf(
                "id" to UUID.randomUUID(),
                "version" to version,
                "hash" to bodyHash,
                "session" to UUID.randomUUID(),
                "expires" to expiresInSeconds.toDouble(),
                "owner" to userId,
                "ago" to startedSecondsAgo.toDouble(),
                "status" to status,
                "name" to name,
                "ws" to workspaceId,
            ),
        )
    }

    private fun artifact(
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
            mapOf("id" to UUID.randomUUID(), "ws" to workspaceId, "name" to name, "owner" to userId),
        )
        jdbc.update(
            "INSERT INTO $versions ($foreignKey, version, body_json, status, body_hash, created_by, released_by, released_at," +
                " discarded_by, discarded_at)" +
                " SELECT e.id, :version, CAST(:body AS jsonb), :status, :hash, :owner, $RELEASED_BY, $RELEASED_AT, $DISCARDED_BY," +
                " $DISCARDED_AT FROM $entities e WHERE e.name = :name AND e.workspace_id = :ws",
            mapOf(
                "name" to name,
                "version" to version,
                "body" to body(),
                "status" to status.name,
                "hash" to "a-$name-$version",
                "owner" to userId,
                "ws" to workspaceId,
            ),
        )
        pointer(entities, name, status, version)
    }

    /** A RELEASED version moves the entity pointer to the highest released number (D60). */
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

    private companion object {
        const val HOUR_SECONDS = 3600L
        const val RELEASED_BY = "CASE WHEN :status <> 'DRAFT' THEN CAST(:owner AS uuid) END"
        const val RELEASED_AT = "CASE WHEN :status <> 'DRAFT' THEN NOW() END"
        const val DISCARDED_BY = "CASE WHEN :status = 'DISCARDED' THEN CAST(:owner AS uuid) END"
        const val DISCARDED_AT = "CASE WHEN :status = 'DISCARDED' THEN NOW() END"
    }
}
