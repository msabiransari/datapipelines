package co.datapipelines.redis

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.lettuce.core.resource.DefaultClientResources
import org.junit.jupiter.api.Test
import java.time.Duration

/** Inspects the built client, so losing a builder option fails without needing a Redis server. */
class RedisClientConfigurationTest {
    @Test
    fun `factory carries operator values and the three retained Boot behaviours`() {
        val resources = DefaultClientResources.create()
        try {
            val properties =
                RedisClientProperties(
                    host = "redis.internal",
                    port = 6381,
                    password = "opaque-test-credential",
                    commandTimeout = Duration.ofSeconds(3),
                    connectTimeout = Duration.ofSeconds(4),
                )
            val factory = RedisClientConfiguration().redisClientConnectionFactory(properties, resources)
            factory.hostName shouldBe properties.host
            factory.port shouldBe properties.port
            factory.password shouldBe properties.password
            val client = factory.clientConfiguration
            client.commandTimeout shouldBe properties.commandTimeout
            client.clientOptions
                .orElseThrow()
                .socketOptions.connectTimeout shouldBe properties.connectTimeout
            client.clientOptions
                .orElseThrow()
                .timeoutOptions.isTimeoutCommands shouldBe true
            client.shutdownTimeout shouldBe Duration.ofMillis(100)
            client.clientResources.orElseThrow() shouldBe resources
            client.isUseSsl shouldBe false
            client.clientName.isEmpty shouldBe true
            client.readFrom.isEmpty shouldBe true
        } finally {
            resources.shutdown().get()
        }
    }

    @Test
    fun `blank password leaves authentication unset`() {
        val resources = DefaultClientResources.create()
        try {
            listOf("", "  ").forEach { password ->
                val factory =
                    RedisClientConfiguration().redisClientConnectionFactory(
                        RedisClientProperties(host = "localhost", password = password),
                        resources,
                    )
                factory.standaloneConfiguration.password.isPresent shouldBe false
            }
        } finally {
            resources.shutdown().get()
        }
    }

    @Test
    fun `property representations never print the credential`() {
        val password = "unique-redis-test-credential"
        val properties = RedisClientProperties(host = "localhost", password = password)
        properties.toString() shouldNotContain password
        properties.copy(port = 6380).toString() shouldNotContain password
    }

    @Test
    fun `port requires a valid TCP port without exposing the credential`() {
        listOf(0, -1, 65536).forEach { port ->
            val failure =
                shouldThrow<IllegalArgumentException> {
                    RedisClientProperties(host = "localhost", port = port, password = "private-credential")
                }
            failure.message shouldBe "datapipelines.redis.port must be in 1..65535"
        }
        RedisClientProperties(host = "localhost", port = 1).port shouldBe 1
        RedisClientProperties(host = "localhost", port = 65535).port shouldBe 65535
    }
}
