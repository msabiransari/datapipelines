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
 *  - anonymous `GET /dashboard` still redirects to `/login` — the private surface did not move;
 *  - anonymous `GET /docs` and `/docs/{slug}` now render (073 §C) with the PUBLIC chrome and
 *    no link into the signed-in app;
 *  - signed-in, the dashboard and the docs render — the docs in the APP chrome, so making them
 *    public did not evict logged-in readers to the marketing site;
 *  - `/robots.txt` and `/sitemap.xml` answer anonymously, and EVERY `<loc>` in the sitemap
 *    answers 200 anonymously — the sweep that makes a generated sitemap trustworthy;
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
        // 115: the H1 speaks to the buyer; the engineering story lives on /how-it-works.
        response.asString() shouldContain "Show your customers their data. No data team required."
        response.header("Cache-Control") shouldContain "public"

        // The fact is derived, not transcribed: asserted against the catalog in
        // WebsiteFactsGuardTest — here we only prove the number made it to the wire, on the
        // page 115 moved that sentence to.
        val engineering =
            given()
                .port(port)
                .accept("text/html")
                .`when`()
                .get("/how-it-works")
        engineering.statusCode shouldBe 200
        engineering.asString() shouldContain "tools cover the full lifecycle"
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
    fun `the private surface still redirects anonymous callers to login`() {
        listOf("/dashboard", "/pipelines", "/settings/api-keys").forEach { path ->
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
    fun `anonymous docs render with the public chrome, not the application navigation`() {
        listOf("/docs", "/docs/auth", "/docs/staging").forEach { path ->
            val response =
                given()
                    .port(port)
                    .accept("text/html")
                    .redirects()
                    .follow(false)
                    .`when`()
                    .get(path)

            response.statusCode shouldBe 200
            // The public footer's site map is on every page the site layout renders.
            response.asString() shouldContain "footer-map"
            response.asString() shouldContain "rel=\"canonical\" href=\"https://datapipelines.co$path\""
            // The signed-in navigation must NOT be there: that is the half of this change a
            // 200 alone would not catch.
            (response.asString().contains("app-nav")) shouldBe false
            response.header("Cache-Control") shouldContain "public"
        }
    }

    @Test
    fun `robots and the sitemap answer anonymously, and every listed URL is reachable`() {
        val robots =
            given()
                .port(port)
                .`when`()
                .get("/robots.txt")
        robots.statusCode shouldBe 200
        robots.asString() shouldContain "Sitemap: https://datapipelines.co/sitemap.xml"

        val sitemap =
            given()
                .port(port)
                .`when`()
                .get("/sitemap.xml")
        sitemap.statusCode shouldBe 200

        val locations = LOC.findAll(sitemap.asString()).map { it.groupValues[1] }.toList()
        // Non-vacuity: 14 registry pages + the docs index + the packaged docs. A sweep over an
        // empty list is the failure mode this whole test exists to prevent.
        check(locations.size >= MIN_SITEMAP_URLS) { "the sitemap listed only ${locations.size} URLs" }

        val unreachable =
            locations.mapNotNull { loc ->
                val path = loc.removePrefix("https://datapipelines.co").ifEmpty { "/" }
                val status =
                    given()
                        .port(port)
                        .accept("text/html")
                        .redirects()
                        .follow(false)
                        .`when`()
                        .get(path)
                        .statusCode
                if (status == 200) null else "$path answered $status anonymously"
            }
        unreachable shouldBe emptyList()
    }

    /**
     * 095 §C3/§E — the raw skill route, at the wire and ANONYMOUS. It is the delivery for an
     * agent that speaks no MCP, so the thing under test is precisely that no credential is
     * needed: the two `permitAll` entries in `SecurityConfig` are what this pins.
     */
    @Test
    fun `the skill and its references are served as markdown without a credential`() {
        val skill =
            given()
                .port(port)
                .`when`()
                .get("/skill.md")

        skill.statusCode shouldBe 200
        skill.contentType shouldContain "text/markdown"
        skill.asString() shouldContain "name: datapipelines"
        skill.header("Cache-Control") shouldContain "public"

        // Every reference the skill's own map advertises answers at its own URL — the sweep
        // that makes the map trustworthy rather than a list of hopes.
        val references = SKILL_REFERENCE.findAll(skill.asString()).map { it.groupValues[1] }.toSet()
        check(references.size >= MIN_SKILL_REFERENCES) { "the skill advertised only ${references.size} references" }
        val unreachable =
            references.mapNotNull { name ->
                val status =
                    given()
                        .port(port)
                        .`when`()
                        .get("/skill/$name.md")
                        .statusCode
                if (status == 200) null else "/skill/$name.md answered $status anonymously"
            }
        unreachable shouldBe emptyList()

        // A mistyped reference is the §4.2 envelope, not an HTML error page: this route is
        // curl'ed by agents, and an HTML body would be parsed as the manual.
        val missing =
            given()
                .port(port)
                .`when`()
                .get("/skill/nope.md")
        missing.statusCode shouldBe 404
        missing.contentType shouldContain ContentType.JSON.toString().substringBefore(";")
        missing.jsonPath().getString("error.details.reason") shouldBe "skill_reference_not_found"
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

        // The operations manual, in-product: deployment.md rendered as HTML, in the APP
        // chrome — 073 made the docs public without evicting signed-in readers to the
        // marketing site, and this is the assertion that would catch it if it had.
        val doc =
            given()
                .port(port)
                .cookie("dp_session", session)
                .`when`()
                .get("/docs/deployment")
        doc.statusCode shouldBe 200
        doc.asString() shouldContain "doc-body"
        doc.asString() shouldContain "app-nav"
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
        private val LOC = Regex("""<loc>([^<]+)</loc>""")

        /** 14 registry pages + the docs index + ~25 packaged docs. */
        private const val MIN_SITEMAP_URLS = 35

        /** The reference map in SKILL.md — `references/<name>.md`, one per reference (095). */
        private val SKILL_REFERENCE = Regex("""references/([a-z0-9-]+)\.md""")

        /** Non-vacuity floor for the reference sweep: the split landed nine. */
        private const val MIN_SKILL_REFERENCES = 8

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
