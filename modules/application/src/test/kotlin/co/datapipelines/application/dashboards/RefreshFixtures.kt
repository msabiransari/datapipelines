package co.datapipelines.application.dashboards

import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.ActionScope
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardLayout
import co.datapipelines.visualization.DashboardObjectType
import co.datapipelines.visualization.DashboardSource
import co.datapipelines.visualization.InputColumn
import co.datapipelines.visualization.InputContract
import co.datapipelines.visualization.InputMapping
import co.datapipelines.visualization.LiteralValue
import co.datapipelines.visualization.ParameterBinding
import co.datapipelines.visualization.RendererKind
import co.datapipelines.visualization.RendererSpec
import co.datapipelines.visualization.TransformBinding
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationOccurrence
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Builders for the refresh tests — small, explicit documents in the module's own model classes, no JSON. The shapes
 * mirror the spec's worked example: `revenue` feeds `v_revenue`, and a second visualization shares its execution.
 */
internal object RefreshFixtures {
    val WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    val USER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    val NOW: Instant = Instant.parse("2026-09-29T10:00:00Z")
    private val nodes = JsonNodeFactory.instance

    fun ref(
        name: String,
        version: Int = 1,
    ) = ArtifactRef(name, version)

    fun text(value: String): JsonNode = nodes.textNode(value)

    fun number(value: Int): JsonNode = nodes.numberNode(value)

    fun source(
        name: String,
        pipeline: String = "p/$name",
        parameters: Map<String, ParameterBinding> = emptyMap(),
    ) = DashboardSource(name, ref(pipeline), parameters)

    fun bindParameter(name: String) = ParameterBinding(parameter = name)

    fun bindLiteral(value: JsonNode) = ParameterBinding(value = value)

    fun occurrence(
        name: String,
        visualization: String = "v/$name",
        inputs: Map<String, String>,
        timeoutSeconds: Int? = null,
    ) = VisualizationOccurrence(
        name = name,
        type = DashboardObjectType.VISUALIZATION,
        visualization = ref(visualization),
        inputs = inputs.mapValues { InputMapping(it.value) },
        timeoutSeconds = timeoutSeconds,
    )

    fun dashboard(
        sources: List<DashboardSource>,
        visualizations: List<VisualizationOccurrence>,
        overrides: Map<String, Map<String, LiteralValue>> = emptyMap(),
    ) = DashboardBody(
        displayName = "Dashboard",
        sources = sources,
        visualizations = visualizations,
        outgoingOverrides = overrides,
        layout = DashboardLayout(),
    )

    fun columns(vararg spec: Pair<String, LogicalType>) = spec.map { ColumnSchema(it.first, it.second) }

    /** A visualization with one input `main` of INTEGER `x`, bound `cells.x` ← `x`, no transform. */
    fun visualization(
        inputs: Map<String, List<InputColumn>> = mapOf("main" to listOf(InputColumn("x", LogicalType.INTEGER))),
        bindings: Map<String, String> = mapOf("cells.x" to "x"),
        transform: TransformBinding? = null,
    ) = VisualizationBody(
        displayName = "Viz",
        renderer = RendererSpec(RendererKind.TABLE, "1"),
        inputs = inputs.mapValues { InputContract(it.value) },
        transform = transform,
        config = nodes.objectNode(),
        bindings = bindings,
    )

    fun target(
        occurrence: VisualizationOccurrence,
        body: VisualizationBody = visualization(),
    ) = TargetSpec(
        name = occurrence.name,
        visualization = occurrence.visualization,
        body = body,
        sourceOfInput = occurrence.inputs.mapValues { it.value.source },
        timeoutSeconds = occurrence.timeoutSeconds,
    )

    fun plan(
        body: DashboardBody,
        values: Map<String, JsonNode?> = emptyMap(),
    ) = RefreshPlanner().plan(body, ActionScope.ALL, emptyList(), values)

    fun job(
        body: DashboardBody,
        bodies: Map<String, VisualizationBody> = emptyMap(),
        refreshId: UUID = UUID.randomUUID(),
        deadlineSeconds: Int = 60,
        values: Map<String, JsonNode?> = emptyMap(),
        slots: ExecutionSlots = ExecutionSlots(maxPerUser = 5, maxPerInstance = 100),
    ): RefreshJob {
        val plan = plan(body, values)
        val pipeline = mockk<Pipeline>()
        return RefreshJob(
            refreshId = refreshId,
            workspaceId = WORKSPACE,
            userId = USER,
            executedByKeyKind = null,
            dashboardId = UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
            dashboardVersion = 3,
            scope = ActionScope.ALL,
            instanceId = UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
            plan = plan,
            targets = body.visualizations.associate { it.name to target(it, bodies[it.name] ?: visualization()) },
            invocations =
                plan.invocations.associate {
                    it.id to
                        InvocationSpec(it.id, UUID.nameUUIDFromBytes(it.id.toByteArray()), it.pipeline.version, pipeline, it.parameters)
                },
            deadlineSeconds = deadlineSeconds,
            reservation =
                if (plan.invocations.isEmpty()) {
                    null
                } else {
                    runBlocking {
                        slots.acquireInstanceOnly(
                            plan.invocations.size,
                            Duration.ZERO,
                        )
                    }
                },
            startedAt = NOW,
        )
    }
}
