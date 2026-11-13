package co.datapipelines.web.config

import co.datapipelines.application.dependencies.DashboardParameterSetConsumers
import co.datapipelines.application.dependencies.DashboardPipelineConsumers
import co.datapipelines.application.dependencies.RegistryIncomingRows
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.parameters.ParameterSetConsumers
import co.datapipelines.pipeline.PipelineVersionConsumers
import co.datapipelines.visualization.ArtifactDependents
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The reverse arrows' wiring (#320) — the scans behind the purge and discard guards of the templates, pipelines and
 * parameter sets that a visualization or a dashboard pins (versioning §3.5's graph rule 1; the design record §4.2).
 *
 * Its own configuration, never [VisualizationConfiguration]: that class assembles the visualization and dashboard
 * SURFACES, and this one assembles what the OTHER families' guards read. `web` is the assembling layer; the owning
 * modules see only the ports they declare.
 */
@Configuration
class DependencyGuardsConfiguration {
    /** The three reverse scans over `visualization_versions` and `dashboard_versions` — one shared instance. */
    @Bean
    fun artifactDependents(jdbc: NamedParameterJdbcTemplate): ArtifactDependents = ArtifactDependents(jdbc)

    /**
     * The pipeline aggregate's answer to "which dashboards pin this release?" (`pipeline.version.pinned`) — an adapter over
     * the pipeline family's dashboard arm (#877 §C), the registry read at the first question (no bean cycle).
     */
    @Bean
    fun pipelineVersionConsumers(dependencyFamilyRegistry: ObjectProvider<FamilyRegistry>): PipelineVersionConsumers =
        DashboardPipelineConsumers(RegistryIncomingRows(dependencyFamilyRegistry::getObject))

    /** The parameter-set aggregate's answer to the same question (`parameter.in_use`), over the set family's dashboard arm. */
    @Bean
    fun parameterSetConsumers(dependencyFamilyRegistry: ObjectProvider<FamilyRegistry>): ParameterSetConsumers =
        DashboardParameterSetConsumers(RegistryIncomingRows(dependencyFamilyRegistry::getObject))
}
