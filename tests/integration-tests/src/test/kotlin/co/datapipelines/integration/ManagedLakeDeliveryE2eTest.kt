package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Real REST/system entry points, production runtime, native staging, fenced catalog and immutable S3 recipes. */
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ManagedLakeDeliveryE2eTest {
    @LocalServerPort private var port = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var context: org.springframework.context.ApplicationContext
    private val runtime get() = ManagedLakeRuntimeAccess(context)
    private val json = ObjectMapper()

    @BeforeAll
    fun seed() {
        E2eClean.beforeSeeding()
        runtime.restoreStartupSeeds()
        jdbc.update("INSERT INTO workspaces (id, name, display_name) VALUES (?, ?, 'P4 delivery')", WORKSPACE, WORKSPACE_NAME)
        listOf(ADMIN to "super_admin", AUTHOR to "author", VIEWER to "viewer").forEach { (id, role) ->
            jdbc.update(
                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) " +
                    "VALUES (?, ?, ?, 'test', ?, TRUE, ?)",
                id,
                "$role@p4delivery.test",
                role,
                id.toString(),
                id == ADMIN,
            )
            if (id !=
                ADMIN
            ) {
                jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)", WORKSPACE, id, role)
            }
        }
        val created =
            given()
                .port(port)
                .asSession(jwt(ADMIN))
                .contentType(ContentType.JSON)
                .body(
                    json.writeValueAsString(
                        mapOf(
                            "name" to LAKE,
                            "display_name" to "P4 delivery",
                            "dialect" to "LAKE",
                            "jdbc_url" to "jdbc:duckdb::memory:",
                            "readonly" to true,
                            "credential" to
                                mapOf("kind" to "password", "username" to SharedE2e.MINIO_USER, "secret" to SharedE2e.MINIO_PASSWORD),
                            "properties" to
                                mapOf(
                                    "dialect" to
                                        mapOf(
                                            "catalog.kind" to "s3",
                                            "region" to "us-east-1",
                                            "endpoint" to SharedE2e.minioEndpoint,
                                            "url_style" to "path",
                                        ),
                                ),
                        ),
                    ),
                ).post("/api/v1/datasources")
                .then()
                .extract()
                .response()
        withClue("datasource creation code=${created.jsonPath().getString("error.code")}") { created.statusCode() shouldBe 201 }
        runtime.createTable(ADMIN)
    }

    @AfterAll
    fun closeDiscovery() {
        oidc.close()
    }

    @Test
    fun `V55 rolls down and up on a populated metadata copy while preserving every existing row`() {
        createPipeline()
        ManagedLakeMigrationRehearsal.run()
    }

    @Test
    fun `viewer is refused before admission and author publishes once through REST`() {
        val pipeline = createPipeline()
        val batch = putDelivery()
        given()
            .port(port)
            .asSession(jwt(VIEWER))
            .contentType(ContentType.JSON)
            .body(parameters(batch))
            .post("/api/v1/pipelines/$pipeline/execute")
            .then()
            .statusCode(403)
        deliveryCount(batch) shouldBe 0
        val events = execute(pipeline, batch)
        events.contains("pipeline_completed") shouldBe true
        state(batch) shouldBe "PUBLISHED"
        ManagedLakeRecipeAssertions.complete(jdbc, batch, BUCKET)
        val attempts = attemptCount(batch)
        execute(pipeline, batch).contains("pipeline_completed") shouldBe true
        attemptCount(batch) shouldBe attempts
        jdbc.queryForObject(
            "SELECT count(*) FROM lake_table_revisions r JOIN lake_delivery_attempts a ON a.operation_id = r.operation_id " +
                "JOIN lake_deliveries d ON d.id = a.delivery_id WHERE d.delivery_id = ?",
            Int::class.java,
            batch,
        ) shouldBe 1
        println("case=rest_author_duplicate ledger=PUBLISHED attempts=$attempts revisions=1 viewer=403")
    }

    @Test
    fun `stripping the entry grant refuses the actual recording runner before data or recipe publication`() {
        val id = createPipeline()
        val batch = putDelivery()
        runtime.withoutGrant(WORKSPACE, UUID.fromString(id), AUTHOR, mapOf("batch_id" to json.valueToTree(batch))) shouldBe "FAILED"
        state(batch) shouldBe "FAILED"
        jdbc.queryForObject("SELECT failure_code FROM lake_deliveries WHERE delivery_id = ?", String::class.java, batch) shouldBe
            "lake.append.grant_missing"
        jdbc.queryForObject(
            "SELECT count(*) FROM lake_delivery_attempts a JOIN lake_deliveries d ON d.id = a.delivery_id " +
                "WHERE d.delivery_id = ? AND a.recipe_key IS NOT NULL",
            Int::class.java,
            batch,
        ) shouldBe 0
    }

    @Test
    fun `the scheduler entry point supplies the system actor's grant and publishes the actual delivery`() {
        val id = createPipeline()
        val batch = putDelivery()
        val values = json.readTree(parameters(batch)).path("parameters")
        val execution = runtime.schedule(WORKSPACE, UUID.fromString(id), values)
        await { state(batch) == "PUBLISHED" }
        jdbc.queryForObject("SELECT triggered_via FROM pipeline_executions WHERE execution_id = ?", String::class.java, execution) shouldBe
            "SCHEDULE"
        println("case=system_schedule ledger=PUBLISHED revisions=1")
    }

    @Test
    fun `an author MCP key keeps its own authority when its creator is now a viewer`() {
        val id = createPipeline()
        val batch = putDelivery()
        val key = runtime.authorKey(WORKSPACE, WORKSPACE_NAME, AUTHOR)
        jdbc.update("UPDATE workspace_members SET role = 'viewer' WHERE workspace_id = ? AND user_id = ?", WORKSPACE, AUTHOR)
        runtime.invalidateMemberships(AUTHOR)
        try {
            jdbc.queryForObject(
                "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                String::class.java,
                WORKSPACE,
                AUTHOR,
            ) shouldBe "viewer"
            val mcp = ManagedLakeMcpClient(port, key)
            runtime.templateIds(WORKSPACE, UUID.fromString(id)).forEach { template ->
                val (payload, refused) = mcp.call("templates_render", mapOf("id" to template, "context" to emptyMap<String, Any>()))
                withClue("render refused: $payload") { refused shouldBe false }
            }
            val (payload, refused) = mcp.call("pipelines_execute", mapOf("id" to id, "parameters" to mapOf("batch_id" to batch)))
            withClue("execute refused: $payload") { refused shouldBe false }
            await { state(batch) == "PUBLISHED" }
            println("case=mcp_key_role creator=viewer key=author ledger=PUBLISHED")
        } finally {
            jdbc.update("UPDATE workspace_members SET role = 'author' WHERE workspace_id = ? AND user_id = ?", WORKSPACE, AUTHOR)
            runtime.invalidateMemberships(AUTHOR)
        }
    }

    @Test
    fun `a slow strictly failing validation prevents the fast append from ever starting`() {
        val fixture = ManagedLakeBarrierFixture(port, jwt(ADMIN), LAKE)
        fixture.verify(jdbc, putDelivery(), ::execute)
    }

    @Test
    @Suppress("NestedBlockDepth") // Hold the real lock until both scheduled calls and their JDBC assertions finish.
    fun `a real control row lock cannot starve the actual pool reaper behind the bounded sweep`() {
        requireNotNull(jdbc.dataSource).connection.use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.executeQuery("SELECT id FROM lake_control WHERE id = 1 FOR UPDATE").close() }
            try {
                runtime.sweepThenReap()
                // The holder is still live: the five-second statement bound, not lock release, freed the scheduler thread.
                holder.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { rows -> rows.next() shouldBe true }
                }
                println("case=bounded_sweep held_control_lock=true actual_reaper_returned=true")
            } finally {
                holder.rollback()
            }
        }
    }

    private fun createPipeline(): String = ManagedLakeDeliveryHttp(port, jwt(ADMIN), LAKE, BUCKET).createPipeline()

    private fun putDelivery(): String = ManagedLakeDeliveryHttp(port, jwt(ADMIN), LAKE, BUCKET).putDelivery()

    private fun execute(
        id: String,
        batch: String,
    ): String {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$id/execute"))
                .timeout(Duration.ofSeconds(45))
                .header("Cookie", E2eSession.cookieHeader(jwt(AUTHOR)))
                .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(parameters(batch)))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        return response.body()
    }

    private fun parameters(batch: String) = json.writeValueAsString(mapOf("parameters" to mapOf("batch_id" to batch)))

    private fun deliveryCount(batch: String) =
        jdbc.queryForObject("SELECT count(*) FROM lake_deliveries WHERE delivery_id = ?", Int::class.java, batch)

    private fun attemptCount(batch: String) =
        jdbc.queryForObject(
            "SELECT count(*) FROM lake_delivery_attempts a " +
                "JOIN lake_deliveries d ON d.id = a.delivery_id WHERE d.delivery_id = ?",
            Int::class.java,
            batch,
        )

    private fun state(batch: String): String? =
        jdbc.queryForList("SELECT state FROM lake_deliveries WHERE delivery_id = ?", String::class.java, batch).singleOrNull()

    private fun jwt(user: UUID): String {
        val role =
            when (user) {
                ADMIN -> "super_admin"
                AUTHOR -> "author"
                else -> "viewer"
            }
        return E2eSession.jwt(SECRET, user.toString(), "$role@p4delivery.test", WORKSPACE_NAME)
    }

    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + Duration.ofSeconds(45).toNanos()
        while (!predicate()) {
            check(System.nanoTime() < end) { "Delivery did not reach the expected durable state." }
            Thread.sleep(20)
        }
    }

    companion object {
        private val SECRET = E2eSession.newSecret()
        private val oidc = OidcDiscoveryStub()
        private val WORKSPACE = UUID.randomUUID()
        private val ADMIN = UUID.randomUUID()
        private val AUTHOR = UUID.randomUUID()
        private val VIEWER = UUID.randomUUID()
        private const val WORKSPACE_NAME = "p4delivery"
        private const val LAKE = "lake799e2e"
        private const val BUCKET = "dp799-delivery-e2e"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            ManagedLakeDeliveryProperties.register(registry, SECRET, oidc.issuer, LAKE, BUCKET)
        }
    }
}
