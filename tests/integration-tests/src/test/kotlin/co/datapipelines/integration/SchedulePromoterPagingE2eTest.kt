package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
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
 * **The promoter's schedule list pages over the ADMITTED sequence** (#257, the HTTP surface).
 *
 * Six schedules over six released pipelines, named so hidden and visible rows interleave in the
 * name order the list answers (`pp/a_h1 … pp/f_v3`): three pipelines the higher environment
 * already holds at their released version (HIDDEN to the promoter — versioning §10.2: nothing
 * newer to push), three it has never seen (visible). A promoter paging two at a time must list
 * every visible schedule EXACTLY ONCE: on the base behaviour the lens filtered the database page
 * after it was cut, so a hidden row among the first `limit + 1` rows reported `has_more: false`
 * and the walk stopped after one page (d and f never listed).
 *
 * The higher environment is a STUB on a loopback port (the [PromoterLensSweepTest] shape); the
 * schedules are created by a workspace admin and never fire (yearly patterns).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SchedulePromoterPagingE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `a promoter paging the schedule list receives every visible schedule exactly once`() {
        seed()
        createPipelinesAndSchedules()

        val listed = ArrayList<String>()
        var offset = 0
        var hasMore: Boolean
        do {
            val page =
                session(PROMOTER_SESSION)
                    .get("/api/v1/schedules?offset=$offset&limit=$PAGE_SIZE")
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath()
            listed += page.getList<String>("data.items.name")
            hasMore = page.getBoolean("data.pagination.has_more")
            offset += PAGE_SIZE
        } while (hasMore)

        withClue("the promoter's walk sees the three visible schedules, each exactly once, in name order") {
            listed shouldContainExactly listOf("pp/b_v1", "pp/d_v2", "pp/f_v3")
        }
        withClue("no hidden name leaks into any page") {
            listed.filter { it.endsWith("_h1") || it.endsWith("_h2") || it.endsWith("_h3") } shouldHaveSize 0
        }
    }

    // ------------------------------------------------------------------------------- helpers

    private fun session(jwt: String) = given().port(port).asSession(jwt)

    private fun seed() {
        sql(
            """
            INSERT INTO workspaces (id, name, display_name) VALUES
                ('$WORKSPACE_ID', '$WORKSPACE', 'Promoter paging E2E')
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'pp-admin@e2e.test', 'PP Admin', 'test', 'pp-admin-sub', TRUE, TRUE),
                ('$PROMOTER_ID', 'pp-promoter@e2e.test', 'PP Promoter', 'test', 'pp-promoter-sub', TRUE, FALSE)
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

    private fun createPipelinesAndSchedules() {
        NAMES.forEach { name ->
            val visible = name.endsWith("_v1") || name.endsWith("_v2") || name.endsWith("_v3")
            val body =
                mapper.writeValueAsString(
                    mapOf(
                        "schema_version" to 1,
                        "name" to name,
                        "display_name" to "PP $name",
                        "description" to "Paging leg",
                        "nodes" to
                            listOf(
                                mapOf(
                                    "id" to "fq",
                                    "type" to "CALCULATOR",
                                    "kind" to "fiscal_quarter",
                                    "context_key" to "run_fiscal_quarter",
                                    "inputs" to mapOf("date" to "\$current_date", "fiscal_start" to "01-01"),
                                ),
                            ),
                    ),
                )
            val created = session(ADMIN_SESSION).contentType(ContentType.JSON).body(body).post("/api/v1/pipelines")
            check(created.statusCode == 201) { "pipeline $name → ${created.statusCode}: ${created.body().asString().take(400)}" }
            val id = created.jsonPath().getString("data.id")
            session(ADMIN_SESSION)
                .header("If-Match", created.jsonPath().getString("data.body_hash"))
                .post("/api/v1/pipelines/$id/release")
                .then()
                .statusCode(200)
            if (!visible) HIDDEN_NAMES += name

            val schedule =
                session(ADMIN_SESSION)
                    .contentType(ContentType.JSON)
                    .body(
                        mapper.writeValueAsString(
                            mapOf(
                                "name" to name,
                                "payload" to mapOf("pipeline" to name, "version" to "current"),
                                "cron" to "0 3 1 1 *",
                                "timezone" to "UTC",
                            ),
                        ),
                    ).post("/api/v1/schedules")
            check(schedule.statusCode == 201) { "schedule $name → ${schedule.statusCode}: ${schedule.body().asString().take(400)}" }
        }
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private companion object {
        private const val PAGE_SIZE = 2

        /** Name order interleaves hidden and visible — the case the base behaviour got wrong. */
        private val NAMES = listOf("pp/a_h1", "pp/b_v1", "pp/c_h2", "pp/d_v2", "pp/e_h3", "pp/f_v3")
        private val HIDDEN_NAMES = ArrayList<String>()

        private const val WORKSPACE = "pp-e2e"
        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()
        private val PROMOTER_ID = UUID.randomUUID().toString()

        /** Per-run secrets — registered below and used to sign every session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "pp-admin@e2e.test", WORKSPACE)
        private val PROMOTER_SESSION get() = E2eSession.jwt(JWT_SECRET, PROMOTER_ID, "pp-promoter@e2e.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        /** The stub higher environment: it already holds every HIDDEN pipeline at its released version. */
        private val stub: HttpServer by lazy {
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .also { server ->
                    server.createContext("/api/v1/promotion/inventory") { exchange ->
                        val entries =
                            HIDDEN_NAMES.joinToString(",") { name ->
                                """{"name":"$name","current_version":1,"body_hash":"hash-on-target"}"""
                            }
                        val body =
                            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat",""" +
                                """"authoring_enabled":false,"workspace":"$WORKSPACE",""" +
                                """"pipelines":[$entries],"templates":[],"datasources":[]}}"""
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
            registry.add("datapipelines.deployment.promotion.target.server-key") { "pp-e2e-server-key" }
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
