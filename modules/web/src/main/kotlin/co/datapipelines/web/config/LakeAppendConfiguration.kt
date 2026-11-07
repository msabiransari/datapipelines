package co.datapipelines.web.config

import co.datapipelines.datasources.CredentialKind
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.LakeDialectAdapter
import co.datapipelines.datasources.PoolInvalidationPublisher
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.append.LakeAppendHeartbeat
import co.datapipelines.lake.append.LakeAppendWatch
import co.datapipelines.lake.delivery.LakeProjectionInvalidation
import co.datapipelines.lake.delivery.LakeStagingStorage
import co.datapipelines.persistence.FailureShape
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Production storage setup, lease renewal and committed projection invalidation. */
@Configuration
@ConditionalOnProperty(prefix = LakeProperties.PREFIX, name = ["enabled"], havingValue = "true")
class LakeAppendConfiguration {
    @Bean
    fun lakeStagingStorage(
        properties: LakeProperties,
        environment: Environment,
    ): LakeStagingStorage {
        val storage = properties.storage
        val datasource =
            Datasource(
                name = properties.name,
                displayName = properties.name,
                dialect = Dialect.LAKE,
                jdbcUrl = "jdbc:duckdb:",
                username = storage.accessKey.takeIf { storage.hasStaticCredentials },
                credentialKind = if (storage.hasStaticCredentials) CredentialKind.PASSWORD else CredentialKind.NONE,
                secret = storage.secretKey.takeIf { storage.hasStaticCredentials },
                properties =
                    DatasourceProperties(
                        dialect =
                            buildMap {
                                put("catalog.kind", "s3")
                                put("region", storage.region)
                                put("url_style", if (storage.pathStyle) "path" else "vhost")
                                if (storage.endpoint.isNotBlank()) put("endpoint", storage.endpoint)
                            },
                    ),
            )
        val adapter = LakeDialectAdapter(environment.getProperty("datapipelines.duckdb.extension-directory")?.takeIf { it.isNotBlank() })
        val sql = adapter.storageInit(datasource)
        return LakeStagingStorage { connection, deadline, execution ->
            sql.forEach { statementSql ->
                val remaining = Duration.between(Instant.now(), deadline)
                if (remaining.isNegative || remaining.isZero) throw lostAuthority()
                connection.createStatement().use { statement ->
                    statement.queryTimeout =
                        remaining.seconds
                            .coerceAtLeast(1)
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()
                    execution.execute(statement) { statement.execute(statementSql) }
                }
            }
        }
    }

    @Bean
    fun lakeAppendHeartbeat(scope: WebSurfaceConfiguration.ExecutionCoroutineScope): LakeAppendHeartbeat =
        LakeAppendHeartbeat { lease, deadline, renew ->
            val valid = AtomicBoolean(renew())
            val job =
                scope.launch(Dispatchers.IO) {
                    while (isActive && valid.get()) {
                        delay(HEARTBEAT_MILLIS)
                        valid.set(Instant.now().isBefore(deadline) && renewSafely(lease.operationId, renew))
                    }
                }
            object : LakeAppendWatch {
                override fun checkActive() {
                    if (!valid.get() || !Instant.now().isBefore(deadline)) throw lostAuthority()
                }

                override fun close() {
                    job.cancel()
                }
            }
        }

    @Bean
    fun lakeProjectionInvalidation(
        registry: DatasourceRegistry,
        publisher: PoolInvalidationPublisher,
    ): LakeProjectionInvalidation =
        LakeProjectionInvalidation { name ->
            registry.retirePool(name)
            publisher.publish(name)
        }

    @Suppress("TooGenericExceptionCaught") // Failed renewal loses authority; no endpoint or credential-bearing exception escapes.
    private fun renewSafely(operationId: UUID, renew: () -> Boolean): Boolean =
        try {
            renew()
        } catch (failure: Exception) {
            log.warn(
                "event=lake.append.heartbeat_failed operation_id={} cause={} sql_state={}",
                operationId,
                FailureShape.cause(failure),
                FailureShape.sqlState(failure),
            )
            false
        }

    private companion object {
        const val HEARTBEAT_MILLIS = 30_000L
        val log = LoggerFactory.getLogger(LakeAppendConfiguration::class.java)

        fun lostAuthority() =
            DatapipelinesException(Lake.APPEND_AUTHORITY_LOST, "The append lease or admitted deadline is no longer valid.")
    }
}
