package co.datapipelines.lake.profiles

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.LakeAutoConfiguration
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import javax.sql.DataSource

/**
 * The lake profiles' beans (#956): the profile service, the pause service and the start-up seed `web` runs after
 * Flyway. Inert unless `datapipelines.lake.enabled=true`, like [LakeAutoConfiguration]. No handler or tool reaches the
 * two services before P8 (cut L6). The transactions run on the application's one [PlatformTransactionManager], so each
 * audit row is part of its change's transaction.
 */
@AutoConfiguration(after = [LakeAutoConfiguration::class])
@ConditionalOnProperty(prefix = LakeProperties.PREFIX, name = ["enabled"], havingValue = "true")
class LakeProfilesAutoConfiguration {
    @Bean
    fun lakeProfiles(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        audit: AuditEventSink,
    ): LakeProfiles = LakeProfiles.over(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), audit)

    @Bean
    fun lakeTablePauses(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        audit: AuditEventSink,
        lakeLeases: LakeLeases,
    ): LakeTablePauses =
        LakeTablePauses(repository(dataSource), TransactionTemplate(transactionManager), audit) { lakeLeases.catalogNowPlus(Duration.ZERO) }

    /** The one place the two `datapipelines.lake.maintenance.*` values are handed on (to the seed, D3). */
    @Bean
    fun lakeProfileSeed(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        properties: LakeProperties,
    ): LakeProfileSeed = LakeProfileSeed(repository(dataSource), TransactionTemplate(transactionManager), properties.maintenance)

    private fun repository(dataSource: DataSource) = LakeProfileRepository(NamedParameterJdbcTemplate(dataSource))
}
