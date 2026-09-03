package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import de.mkammerer.argon2.Argon2Factory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 033, at the wire against the FULL application: the public/private split of the new
 * surface, and the cache defence on the public side.
 *
 *  - anonymous `GET /` is the marketing site (200, tool count rendered);
 *  - anonymous `GET /docs`, `/docs/{slug}` and `/dashboard` redirect to `/login`
 *    (docs are session-only — reviewer's answer; the dashboard is authenticated);
 *  - signed-in, all three render — including the operations manual itself;
 *  - `/` and the `/site/` assets carry `Cache-Control: public` (033/D1: cache headers, NOT the
 *    login rate limiter — OPEN-ITEMS T46).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class SiteDocsE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `anonymous gets the marketing site at root with a public cache header`() {
        val response =
            given()
                .port(port)
                .accept("text/html")
                .`when`()
                .get("/")

        response.statusCode shouldBe 200
        response.asString() shouldContain "Agent-native Data Pipelines"
        // The fact is derived, not transcribed: 18 tools as of 033 — asserted against the
        // catalog in WebsiteFactsGuardTest; here we only prove the number made it to the wire.
        response.asString() shouldContain "tools cover the full lifecycle"
        response.header("Cache-Control") shouldContain "public"
    }

    @Test
    fun `site assets are public and cacheable`() {
        given()
            .port(port)
            .`when`()
            .get("/site/css/site.css")
            .then()
            .statusCode(200)
            .header("Cache-Control", org.hamcrest.Matchers.containsString("public"))
    }

    @Test
    fun `anonymous docs and dashboard redirect to login`() {
        listOf("/docs", "/docs/auth", "/dashboard").forEach { path ->
            given()
                .port(port)
                .accept("text/html")
                .redirects()
                .follow(false)
                .`when`()
                .get(path)
                .then()
                .statusCode(302)
                .header("Location", "/login")
        }
    }

    @Test
    fun `signed in, the dashboard and the operations manual render`() {
        seedUser()
        val session = login()

        given()
            .port(port)
            .cookie("dp_session", session)
            .`when`()
            .get("/dashboard")
            .then()
            .statusCode(200)

        given()
            .port(port)
            .cookie("dp_session", session)
            .`when`()
            .get("/docs")
            .then()
            .statusCode(200)

        // The operations manual, in-product: deployment.md rendered as HTML.
        val doc =
            given()
                .port(port)
                .cookie("dp_session", session)
                .`when`()
                .get("/docs/deployment")
        doc.statusCode shouldBe 200
        doc.asString() shouldContain "doc-body"
    }

    private fun login(): String {
        val page =
            given()
                .port(port)
                .`when`()
                .get("/login")
                .then()
                .statusCode(200)
                .extract()
        val csrf =
            checkNotNull(CSRF_FIELD.find(page.asString())) { "no _csrf hidden input on the login page" }
                .groupValues[1]
        return given()
            .port(port)
            .cookies(page.detailedCookies().asList().associate { it.name to it.value })
            .contentType(ContentType.URLENC)
            .formParam("_csrf", csrf)
            .formParam("email", EMAIL)
            .formParam("password", PASSWORD)
            .`when`()
            .post("/login")
            .then()
            .statusCode(302)
            .extract()
            .detailedCookies()
            .getValue("dp_session")
    }

    companion object {
        private const val SECRET_BYTES = 32
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val EMAIL = "site-docs@datapipelines.test"

        private val CSRF_FIELD = Regex("""name="_csrf" value="([^"]+)"""")
        private val random = SecureRandom()

        /** Generated per run — no literal secret in any test fixture (HIGH-2). */
        private val PASSWORD = "e2e-site-" + (1..24).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")

        private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

        private val seeded = AtomicBoolean(false)

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedE2e.postgres

        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        /** Seed-in-first-test (TracerBullet pattern): Flyway must have migrated first. */
        private fun seedUser() {
            if (!seeded.compareAndSet(false, true)) return
            val chars = PASSWORD.toCharArray()
            val hash =
                try {
                    argon2.hash(2, 19_456, 1, chars)
                } finally {
                    argon2.wipeArray(chars)
                }
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (email, display_name, provider, provider_subject, is_active, password_hash)
                        VALUES ('$EMAIL', 'Site Docs', 'local', '$EMAIL', TRUE, '$hash')
                        """.trimIndent(),
                    )
                }
            }
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            // Generated per run — no literal secret in any test fixture (HIGH-2).
            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            registry.add("datapipelines.auth.local.enabled") { true }
        }
    }
}
