package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
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
import java.sql.DriverManager
import java.util.Base64

/**
 * The zero-setup deployment (auth.md §5A.2): the FULL application starting with **no
 * OIDC configuration at all** — no providers, no base-url — only local accounts and
 * the config-seeded first admin. Proves the round's headline outcome at the wire:
 *
 *  - startup succeeds with zero OIDC providers (the stock `google` entry binds empty
 *    and is ignored; `ConfigValidator` accepts local as the one auth method);
 *  - the login page renders the form alone — no divider, no provider buttons;
 *  - the seeded credential logs in (and is flagged `must_change_password`, so the
 *    §5A.4 gate forces its replacement at first login).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class LocalAdminSeedE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `the app starts with no OIDC configuration and the seeded admin logs in`() {
        // Startup itself is the first proof: this context had zero OIDC providers.
        val page =
            given()
                .port(port)
                .`when`()
                .get("/login")
                .then()
                .statusCode(200)
                .extract()

        val html = page.asString()
        html shouldContain "name=\"password\""
        // A local-only deployment: no divider, no provider buttons.
        html shouldNotContain ">or<"
        html shouldNotContain "/oauth2/authorization/"

        val csrf =
            checkNotNull(CSRF_FIELD.find(html)) { "no _csrf hidden input on the login page" }
                .groupValues[1]
        val response =
            given()
                .port(port)
                .cookies(page.detailedCookies().asList().associate { it.name to it.value })
                .contentType(ContentType.URLENC)
                .formParam("_csrf", csrf)
                .formParam("email", ADMIN_EMAIL)
                .formParam("password", SEED_PASSWORD)
                .redirects()
                .follow(false)
                .`when`()
                .post("/login")

        response.statusCode shouldBe 302
        response.headers.getValue("Location") shouldBe "http://localhost:$port/"
        val session = response.detailedCookies().get("dp_session").shouldNotBeNull()
        given()
            .port(port)
            .cookie("dp_session", session.value)
            .`when`()
            .get("/")
            .then()
            .statusCode(200)

        // The seeded credential is one-time: the §5A.4 gate key is set.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement("SELECT must_change_password, is_admin, provider FROM users WHERE email = ?")
                .use { ps ->
                    ps.setString(1, ADMIN_EMAIL)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getBoolean("must_change_password") shouldBe true
                        rs.getBoolean("is_admin") shouldBe true
                        rs.getString("provider") shouldBe "bootstrap"
                    }
                }
        }
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32
        private const val ADMIN_EMAIL = "seed-admin@datapipelines.test"

        private val CSRF_FIELD = Regex("""name="_csrf" value="([^"]+)"""")

        private val random = SecureRandom()

        /** Generated per run — no literal secret in any test fixture (HIGH-2). */
        private val SEED_PASSWORD = "e2e-seed-" + (1..24).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

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

            // NO OIDC AT ALL: zero providers, no base-url. The stock google entry binds
            // empty (GOOGLE_CLIENT_ID unset) and is ignored with a WARN — the whole
            // point of this context is that nothing OIDC is required to start.
            registry.add("datapipelines.auth.local.enabled") { true }
            registry.add("datapipelines.auth.bootstrap-admin-email") { ADMIN_EMAIL }
            registry.add("datapipelines.auth.local.bootstrap-password") { SEED_PASSWORD }
        }
    }
}
