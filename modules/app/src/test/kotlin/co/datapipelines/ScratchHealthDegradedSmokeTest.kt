package co.datapipelines

import co.datapipelines.config.SharedRedis
import co.datapipelines.web.health.ScratchPathKind
import co.datapipelines.web.health.ScratchSpaceReader
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/**
 * #806 section C, the degraded case, on the ASSEMBLED application: the scratch volume is forced below one default
 * reservation (the space reader pinned to one usable byte), so the `duckdb_scratch` indicator is `OUT_OF_SERVICE`.
 * The management port's `/actuator/health` then answers 503 with a status-only body — no path, no number, no
 * component — while the application port's public `/health` and `/ready` still answer 200 with exactly their three
 * components (rest-api.md §11.1; owner ruling 2026-11-12: a full scratch volume never fails readiness).
 *
 * Booted like [ApplicationSmokeTest] (the same property set, the in-process OIDC discovery stub, the module's shared
 * containers); it is a class of its own because the pinned reading is a different context.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(ScratchHealthDegradedSmokeTest.StarvedScratchSpace::class)
class ScratchHealthDegradedSmokeTest {
    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalManagementPort
    private var managementPort: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    private fun get(
        port: Int,
        path: String,
    ): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `a scratch volume below one reservation makes the management health 503, status only`() {
        val health = get(managementPort, "/actuator/health")
        withClue("anonymous GET /actuator/health on the management port: ${health.body()}") {
            health.statusCode() shouldBe 503
            val json = mapper.readTree(health.body())
            json.fieldNames().asSequence().toList() shouldContainExactly listOf("status")
            json["status"].asText() shouldBe "OUT_OF_SERVICE"
            health.body() shouldNotContain "dp-staging"
            health.body() shouldNotContain "duckdb_scratch"
            health.body().any(Char::isDigit) shouldBe false
        }
    }

    @Test
    fun `the public health and ready probes stay 200 with exactly their three components`() {
        val health = rest.getForEntity("/health", String::class.java)
        health.statusCode.value() shouldBe 200
        val json = mapper.readTree(health.body)
        json["status"].asText() shouldBe "UP"
        json["components"].fieldNames().asSequence().toList() shouldContainExactly listOf("database", "redis", "h2_factory")

        val ready = rest.getForEntity("/ready", String::class.java)
        ready.statusCode.value() shouldBe 200
        mapper.readTree(ready.body)["components"].fieldNames().asSequence().toList() shouldContainExactly
            listOf("database", "redis", "h2_factory")
    }

    /** The scratch readings pinned to a writable directory with ONE usable byte — below any default reservation. */
    @TestConfiguration
    class StarvedScratchSpace {
        @Bean
        @Primary
        fun starvedScratchSpaceReader(): ScratchSpaceReader =
            object : ScratchSpaceReader {
                override fun kind(path: Path): ScratchPathKind = ScratchPathKind.DIRECTORY

                override fun canWrite(directory: Path): Boolean = true

                override fun usableBytes(path: Path): Long = 1L
            }
    }

    private companion object {
        private val postgres get() = SharedPostgres.postgres
        private val redis get() = SharedRedis.redis
        private const val SECRET_BYTES = 32

        /** In-process OIDC discovery for the configured providers, exactly as [ApplicationSmokeTest] boots it. */
        private val oidc = OidcDiscoveryStub()

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) })

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
            // The provider list re-declared in full, both indices — see ApplicationSmokeTest for why.
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
