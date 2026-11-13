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
        // The validator's @PostConstruct refusal reaches the caller wrapped by whichever bean pulled it
        // first — since #804 R2 the key consumers depend on it, so that is web-server creation pulling
        // the staging factory. The §3.28 report is the ROOT of the chain, verbatim — the operator sees it.
        val failure =
            shouldThrow<Exception> {
                SpringApplicationBuilder(DatapipelinesApplication::class.java)
                    .run(
                        *(
                            baseArgs() +
                                "--datapipelines.transform.pool-size=4"
                        ).toTypedArray(),
                    ).close()
            }

        val root = generateSequence<Throwable>(failure) { it.cause }.last()
        check(root is IllegalStateException) { "the boot failed, but not on the §7 report: $root" }
        val text = root.message.orEmpty()
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
