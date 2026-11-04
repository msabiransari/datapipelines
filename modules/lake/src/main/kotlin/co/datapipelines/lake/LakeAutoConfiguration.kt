package co.datapipelines.lake

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import software.amazon.awssdk.services.s3.S3Client
import java.time.Duration

/**
 * The managed lake's beans (module-structure §5.21, §8.4 — declared here, never by stereotype annotations).
 *
 * Inert unless `datapipelines.lake.enabled=true`: with the default `false` no bean of this module exists — no
 * properties binding, no S3 client, no catalog, no boot check — so a deployment without a lake behaves exactly as
 * before the module existed (`LakeAutoConfigurationTest`). `app`'s `LakeRules` validates the keys before this
 * configuration is reached.
 *
 * The catalog database is DP's own metadata Postgres: the URL and credentials Spring Boot bound for the
 * primary `DataSource` ([DataSourceProperties]). Iceberg's catalog connections are opened separately, one fenced
 * pool per lease, so they never enter (or take from) the application's Hikari pool.
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class, JdbcTemplateAutoConfiguration::class])
@ConditionalOnProperty(prefix = LakeProperties.PREFIX, name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(LakeProperties::class)
class LakeAutoConfiguration {
    /** A takeover's lock wait is bounded by the catalog's `lock-timeout-seconds`, as the catalog sessions are (#812). */
    @Bean
    fun lakeLeases(
        jdbc: JdbcTemplate,
        properties: LakeProperties,
    ): LakeLeases = LakeLeases(jdbc, lockTimeout = Duration.ofSeconds(properties.catalog.lockTimeoutSeconds))

    @Bean
    fun lakeCatalogs(
        properties: LakeProperties,
        dataSource: DataSourceProperties,
    ): LakeCatalogs =
        LakeCatalogs(
            properties,
            CatalogDatabase(
                url = dataSource.determineUrl(),
                username = dataSource.determineUsername().orEmpty(),
                password = dataSource.determinePassword().orEmpty(),
            ),
        )

    /** The boot check's S3 client; closed with the context. Not shared: `S3FileIO` builds its own per catalog. */
    @Bean(destroyMethod = "close")
    fun lakeS3Client(properties: LakeProperties): S3Client = LakeS3Clients.build(properties.storage)

    @Bean
    fun lakeBootCheck(
        jdbc: JdbcTemplate,
        lakeS3Client: S3Client,
        properties: LakeProperties,
    ): LakeBootCheck = LakeBootCheck(jdbc, lakeS3Client, properties.storage)
}
