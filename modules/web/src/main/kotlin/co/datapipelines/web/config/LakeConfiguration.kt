package co.datapipelines.web.config

import co.datapipelines.application.datasources.LakeTableMutationGate
import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.application.datasources.LakeTableRepository
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.PoolInvalidationPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The dp-lake catalog's beans (metadata-db §4.15, round 089 §A) — a sibling of
 * [DomainConfiguration]'s datasource wiring, kept apart because that class is at the
 * house function-density ceiling.
 */
@Configuration
class LakeConfiguration {
    /** The dp-lake catalog's rows. */
    @Bean
    fun lakeTableRepository(jdbc: NamedParameterJdbcTemplate): LakeTableRepository = LakeTableRepository(jdbc)

    /**
     * The ONE validated lake-table path, shared by the REST `/tables` endpoints and the
     * `lake_tables_*` MCP tools (049's principle). The D8 mutation gate is the same
     * [co.datapipelines.web.datasources.DatasourceWorkspaceRules] instance the datasource CUD
     * endpoints consult, passed as a method reference because `application` sits below `web`
     * and cannot import the rules — mutating a global datasource's registry is admin-only,
     * exactly as mutating the datasource itself is. The invalidation publisher is the same
     * §5.7 channel the datasource registry publishes pool evictions on.
     */
    @Bean
    fun lakeTableRegistryService(
        datasources: DatasourceRegistry,
        lakeTables: LakeTableRepository,
        invalidation: PoolInvalidationPublisher,
        rules: co.datapipelines.web.datasources.DatasourceWorkspaceRules,
    ): LakeTableRegistryService =
        LakeTableRegistryService(
            datasources = datasources,
            tables = lakeTables,
            invalidation = invalidation,
            mutationGate = LakeTableMutationGate { principal, datasource ->
                rules.requireGlobalMutationAllowed(principal, datasource, datasource.name)
            },
        )
}
