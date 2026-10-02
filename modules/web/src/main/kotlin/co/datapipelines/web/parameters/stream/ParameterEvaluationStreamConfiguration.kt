package co.datapipelines.web.parameters.stream

import co.datapipelines.auth.PrincipalLiveness
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.web.config.SseProperties
import co.datapipelines.web.dashboards.runtime.RefreshStreamRegistry
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.SseJson
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The observed evaluation's stream wiring (#375) — beside its package, as the dashboard runtime's stream beans live in
 * `DashboardRuntimeConfiguration`. Kept out of `ParametersConfiguration`, which wires the ENGINE surface and is
 * loaded on its own by its wiring test; the stream needs the SSE and auth collaborators instead.
 *
 * The one per-user cap (D7) makes the evaluation registry and the refresh registry count each other: the refresh
 * registry is read LAZILY here ([ObjectProvider]), so the two beans build without a cycle.
 */
@Configuration
class ParameterEvaluationStreamConfiguration {
    @Bean
    fun parameterEvaluationStreamAuthority(
        liveness: PrincipalLiveness,
        workspaces: WorkspaceService,
        users: UserService,
    ): ParameterEvaluationStreamAuthority = ParameterEvaluationStreamAuthority(liveness, workspaces, users)

    @Bean
    fun parameterEvaluationStreamRegistry(
        properties: SseProperties,
        executionStreams: ExecutionStreamRegistry,
        refreshStreams: ObjectProvider<RefreshStreamRegistry>,
    ): ParameterEvaluationStreamRegistry =
        ParameterEvaluationStreamRegistry(
            properties,
            otherStreams = { userId -> executionStreams.activeStreamsFor(userId) + refreshStreams.getObject().activeStreamsFor(userId) },
            mapper = SseJson.mapper,
        )
}
