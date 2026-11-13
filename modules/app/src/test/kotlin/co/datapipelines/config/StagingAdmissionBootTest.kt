package co.datapipelines.config

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.SharedPostgres
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import java.security.SecureRandom
import java.util.Base64

/**
 * #804 R2 — the DuckDB staging admission refused at a REAL application start with the lake DISABLED, by the
 * validator's catalogued §7 report and by nothing else. [StagingAdmissionStartupTest] pins the report's content
 * against a bare environment; this boots `DatapipelinesApplication` against the module's shared containers, where
 * `DomainConfiguration.stagingFactory` binds the same keys and constructs `DuckDbStagingProperties` — whose own
 * `require` refuses a sub-1-MiB memory limit, and whose binder refuses a factor past `Int.MAX_VALUE`. Whichever
 * bean runs first decides what the operator reads; the validator must win.
 *
 * "Refused by the report" is pinned three ways, so a boot that fails for another reason (Postgres, Redis, a bind,
 * an unrelated key) is red: the ROOT of the failure chain is the [IllegalStateException] thrown by
 * [ConfigValidator.validateOnStartup] whose message starts [REFUSED]; the report carries exactly ONE violation,
 * naming the case's key; and no [IllegalArgumentException] or bind failure appears anywhere in the chain.
 *
 * The fourth case is the adversary the order guard exists for: a bean registered AHEAD of every other one that
 * pulls `stagingFactory` (as any of the web beans that inject a `StagingFactory` may). Registration order alone
 * would let the staging bean win there; the explicit link ([ConfigValidatorPrecedence]) must not.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StagingAdmissionBootTest {
    @Test
    fun `804 R2 - a staging memory limit below DuckDB's floor refuses a lake-less boot through the catalogued report`() {
        val report = refusal(STAGING_MEMORY_LIMIT to "512KB")
        report shouldContain "$STAGING_MEMORY_LIMIT (524288B) must be at least 1 MiB"
    }

    @Test
    fun `804 R2 - scratch below one reservation refuses a lake-less boot through the catalogued report, naming the bound`() {
        val report =
            refusal(
                STAGING_MEMORY_LIMIT to "1GB",
                STAGING_EXPANSION_FACTOR to "3",
                SCRATCH_BYTES to "2GB",
            )
        report shouldContain "$SCRATCH_BYTES (2147483648B) must be at least $STAGING_MEMORY_LIMIT × $STAGING_EXPANSION_FACTOR"
        report shouldContain "(1073741824B × 3 — one DuckDB staging reservation"
    }

    @Test
    fun `804 R2 - a factor past Int MAX_VALUE refuses a lake-less boot through the catalogued report, not the binder`() {
        val report = refusal(STAGING_EXPANSION_FACTOR to "2147483648")
        report shouldContain "$STAGING_EXPANSION_FACTOR must be a positive whole number no greater than 2147483647"
    }

    @Test
    fun `804 R2 - a bean registered first that pulls the staging factory still meets the catalogued report first`() {
        val report = refusal(STAGING_MEMORY_LIMIT to "512KB", initializer = EarlyStagingConsumer())
        report shouldContain "$STAGING_MEMORY_LIMIT (524288B) must be at least 1 MiB"
    }

    /**
     * Boots with [overrides] and the lake off; returns the report's text once the failure is proven to be the
     * validator's catalogued refusal and nothing else (see the class KDoc).
     */
    private fun refusal(
        vararg overrides: Pair<String, String>,
        initializer: ApplicationContextInitializer<ConfigurableApplicationContext>? = null,
    ): String {
        val args = baseArgs() + "--datapipelines.lake.enabled=false" + overrides.map { (key, value) -> "--$key=$value" }
        val failure =
            runCatching {
                SpringApplicationBuilder(DatapipelinesApplication::class.java)
                    .apply { if (initializer != null) initializers(initializer) }
                    .run(*args.toTypedArray())
                    .close()
            }.exceptionOrNull() ?: error("the application started with ${overrides.toList()}; the §7 report never refused it")

        val chain = generateSequence(failure) { it.cause }.toList()
        val root = chain.last()
        check(root is IllegalStateException && root.message.orEmpty().startsWith(REFUSED)) {
            "the boot failed, but its root cause is not the §7 report: $root"
        }
        check(root.stackTrace.first().let { it.className == ConfigValidator::class.java.name && it.methodName == "validateOnStartup" }) {
            "an IllegalStateException with the report's prefix, thrown outside ConfigValidator.validateOnStartup: ${root.stackTrace.first()}"
        }
        chain.filterIsInstance<IllegalArgumentException>() shouldBe emptyList()
        chain.filter { it.javaClass.name.startsWith("org.springframework.boot.context.properties.bind.") } shouldBe emptyList()

        val report = root.message.orEmpty()
        val lines = report.lines().drop(1)
        check(lines.size == 1) { "expected exactly one violation, the case's own; got: $lines" }
        lines.single() shouldContain overrides.last().first
        return report
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

    /**
     * Registers, before the application's own sources are loaded, a singleton that `dependsOn` the staging factory —
     * so it is the first bean the factory instantiates, and it pulls `stagingFactory` ahead of everything the
     * component scan registers, `configValidator` included.
     */
    private class EarlyStagingConsumer : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(applicationContext: ConfigurableApplicationContext) {
            val definition = RootBeanDefinition(Any::class.java).apply { setDependsOn(ConfigValidatorPrecedence.STAGING_FACTORY) }
            (applicationContext as GenericApplicationContext).registerBeanDefinition("earlyStagingConsumer", definition)
        }
    }

    private companion object {
        const val REFUSED = "Invalid configuration — startup refused"
        const val STAGING_MEMORY_LIMIT = LakeRules.STAGING_MEMORY_LIMIT
        const val STAGING_EXPANSION_FACTOR = LakeRules.STAGING_EXPANSION_FACTOR
        const val SCRATCH_BYTES = LakeRules.SCRATCH_BYTES

        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedPostgres.postgres
    }
}
