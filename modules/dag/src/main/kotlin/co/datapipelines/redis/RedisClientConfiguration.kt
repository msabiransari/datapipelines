package co.datapipelines.redis

import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.resource.ClientResources
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import java.time.Duration

/**
 * Builds the one Redis factory from the operator binding; Boot backs off its factory but keeps
 * StringRedisTemplate and the Redis health contributor.
 *
 * Reproduces Boot's enabled TimeoutOptions and 100 ms shutdown timeout. Reuses Boot's shared
 * ClientResources bean, including actuator's metrics customizer and managed shutdown.
 * Drops Boot's SSL bundle, client-name and read-from settings: no house key exists for them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisClientProperties::class)
class RedisClientConfiguration {
    /** Spring owns initialization and destruction; consumers all inject this same factory. */
    @Bean
    fun redisClientConnectionFactory(
        properties: RedisClientProperties,
        clientResources: ClientResources,
    ): LettuceConnectionFactory {
        val server = RedisStandaloneConfiguration(properties.host, properties.port)
        if (properties.password.isNotBlank()) {
            server.password = RedisPassword.of(properties.password)
        }
        val options =
            ClientOptions
                .builder()
                .timeoutOptions(TimeoutOptions.enabled())
                .socketOptions(SocketOptions.builder().connectTimeout(properties.connectTimeout).build())
                .build()
        val client =
            LettuceClientConfiguration
                .builder()
                .clientResources(clientResources)
                .commandTimeout(properties.commandTimeout)
                .shutdownTimeout(SHUTDOWN_TIMEOUT)
                .clientOptions(options)
                .build()
        return LettuceConnectionFactory(server, client)
    }

    private companion object {
        val SHUTDOWN_TIMEOUT: Duration = Duration.ofMillis(100)
    }
}
