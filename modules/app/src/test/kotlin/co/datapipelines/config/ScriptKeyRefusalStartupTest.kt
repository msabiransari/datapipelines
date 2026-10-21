package co.datapipelines.config

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.SharedPostgres
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import java.security.SecureRandom
import java.util.Base64

/**
 * The script-node rename's removed-key refusal, demonstrated at REAL startup — the unit matrix
 * in [ConfigValidatorTest] proves the pure function; this boots the actual application against
 * live containers and watches what the operator sees:
 *
 * **a `datapipelines.transform.*` key set** → the context REFUSES to start, naming the stale
 * key and where the knob lives now. There is no WARN-and-continue case here (unlike the
 * executor concurrency alias): a removed key binds NOTHING, so the deployment would silently
 * run on defaults — the refusal is unconditional.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScriptKeyRefusalStartupTest {
    @Test
    fun `a datapipelines transform key set refuses startup naming the stale key and the script knob`() {
        // Spring wraps the validator's @PostConstruct refusal in a BeanCreationException; the
        // §3.28 message rides its cause chain verbatim — the operator sees the full text.
        val failure =
            shouldThrow<org.springframework.beans.factory.BeanCreationException> {
                SpringApplicationBuilder(DatapipelinesApplication::class.java)
                    .run(
                        *(
                            baseArgs() +
                                "--datapipelines.transform.pool-size=4"
                        ).toTypedArray(),
                    ).close()
            }

        var cause: Throwable? = failure
        val text = generateSequence(cause) { it.cause }.take(10).joinToString(" ") { it.message.orEmpty() }
        text.shouldContain("startup refused")
        text.shouldContain("datapipelines.transform.pool-size")
        text.shouldContain("datapipelines.script.*")
        text.shouldContain("no alias")
    }

    private fun baseArgs(): List<String> =
        listOf(
            "--server.port=0",
            "--management.server.port=0",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--datapipelines.redis.host=${SharedRedis.host}",
            "--datapipelines.redis.port=${SharedRedis.port}",
            "--datapipelines.redis.password=${SharedRedis.PASSWORD}",
            "--datapipelines.jwt.secret=$SECRET",
            "--datapipelines.db.encryption-key=$SECRET",
            "--datapipelines.auth.local.enabled=true",
        )

    private companion object {
        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedPostgres.postgres
    }
}
