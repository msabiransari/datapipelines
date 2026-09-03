package co.datapipelines.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.DatapipelinesApplication
import co.datapipelines.SharedPostgres
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import java.security.SecureRandom
import java.util.Base64

/**
 * 050/R2's §7 rule, demonstrated at REAL startup — the unit matrix in [ConfigValidatorTest]
 * proves the pure function; this boots the actual application against live containers and
 * watches what the operator sees:
 *
 * - **alias alone** → the application STARTS, and exactly one WARN names the new key and the
 *   value in effect;
 * - **both keys set and differing** → the context REFUSES to start, naming both keys.
 *
 * Local accounts (`datapipelines.auth.local.enabled=true`) satisfy §7's auth-method rule with
 * no OIDC provider — the minimal honest boot. The WARN is captured through an
 * [ApplicationContextInitializer]: Spring's logging re-initialization during `run` detaches
 * appenders added before it, while an initializer runs after logging settles and before any
 * bean — exactly around the validator's `@PostConstruct`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutorConcurrencyAliasStartupTest {
    @Test
    fun `the alias alone boots with exactly one WARN naming the new key and the value in effect`() {
        val probe = AliasWarnProbe()
        val context =
            SpringApplicationBuilder(DatapipelinesApplication::class.java)
                .initializers(probe)
                .run(*(baseArgs() + "--datapipelines.executor.max-concurrent-executions-global=150").toTypedArray())
        try {
            // The alias alone must NOT refuse: the application is up and serving.
            check(context.isActive)
        } finally {
            context.close()
            probe.detach()
        }

        val aliasWarns =
            probe.events
                .filter { it.level == Level.WARN }
                .map { it.formattedMessage }
                .filter { it.contains("executor_concurrency_alias_used") }
        aliasWarns.single() shouldContain "max-concurrent-executions-per-instance"
        aliasWarns.single() shouldContain "(150) is in effect"
    }

    @Test
    fun `both keys set and differing refuses startup - both keys named in the failure`() {
        // Spring wraps the validator's @PostConstruct refusal in a BeanCreationException; the
        // §7 message rides its cause chain verbatim — the operator sees the full text.
        val failure =
            shouldThrow<org.springframework.beans.factory.BeanCreationException> {
                SpringApplicationBuilder(DatapipelinesApplication::class.java)
                    .run(
                        *(
                            baseArgs() +
                                "--datapipelines.executor.max-concurrent-executions-global=150" +
                                "--datapipelines.executor.max-concurrent-executions-per-instance=200"
                        ).toTypedArray(),
                    ).close()
            }

        val text = failure.message.orEmpty() + " " + (failure.cause?.message.orEmpty())
        text.shouldContain("startup refused")
        text.shouldContain("max-concurrent-executions-global (150)")
        text.shouldContain("max-concurrent-executions-per-instance (200)")
    }

    private fun baseArgs(): List<String> =
        listOf(
            "--server.port=0",
            "--management.server.port=0",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.data.redis.host=${SharedRedis.host}",
            "--spring.data.redis.port=${SharedRedis.port}",
            "--spring.data.redis.password=",
            "--datapipelines.redis.host=${SharedRedis.host}",
            "--datapipelines.redis.port=${SharedRedis.port}",
            "--datapipelines.jwt.secret=$SECRET",
            "--datapipelines.db.encryption-key=$SECRET",
            "--datapipelines.auth.local.enabled=true",
        )

    /** Attaches a Logback appender around bean creation — see the class KDoc. */
    private class AliasWarnProbe : ApplicationContextInitializer<ConfigurableApplicationContext> {
        val events = mutableListOf<ILoggingEvent>()

        private val appender =
            object : ListAppender<ILoggingEvent>() {
                override fun append(eventObject: ILoggingEvent) {
                    events += eventObject
                }
            }

        private val logger = LoggerFactory.getLogger(ConfigValidator::class.java) as Logger

        override fun initialize(applicationContext: ConfigurableApplicationContext) {
            logger.addAppender(appender)
            appender.start()
        }

        fun detach() {
            logger.detachAppender(appender)
        }
    }

    private companion object {
        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedPostgres.postgres
    }
}
