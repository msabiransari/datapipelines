package co.datapipelines

import co.datapipelines.config.SharedRedis
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.SecureRandom
import java.util.Base64

/**
 * #716: the meter registries of Boot's DEFAULT test shape — `@SpringBootTest` with no
 * `@AutoConfigureObservability`, so metrics export is off and Boot's test customizer keeps only the
 * simple registry on (`management.simple.metrics.export.enabled=true`). [ApplicationSmokeTest] boots the
 * production shape (export on); this class is the shape every other full-context test in the repository
 * boots, and the one a recording assertion there depends on.
 *
 * Portable by design: the same shared containers and generated secrets as [ApplicationSmokeTest] (the
 * property block is copied, not shared) and no site-local assumption, so it runs on every machine —
 * unlike [ApplicationHardenedSmokeTest], which skips where no site-local IPv4 exists.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ApplicationDefaultMetricsShapeTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    /**
     * Exactly one registry, Boot's [SimpleMeterRegistry], and it RECORDS: a probe counter incremented through
     * the resolved `MeterRegistry` reads back 1.0. Without it (simple export switched off as well) Boot
     * builds an empty composite that accepts every increment and keeps none, so a full-context test reading
     * a count would read zero.
     */
    @Test
    fun `the default test context holds exactly one recording meter registry`() {
        println("event=meter.registries shape=default ${meterRegistryShape(applicationContext)}")
        val beans = applicationContext.getBeansOfType(MeterRegistry::class.java)
        withClue("MeterRegistry beans: ${beans.mapValues { it.value::class.java.name }}") {
            beans.values.map { it::class.java.name } shouldBe listOf(SimpleMeterRegistry::class.java.name)
        }
        val registry = applicationContext.getBean(MeterRegistry::class.java)
        registry.counter(PROBE).increment()
        registry.find(PROBE).counter()?.count() shouldBe 1.0
    }

    private companion object {
        private val postgres get() = SharedPostgres.postgres
        private val redis get() = SharedRedis.redis

        private const val SECRET_BYTES = 32

        /** Outside the `datapipelines.` prefix, so no catalogue or registry guard ever reads it. */
        private const val PROBE = "test.default_shape.recording_probe"

        private val oidc = OidcDiscoveryStub()

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedRedis.port }
            registry.add("datapipelines.redis.password") { SharedRedis.PASSWORD }

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}

/**
 * The context's `MeterRegistry` beans (bean name → class) and the one `getBean(MeterRegistry)` resolves —
 * with its member registries when it is a [CompositeMeterRegistry] — as one evidence line (#716). Shared by
 * the three full-context classes that print it.
 */
internal fun meterRegistryShape(context: ApplicationContext): String {
    val beans =
        context
            .getBeansOfType(MeterRegistry::class.java)
            .mapValues { it.value::class.java.name }
            .toSortedMap()
    val resolved =
        runCatching { context.getBean(MeterRegistry::class.java) }.fold(
            onSuccess = { registry ->
                val members = (registry as? CompositeMeterRegistry)?.registries?.map { it::class.java.name }?.sorted()
                registry::class.java.name + (members?.let { " members=$it" } ?: "")
            },
            onFailure = { "unresolved(${it::class.java.simpleName}: ${it.message})" },
        )
    return "beans=$beans resolved=$resolved"
}
