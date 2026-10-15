package co.datapipelines.web

import io.kotest.matchers.shouldBe
import io.lettuce.core.resource.ClientResources
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.autoconfigure.data.redis.RedisHealthContributorAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/** Boots the real Redis autoconfiguration alongside the house configuration, without opening a connection. */
class RedisClientAuthorityBootTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(RedisAutoConfiguration::class.java, RedisHealthContributorAutoConfiguration::class.java),
            ).withUserConfiguration(HouseRedisScan::class.java)

    @Test
    fun `framework Redis overrides cannot bypass the operator binding`() {
        val framework = listOf("spring", "data", "redis").joinToString(".")
        runner
            .withPropertyValues(
                "datapipelines.redis.host=127.0.0.1",
                "datapipelines.redis.password=",
                "$framework.host=nowhere.invalid",
                "$framework.timeout=0s",
            ).run { context ->
                context.startupFailure shouldBe null
                val factory = context.getBean(LettuceConnectionFactory::class.java)
                factory.clientConfiguration.commandTimeout shouldBe Duration.ofSeconds(2)
                factory.hostName shouldBe "127.0.0.1"
                factory.clientConfiguration.clientResources.orElseThrow() shouldBe context.getBean(ClientResources::class.java)
                context.getBeansOfType(RedisConnectionFactory::class.java).keys shouldBe setOf("redisClientConnectionFactory")
                context.getBean(StringRedisTemplate::class.java).connectionFactory shouldBe factory
                context.containsBean("redisHealthContributor") shouldBe true
            }
    }

    @Test
    fun `framework connect timeout cannot replace the operator bound`() {
        val framework = listOf("spring", "data", "redis").joinToString(".")
        runner
            .withPropertyValues("datapipelines.redis.host=127.0.0.1", "$framework.connect-timeout=9s")
            .run { context ->
                context.startupFailure shouldBe null
                val factory = context.getBean(LettuceConnectionFactory::class.java)
                factory.clientConfiguration.clientOptions
                    .orElseThrow()
                    .socketOptions.connectTimeout shouldBe Duration.ofSeconds(2)
            }
    }

    @Test
    fun `all five operator properties bind to the factory`() {
        runner
            .withPropertyValues(
                "datapipelines.redis.host=redis.internal",
                "datapipelines.redis.port=6381",
                "datapipelines.redis.password=synthetic-bind-credential",
                "datapipelines.redis.command-timeout=5s",
                "datapipelines.redis.connect-timeout=7s",
            ).run { context ->
                context.startupFailure shouldBe null
                val factory = context.getBean(LettuceConnectionFactory::class.java)
                factory.hostName shouldBe "redis.internal"
                factory.port shouldBe 6381
                factory.password shouldBe "synthetic-bind-credential"
                factory.clientConfiguration.commandTimeout shouldBe Duration.ofSeconds(5)
                factory.clientConfiguration.clientOptions
                    .orElseThrow()
                    .socketOptions.connectTimeout shouldBe Duration.ofSeconds(7)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("co.datapipelines.redis")
    class HouseRedisScan
}
