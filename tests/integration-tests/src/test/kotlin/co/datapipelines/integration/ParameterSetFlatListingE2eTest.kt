package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.util.UUID

/**
 * **The flat parameter-set listing lists every set (#312)** — the acceptance of #312 over the
 * real HTTP stack.
 *
 * Three sets in two folders (`ps/alpha/hidden`, `ps/alpha/visible1`, `ps/beta/visible2`), so the
 * flat route's answer is proven to be a FLAT read and not the ROOT tree level: the name grammar
 * refuses a bare name (`folder_required`), so the root level holds no direct sets and the old
 * `listChildSets(…, null)` reuse answered `[]` with `total 0` for every workspace.
 *
 * The higher environment is a STUB on a loopback port (the [SchedulePromoterPagingE2eTest]
 * shape) holding the `hidden` set at its released version — invisible to the promoter's lens
 * (versioning §10.2: nothing newer to push), the other two visible. Walks:
 *
 * 1. the flat route as an admin (the everything lens): `limit=2` → two rows, `total 3`,
 *    `has_more true`; `offset=2` → one row, `has_more false`;
 * 2. the tree route (`?prefix=`) unchanged: the same sets browse by level;
 * 3. the flat route as the PROMOTER: `total 2` — the lens's size, never the workspace's, and no
 *    hidden name in any body.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ParameterSetFlatListingE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `fixture - three sets in two folders, released`() {
        seed()
        NAMES.forEach { name -> createAndRelease(name) }
    }

    @Test
    @Order(2)
    fun `the flat listing pages the whole workspace - two rows then one, total 3`() {
        val first =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .get("/api/v1/parameter-sets?offset=0&limit=2")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        withClue("non-vacuity: the first page must carry rows before the totals are judged") {
            first.getList<String>("data.items.name") shouldContainExactly listOf("ps/alpha/hidden", "ps/alpha/visible1")
        }
        withClue("the page arithmetic over three sets") {
            first.getInt("data.pagination.total") shouldBe 3
            first.getBoolean("data.pagination.has_more") shouldBe true
        }

        val last =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .get("/api/v1/parameter-sets?offset=2&limit=2")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        last.getList<String>("data.items.name") shouldContainExactly listOf("ps/beta/visible2")
        last.getInt("data.pagination.total") shouldBe 3
        last.getBoolean("data.pagination.has_more") shouldBe false
    }

    @Test
    @Order(3)
    fun `the tree route is unchanged - the same sets browse by level`() {
        val root =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .get("/api/v1/parameter-sets?prefix=")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        root.getList<String>("data.folders.segment") shouldContainExactly listOf("ps")
        root.getList<String>("data.parameter_sets") shouldBe emptyList()

        val alpha =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .get("/api/v1/parameter-sets?prefix=ps/alpha")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        alpha.getList<String>("data.parameter_sets.name") shouldContainExactly listOf("ps/alpha/hidden", "ps/alpha/visible1")
    }

    @Test
    @Order(4)
    fun `a promoter's lens hides the set the target already serves - total 2, no hidden name`() {
        val page =
            given()
                .port(port)
                .asSession(PROMOTER_SESSION)
                .get("/api/v1/parameter-sets")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        withClue("non-vacuity: the promoter's page must carry her visible sets before the total is judged") {
            page.getList<String>("data.items.name") shouldContainExactly listOf("ps/alpha/visible1", "ps/beta/visible2")
        }
        withClue("lens-true: the promoter learns her lens's size, never the workspace's 3") {
            page.getInt("data.pagination.total") shouldBe 2
        }
        withClue("no hidden name leaks") { page.getList<String>("data.items.name").none { it == "ps/alpha/hidden" } shouldBe true }
    }

    // ------------------------------------------------------------------------------- helpers

    private fun createAndRelease(name: String) {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "name" to name,
                    "display_name" to "PS $name",
                    "description" to "Flat-listing leg",
                    "parameters" to
                        listOf(
                            mapOf(
                                "name" to "country",
                                "label" to "Country",
                                "type" to "STRING",
                                "kind" to "SELECT",
                                "cardinality" to "SINGLE",
                                "required" to true,
                                "source" to
                                    mapOf(
                                        "constants" to
                                            listOf(
                                                mapOf("value" to "USA", "display_value" to "United States", "is_default" to true),
                                                mapOf("value" to "CAN", "display_value" to "Canada", "is_default" to false),
                                            ),
                                    ),
                                "presentation" to mapOf("control" to "dropdown"),
                            ),
                        ),
                ),
            )
        val created =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/v1/parameter-sets")
        check(created.statusCode == 201) { "set $name → ${created.statusCode}: ${created.body().asString().take(400)}" }
        val id = created.jsonPath().getString("data.id")
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .body("")
            .post("/api/v1/parameter-sets/$id/release")
            .then()
            .statusCode(200)
    }

    private fun seed() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Flat listing E2E')")
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'psfl-admin@e2e.test', 'PSFL Admin', 'test', 'psfl-admin-sub', TRUE, TRUE),
                ('$PROMOTER_ID', 'psfl-promoter@e2e.test', 'PSFL Promoter', 'test', 'psfl-promoter-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$PROMOTER_ID', 'promoter')
            """.trimIndent(),
        )
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private companion object {
        /** Name order interleaves the folders; `hidden` is the set the stub target already serves. */
        private val NAMES = listOf("ps/alpha/hidden", "ps/alpha/visible1", "ps/beta/visible2")

        private const val WORKSPACE = "psfl-e2e"
        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()
        private val PROMOTER_ID = UUID.randomUUID().toString()

        /** Per-run secrets — registered below and used to sign every session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "psfl-admin@e2e.test", WORKSPACE)
        private val PROMOTER_SESSION get() = E2eSession.jwt(JWT_SECRET, PROMOTER_ID, "psfl-promoter@e2e.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        /** The stub higher environment: it already holds the HIDDEN set at its released version. */
        private val stub: HttpServer by lazy {
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .also { server ->
                    server.createContext("/api/v1/promotion/inventory") { exchange ->
                        val body =
                            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat",""" +
                                """"authoring_enabled":false,"workspace":"$WORKSPACE",""" +
                                """"pipelines":[],"templates":[],"datasources":[],"parameter_sets":[""" +
                                """{"name":"ps/alpha/hidden","current_version":1,"body_hash":"on-target-hash"}]}}"""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    server.start()
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
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }

            // API mode: this suite only lists — no dispatching context may take other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }

            // The promoter lens reads the stub higher environment.
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { "psfl-e2e-server-key" }
            registry.add("datapipelines.deployment.promotion.inventory-cache-ttl-seconds") { "600" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
            stub.stop(0)
        }
    }
}
