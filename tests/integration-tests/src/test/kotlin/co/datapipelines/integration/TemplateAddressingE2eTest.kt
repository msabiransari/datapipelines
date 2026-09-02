package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import de.mkammerer.argon2.Argon2Factory
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
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
import java.util.UUID

/**
 * The §9.6 addressing gates (template-hierarchy-design.md §12.12, round 043 exit gates 4–5),
 * against the FULL application on a real Tomcat port:
 *
 * 1. **Lifecycle over the new routes** for a template named `acme/finance/report` — create,
 *    read (both `GET /api/v1/templates` shapes), versioned read, draft write, release, render,
 *    delete — proving a `/`-carrying name is addressable everywhere.
 * 2. **Falsification against the removed `/{id}` shape**: the encoded slash is refused **400,
 *    not 404** — the container rejects `%2F` below routing and below the security chain (the
 *    measurement §9.6 records), so a client that kept the old form fails loudly rather than
 *    silently re-routing. The flat control path answers 404: the route is gone, not the name.
 * 3. **The removed shapes are gone from the route table**: every pre-v2.0 `/{id}` shape is
 *    probed over HTTP and must answer 404, so the removal cannot silently regress.
 *
 * Auth is a seeded admin API key (the TracerBulletE2eTest pattern: SQL-seeded user + Argon2id
 * key hash), which also proves the replacement routes carry the same scope floor their
 * predecessors had — the key reaches every one of them.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TemplateAddressingE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    @Order(1)
    fun `a hierarchical template name is addressable through every new route`() {
        // Seeded here, not in @BeforeAll: a static @BeforeAll runs BEFORE the Spring
        // context — and with it Flyway — is up (the TracerBulletE2eTest pattern).
        seedAuthRows()
        val name = "acme/finance/report"
        val bodyV1 =
            """
            {"id": "$name", "dialect": "POSTGRES", "display_name": "Monthly Revenue",
             "description": "043 gate template", "imports": [], "body": "SELECT ${'$'}{x} AS v"}
            """.trimIndent()

        val releasedHash = createTemplate(name, bodyV1)
        assertBothGetShapes(name)
        assertVersionedRead(name)
        val draftHash = writeDraft(bodyV1, releasedHash)
        releaseDraft(name, draftHash)
        assertRender(name)
        deleteTemplate(name)
    }

    /** create — POST /api/v1/templates (unchanged: it never carried a name in the path). */
    private fun createTemplate(
        name: String,
        bodyV1: String,
    ): String =
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(bodyV1)
            .`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
            .body("data.id", equalTo(name))
            .extract()
            .jsonPath()
            .getString("data.body_hash")

    /** The two shapes of GET /api/v1/templates: single-resource with `name`, paged list without. */
    private fun assertBothGetShapes(name: String) {
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .queryParam("name", name)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(200)
            .body("data.id", equalTo(name))
            .body("data.version", equalTo(1))

        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(200)
            .body("data.items.id", hasItem(name))

        // the single-resource shape keeps template.not_found — never an empty list.
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .queryParam("name", "acme/finance/nonexistent")
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(404)
            .body("error.code", equalTo("template.not_found"))
    }

    /** versioned read — GET /api/v1/templates/versions?name=<path>&version=N. */
    private fun assertVersionedRead(name: String) {
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .queryParam("name", name)
            .queryParam("version", 1)
            .`when`()
            .get("/api/v1/templates/versions")
            .then()
            .statusCode(200)
            .body("data.id", equalTo(name))
            .body("data.version", equalTo(1))
    }

    /** draft write — PUT /api/v1/templates, name in the body's id field. */
    private fun writeDraft(
        bodyV1: String,
        releasedHash: String,
    ): String =
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .header("If-Match", releasedHash)
            .body(bodyV1.replace("SELECT ${'$'}{x} AS v", "SELECT ${'$'}{x} AS v, 'v2' AS rev"))
            .`when`()
            .put("/api/v1/templates")
            .then()
            .statusCode(200)
            .body("data.status", equalTo("DRAFT"))
            .extract()
            .jsonPath()
            .getString("data.body_hash")

    /** release — POST /api/v1/templates/release, name in the body. */
    private fun releaseDraft(
        name: String,
        draftHash: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .header("If-Match", draftHash)
            .body("""{"name": "$name"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))
            .body("data.version", equalTo(2))
    }

    /** render — POST /api/v1/templates/render, name and version in the body. */
    private fun assertRender(name: String) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body("""{"name": "$name", "version": 2, "context": {"x": 42}}""")
            .`when`()
            .post("/api/v1/templates/render")
            .then()
            .statusCode(200)
            .body("data", equalTo("SELECT 42 AS v, 'v2' AS rev"))
    }

    /** delete — DELETE /api/v1/templates?name=<path>; afterwards the read 404s. */
    private fun deleteTemplate(name: String) {
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .queryParam("name", name)
            .`when`()
            .delete("/api/v1/templates")
            .then()
            .statusCode(204)

        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .queryParam("name", name)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(404)
            .body("error.code", equalTo("template.not_found"))
    }

    @Test
    @Order(2)
    fun `the removed path-addressed shape answers 400 on the encoded slash, 404 on the flat control`() {
        // FALSIFICATION (§9.6's measurement, re-run against this build): %2F in the path is
        // refused 400 below routing and below the security chain — no handler can reach past
        // it. urlEncodingEnabled(false) sends the escape verbatim, as the raw-socket spike did.
        given()
            .port(port)
            .urlEncodingEnabled(false)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/templates/acme%2Ffinance%2Freport")
            .then()
            .statusCode(400)

        // The flat control: no encoded slash, route simply gone — 404, never 400, and never a
        // silent match on some leftover /{id} mapping.
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/templates/acme")
            .then()
            .statusCode(404)
    }

    @Test
    @Order(3)
    fun `the removed path-addressed shapes are gone from the route table`() {
        // Gate 5, asserted over HTTP against the live route table: every pre-v2.0 `/{id}`
        // shape must answer 404 (flat control — the route is GONE, not the name), never 200
        // and never a silent re-match. A leftover mapping would answer something else.
        listOf(
            "/api/v1/templates/acme",
            "/api/v1/templates/acme/versions/1",
            "/api/v1/templates/acme/release",
            "/api/v1/templates/acme/draft/discard",
            "/api/v1/templates/acme/versions/1/render",
        ).forEach { path ->
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .request(
                    when {
                        path.endsWith("/release") || path.endsWith("/discard") || path.endsWith("/render") -> "POST"
                        else -> "GET"
                    },
                    path,
                ).then()
                .statusCode(404)
        }
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .delete("/api/v1/templates/acme")
            .then()
            .statusCode(404)
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .put("/api/v1/templates/acme")
            .then()
            .statusCode(404)

        // The UI twins, with the same API key (it authenticates on every path):
        // /templates/{id}/editor and /partials/templates/{id}/versions/{v}/render are gone too.
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/templates/acme/editor")
            .then()
            .statusCode(404)
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .post("/partials/templates/acme/versions/1/render")
            .then()
            .statusCode(404)
    }

    /** A generated `dpk_<id>.<secret>` key and its stored Argon2id hash (auth.md §7.1/§7.2). */
    private class SeededKey(
        val name: String,
        val scopes: Array<out String>,
        val id: String,
        val plaintext: String,
        val hash: String,
    )

    companion object {
        fun seedAuthRows() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER_ID', 'e2e-043@datapipelines.test', 'E2E 043', 'test', 'e2e-043-sub', TRUE, TRUE)
                        """.trimIndent(),
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                            " VALUES (?, ?, ?, ?, ?, 'defa0000-0000-0000-0000-000000000001')",
                    ).use { ps ->
                        ps.setString(1, ADMIN_KEY.id)
                        ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                        ps.setString(3, ADMIN_KEY.name)
                        ps.setString(4, ADMIN_KEY.hash)
                        ps.setArray(5, connection.createArrayOf("text", ADMIN_KEY.scopes))
                        ps.executeUpdate()
                    }
            }
        }

        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val random = SecureRandom()

        // Argon2id with auth's exact parameters (SecretHasher.kt: 2 / 19 456 / 1) on the same
        // pinned library — the TracerBulletE2eTest pattern, including the char[] wipe.
        private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

        private fun argon2Hash(raw: String): String {
            val chars = raw.toCharArray()
            return try {
                argon2.hash(2, 19_456, 1, chars)
            } finally {
                argon2.wipeArray(chars)
            }
        }

        private val ADMIN_KEY =
            run {
                val id = "dpk_" + (1..12).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
                val plaintext = id + "." + (1..48).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
                SeededKey(name = "e2e-043-key", scopes = arrayOf("admin"), id = id, plaintext = plaintext, hash = argon2Hash(plaintext))
            }

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
