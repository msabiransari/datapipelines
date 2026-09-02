package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
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
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

/**
 * 050/R3 at the HTTP surface: a datasource row written OUTSIDE the API (manual SQL, an older
 * backup) carrying a server-managed `properties.hikari.readOnly` used to fail an unmodified
 * GET→PUT round-trip with 400 `datasource.validation.properties_invalid` — while the stored
 * key still flipped the real pool flag at build time. `DatasourceRow.toDatasource` now strips
 * every SERVER_MANAGED key on read (the one boundary GET, PUT-revalidation and pool build all
 * cross), so:
 *
 * - GET returns the row WITHOUT the key (and WITH the legitimate hikari keys);
 * - PUTting exactly what GET returned succeeds with 200;
 * - the row-level and real-pool proofs live in the datasources module's suites
 *   (`DatasourceRowServerManagedStripTest`, `DatasourceRegistryIntegrationTest`).
 *
 * This suite is the third leg the row-level tests cannot see: the REST bind and the §9
 * validation over the wire.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class DatasourceOutOfBandRowE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    fun `a row carrying hikari readOnly round-trips unmodified through GET then PUT`() {
        seedAuthRows()
        insertOutOfBandRow()

        val getBody =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/datasources/$DS")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()

        // GET strips the server-managed key and keeps the operator's real one.
        val got: JsonNode = mapper.readTree(getBody).path("data")
        got.path("properties").path("hikari").has("readOnly") shouldBe false
        got.path("properties").path("hikari").path("maximumPoolSize").asInt() shouldBe 5

        // The unmodified round-trip: PUT exactly what GET returned (bar the write-only
        // password, which the §9.4 bind re-adds) — 200, not the pre-050 400.
        val putBody =
            mapper.createObjectNode().apply {
                put("display_name", got.path("display_name").asText())
                put("dialect", got.path("dialect").asText())
                put("jdbc_url", got.path("jdbc_url").asText())
                put("username", got.path("username").asText())
                put("password", "pw")
                set<JsonNode>("properties", got.path("properties"))
            }
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(mapper.writeValueAsString(putBody))
            .`when`()
            .put("/api/v1/datasources/$DS")
            .then()
            .statusCode(200)

        // And the stored row's hikari namespace now holds only the legitimate key — the
        // save persisted the CLEAN projection, so the dirty key is gone from the database too.
        val stored = readStoredProperties()
        stored.path("hikari").has("readOnly") shouldBe false
        stored.path("hikari").path("maximumPoolSize").asInt() shouldBe 5
    }

    // ----------------------------------------------------------------------- SQL fixtures

    /** The D7 channel, verbatim: a row no API wrote, carrying a smuggled pool flag. */
    private fun insertOutOfBandRow() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, password_encrypted,
                                             properties_json, created_by)
                    VALUES ('$DS', 'Out of band', 'H2', 'jdbc:h2:mem:oob_e2e', 'sa', decode('00', 'hex'),
                            CAST('{"hikari": {"readOnly": true, "maximumPoolSize": 5}}' AS jsonb),
                            '$ADMIN_USER_ID')
                    ON CONFLICT (name) DO UPDATE SET properties_json = EXCLUDED.properties_json
                    """.trimIndent(),
                )
            }
        }
    }

    private fun readStoredProperties(): JsonNode {
        val json =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT properties_json::TEXT FROM datasources WHERE name = '$DS'").use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            }
        return mapper.readTree(json)
    }

    private fun seedAuthRows() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', 'e2e-oob@datapipelines.test', 'E2E OOB', 'test', 'e2e-oob-sub', TRUE, TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
            }
            val insertSql =
                "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                    " VALUES (?, ?, ?, ?, ?, 'defa0000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING"
            connection.prepareStatement(insertSql).use { ps ->
                ps.setString(1, ADMIN_KEY.id)
                ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                ps.setString(3, ADMIN_KEY.name)
                ps.setString(4, ADMIN_KEY.hash)
                ps.setArray(5, connection.createArrayOf("text", ADMIN_KEY.scopes))
                ps.executeUpdate()
            }
        }
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DS = "oob_e2e_ds"

        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32))
        private val ADMIN_USER_ID = "a11e0000-0000-0000-0000-000000000003"
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-oob-key", arrayOf("admin"))

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

            registry.add("datapipelines.jwt.secret") { SECRET }
            registry.add("datapipelines.db.encryption-key") { SECRET }

            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @org.junit.jupiter.api.AfterAll
        @JvmStatic
        fun tearDown() {
            oidc.close()
        }
    }
}
