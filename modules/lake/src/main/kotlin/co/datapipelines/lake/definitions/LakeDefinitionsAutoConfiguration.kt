package co.datapipelines.lake.definitions

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.LakeAutoConfiguration
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeProperties
import co.datapipelines.pipeline.AuthoringGuard
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import javax.sql.DataSource

/**
 * The managed lake definitions' beans (P3, module-structure §5.21): the two repositories, the applier and the
 * lifecycle service. Inert unless `datapipelines.lake.enabled=true`, like [LakeAutoConfiguration], after which it
 * runs. The transactions run on the application's one [PlatformTransactionManager] (`metadataTransactionManager`,
 * app's `TransactionConfiguration`), so the audit row written through [AuditEventSink] is part of each lifecycle
 * step's transaction.
 */
@AutoConfiguration(after = [LakeAutoConfiguration::class])
@ConditionalOnProperty(prefix = LakeProperties.PREFIX, name = ["enabled"], havingValue = "true")
class LakeDefinitionsAutoConfiguration {
    @Bean
    fun lakeDefinitionRepository(dataSource: DataSource): LakeDefinitionRepository =
        LakeDefinitionRepository(NamedParameterJdbcTemplate(dataSource))

    @Bean
    fun lakeApplyRepository(dataSource: DataSource): LakeApplyRepository = LakeApplyRepository(NamedParameterJdbcTemplate(dataSource))

    @Bean
    fun lakeDefinitionApplier(
        transactionManager: PlatformTransactionManager,
        lakeDefinitionRepository: LakeDefinitionRepository,
        lakeApplyRepository: LakeApplyRepository,
        lakeLeases: LakeLeases,
        lakeCatalogs: LakeCatalogs,
        properties: LakeProperties,
        audit: AuditEventSink,
    ): LakeDefinitionApplier =
        LakeDefinitionApplier(
            definitions = lakeDefinitionRepository,
            applies = lakeApplyRepository,
            leases = LakeApplyLeases(lakeLeases, Duration.ofSeconds(properties.admission.leaseWaitSeconds)),
            catalogs = lakeCatalogs,
            lakeName = properties.name,
            tx = TransactionTemplate(transactionManager),
            audit = audit,
        )

    @Bean
    fun lakeDefinitionService(
        transactionManager: PlatformTransactionManager,
        lakeDefinitionRepository: LakeDefinitionRepository,
        lakeDefinitionApplier: LakeDefinitionApplier,
        lakeLeases: LakeLeases,
        properties: LakeProperties,
        audit: AuditEventSink,
        environment: Environment,
    ): LakeDefinitionService =
        LakeDefinitionService(
            definitions = lakeDefinitionRepository,
            applier = lakeDefinitionApplier,
            leases = lakeLeases,
            jobTimeout = properties.admission.jobTimeout,
            policyJson = LakeTablePolicy.defaultsJson(properties),
            tx = TransactionTemplate(transactionManager),
            audit = audit,
            authoring = AuthoringGuard.from(environment),
        )
}
