package co.datapipelines

import co.datapipelines.config.SharedRedis
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.SecureRandom
import java.util.Base64

/**
 * The P0 smoke test (module-structure.md §9.3 / §5.10), now the P7 full-context test:
 * the WHOLE application boots — auth's real security chain, web's REST/SSE surface,
 * the engine, and the MCP autoconfiguration — Flyway migrates a clean database, and
 * the three root probes answer with the shapes their specs promise.
 *
 * OIDC discovery is real but aimed at [OidcDiscoveryStub]: `OidcConfig`'s production
 * bean builds `ClientRegistration`s from the configured providers exactly as shipped
 * (auth.md §5.2), against an in-process loopback discovery document — no network, no
 * container, no test-only substitute bean.
 *
 * Self-contained by design — it runs against the module's shared Postgres and
 * Redis containers rather than assuming `deploy/docker-compose.dev.yml` is up, so
 * CI and a fresh checkout behave identically.
 *
 * Deliberately does NOT activate the `dev` profile. Two reasons: the dev profile
 * resolves its secrets from a developer's `.env.local` (configuration.md §6), and
 * a test that depended on dev values would be the back door through which literal
 * secrets returned to the repository after the 2026-08-07 HIGH-2 finding. The
 * secrets below are generated per-run and never written down.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ApplicationSmokeTest {
    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    private val mapper = ObjectMapper()

    @Test
    fun `context loads and health reports every component UP`() {
        val response = rest.getForEntity("/health", String::class.java)

        response.statusCode.value() shouldBe 200
        val json = mapper.readTree(response.body)

        // Same key-set assertion as the unit test, but against the real serialized
        // response — this is what actually catches a Jackson config change in the app.
        json.fieldNames().asSequence().toList() shouldContainExactly listOf("status", "version", "components")
        json["components"].fieldNames().asSequence().toList() shouldContainExactly
            listOf("database", "redis", "h2_factory")

        // `database` UP is the real proof Flyway migrated and the pool works.
        json["status"].asText() shouldBe "UP"
        json["components"]["database"].asText() shouldBe "UP"
        json["components"]["redis"].asText() shouldBe "UP"
        json["components"]["h2_factory"].asText() shouldBe "UP"
        json["version"].asText().shouldNotBeBlank()
    }

    @Test
    fun `ready returns 200 once the context is up`() {
        rest.getForEntity("/ready", String::class.java).statusCode.value() shouldBe 200
    }

    @Test
    fun `info reports version and build time at the root path`() {
        val response = rest.getForEntity("/info", String::class.java)

        response.statusCode.value() shouldBe 200
        val json: JsonNode = mapper.readTree(response.body)

        json["version"].asText().shouldNotBeBlank()
        json["build_time"].shouldNotBeNull()
        // `commit` is absent unless the build was given -Pdatapipelines.commit,
        // so it is deliberately not asserted here.
    }

    @Test
    fun `no actuator endpoint is reachable on the application port`() {
        // observability.md §6.4: only /health, /ready and /info are public on the app
        // port; /actuator/prometheus lives on the separate management port (§4.2).
        rest.getForEntity("/actuator/health", String::class.java).statusCode.is2xxSuccessful shouldBe false
        rest.getForEntity("/actuator/env", String::class.java).statusCode.is2xxSuccessful shouldBe false
    }

    /**
     * The composition wiring (design 2026-08-13-pipeline-node-type §4.1), asserted at the bean
     * graph the assembled app actually boots: the `SubPipelineRunner` port and the
     * repository-backed `PipelineResolver` must both exist, or a PIPELINE node would fail closed
     * in production while module tests stayed green. (By name, not by type: `app` may only
     * depend on `web`, module-structure §4.2 — the behavioral half of this wiring is covered in
     * `web`'s `RepositoryPipelineResolverTest`.)
     */
    @Test
    fun `composition beans are wired - sub-pipeline runner and repository-backed resolver`() {
        applicationContext.getBean("subPipelineRunner") shouldNotBe null
        applicationContext.getBean("pipelineResolver") shouldNotBe null
    }

    private companion object {
        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedPostgres.postgres
        private val redis get() = SharedRedis.redis

        private const val SECRET_BYTES = 32

        /**
         * In-process OIDC discovery (auth.md §5.2) for the configured providers — the
         * real `OidcConfig` fetches this at startup; nothing here authenticates anyone.
         */
        private val oidc = OidcDiscoveryStub()

        /** A fresh, valid, base64 32-byte secret. Never a literal — see the class KDoc. */
        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            // Random management port (observability.md §4.2 puts actuator on its own
            // port). Fixed 9090 would collide between concurrently running tests, and
            // TestRestTemplate targets the application port regardless.
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedRedis.port }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedRedis.port }

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            // OIDC (auth.md §5.1/§5.2): the provider LIST is re-declared here in full —
            // Spring takes a bound list wholesale from the highest-precedence property
            // source that contains any element of it, so overriding just the issuer-uri
            // would shadow application.yml's entries and leave their names blank; and
            // BOTH indices must be redeclared, because an Environment lookup for index 1
            // otherwise falls through to application.yml's `${GOOGLE_CLIENT_ID}`
            // placeholder, which cannot resolve here. (application.yml ships google
            // only; the second entry merely exercises multi-provider discovery.)
            // Both entries discover from the in-process stub; the base-url the
            // redirect URIs are built from is set explicitly.
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
