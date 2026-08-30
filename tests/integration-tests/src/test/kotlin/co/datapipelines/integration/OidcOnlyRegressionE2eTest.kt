package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.SecureRandom
import java.util.Base64

/**
 * The OIDC-only regression proof (026): a deployment with ONLY OIDC configured —
 * `datapipelines.auth.local.enabled` left at its default `false` — behaves exactly
 * as before local accounts existed:
 *
 *  - the login page renders NO password form and NO divider, only the provider
 *    buttons it has always rendered;
 *  - `POST /login` does not exist (404) — the method is off, not merely hidden;
 *  - the OIDC entry point redirects as always;
 *  - the `?error=` banner idiom is unchanged.
 *
 * The OIDC flow itself (callback, provisioning, workspace resolution, audit
 * events) is pinned unchanged by the pre-existing `OidcLoginIntegrationTest` in
 * the auth module, which this round did not modify.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class OidcOnlyRegressionE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `an OIDC-only deployment renders and behaves exactly as before local accounts`() {
        val page =
            given()
                .port(port)
                .`when`()
                .get("/login")
                .then()
                .statusCode(200)
                .extract()
        val html = page.asString()

        // No local method anywhere on the page — the pre-§5A page, byte-for-byte
        // in structure: same card, same buttons, nothing else. (The navbar's
        // logout form predates this round; it is not the local login form.)
        html shouldNotContain "name=\"password\""
        html shouldNotContain "id=\"login-email\""
        html shouldNotContain ">or<"
        html shouldContain "/oauth2/authorization/google"
        html shouldContain "/oauth2/authorization/microsoft"

        // The ?error= idiom is untouched.
        given()
            .port(port)
            .`when`()
            .get("/login?error=oidc_error")
            .then()
            .statusCode(200)
            .body(org.hamcrest.Matchers.containsString("Login failed. Please try again."))

        // POST /login is a 404 in this deployment — local login is not merely
        // hidden, it is not there.
        val csrf =
            checkNotNull(CSRF_COOKIE.find(page.detailedCookies().asList().joinToString(";") { "${it.name}=${it.value}" })) {
                "no dp_csrf cookie on the login page"
            }.groupValues[1]
        given()
            .port(port)
            .cookie("dp_csrf", csrf)
            .header("DP-CSRF-Token", csrf)
            .contentType(ContentType.URLENC)
            .formParam("email", "nobody@datapipelines.test")
            .formParam("password", "anything")
            .redirects()
            .follow(false)
            .`when`()
            .post("/login")
            .then()
            .statusCode(404)

        // The OIDC authorization entry point redirects to the provider as always.
        given()
            .port(port)
            .redirects()
            .follow(false)
            .`when`()
            .get("/oauth2/authorization/google")
            .then()
            .statusCode(302)
            .header("Location", org.hamcrest.Matchers.startsWith(oidc.issuer))
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32

        private val CSRF_COOKIE = Regex("""dp_csrf=([^;]+)""")

        private val random = SecureRandom()

        @Container
        @JvmStatic
        private val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("datapipelines")
                .withPassword("datapipelines")

        @Container
        @JvmStatic
        private val redis =
            GenericContainer("redis:7-alpine")
                .withCommand("redis-server", "--maxmemory-policy", "noeviction")
                .withExposedPorts(REDIS_PORT)

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { redis.getMappedPort(REDIS_PORT) }

            // Generated per run — no literal secret in any test fixture (HIGH-2).
            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            // OIDC ONLY — local.enabled deliberately untouched (its default is false).
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
