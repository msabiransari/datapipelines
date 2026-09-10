package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * **The golden path an agent takes, end to end, under D55/D56** (099): create → execute → read →
 * *stop*, then a human releases and the next run serves the release.
 *
 * Every leg is driven over the product's own HTTP surfaces, because the ruling is about what
 * those surfaces DO, not about what the aggregate can be made to do. The unit and repository
 * suites prove each rule; what only this can prove is that the create response, the execute
 * default, the publish refusal, the release route and the executions record agree with each
 * other about which version is "the last version".
 *
 * The cases are ordered because they are one story, and each leg's assertion is chosen for what
 * it would catch:
 *
 * - **create answers `status: DRAFT` with `current_version: null`** — a create that landed
 *   RELEASED (the pre-099 rule) fails here first.
 * - **execute with NO version returns the draft's rows** — the whole of D56. The draft body
 *   selects a different column set from the released one, so running the wrong version cannot
 *   produce the right rows.
 * - **publishing an endpoint over it is refused** — released-only surfaces did not move, and this
 *   is the one an author meets immediately.
 * - **a human releases over `POST /pipelines/{id}/release`** — the first release of a pipeline,
 *   which before 099 could not exist (v1 was born released).
 * - **the execution rows say which version ran and whether it was a draft** — `draft_run` is
 *   true for the pre-release run and false for the post-release one, from the same endpoint.
 *
 * The source datasource is an in-JVM H2 (`DB_CLOSE_DELAY=-1`), so this suite adds no container
 * beyond the module's shared Postgres and Redis.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DraftFirstGoldenPathE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `an agent creates a pipeline and gets a DRAFT it can run, not a release`() {
        E2eClean.beforeSeeding()
        seedAuthRows()
        seedH2()
        registerH2Datasource()

        // The template is authored the same way, and lands the same way: a DRAFT.
        val template =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(
                    """
                    {"id": "$TEMPLATE_ID", "dialect": "H2", "display_name": "Draft-first template",
                     "description": "Selects the seeded rows", "imports": [],
                     "body": ${mapper.writeValueAsString(TEMPLATE_BODY)}}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/templates")
                .then()
                .statusCode(201)
                .extract()
        withClue("D55 — templates_create / POST /templates land version 1 as a DRAFT too") {
            template.jsonPath().getString("data.status") shouldBe "DRAFT"
            template.jsonPath().getInt("data.version") shouldBe 1
        }

        // A pipeline may PIN a draft template while iterating; it may not be RELEASED while the
        // pin is a draft (versioning §6), so the human releases the template first — which is
        // also this suite's proof that a template's FIRST release works from the create's draft.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .header(IF_MATCH, template.jsonPath().getString("data.body_hash"))
            .body("""{"name": "$TEMPLATE_ID"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
            .body("data.status", org.hamcrest.Matchers.equalTo("RELEASED"))

        val created =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(pipelineBody(SELECT_ID_ONLY))
                .`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()

        pipelineId = created.jsonPath().getString("data.id")
        draftHash = created.jsonPath().getString("data.body_hash")
        withClue("D55 — creation is authoring: v1 is a DRAFT nobody has reviewed") {
            created.jsonPath().getString("data.status") shouldBe "DRAFT"
            created.jsonPath().getInt("data.version") shouldBe 1
            created.jsonPath().get<Any?>("data.current_version").shouldBeNull()
            created.jsonPath().getInt("data.draft.version") shouldBe 1
        }
    }

    @Test
    @Order(2)
    fun `execute with no version runs the draft the agent just wrote`() {
        val events = execute()

        val dataReady = events.single { it.first == "data_ready" }.second
        withClue("D56 — the working version is the draft, and it is what ran") {
            events.single { it.first == "execution_started" }.second["pipeline_version"].asInt() shouldBe 1
        }
        withClue("the draft's projection — one column, because v1 declares the id-only default") {
            dataReady["schema"].size() shouldBe 1
        }
        dataReady["total_rows"].asLong() shouldBe SEEDED_ROWS.toLong()
        firstExecutionId = dataReady["execution_id"].asText()
    }

    @Test
    @Order(3)
    fun `an endpoint cannot be published over it until a human has released it`() {
        val refused =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body("""{"path": "/draftfirst/rows", "pipeline": "$PIPELINE_NAME"}""")
                .`when`()
                .post("/api/v1/endpoints")
                .thenReturn()

        withClue("released-only surfaces did not move; the refusal names the way forward") {
            refused.jsonPath().getString("error.code") shouldBe "endpoint.pipeline_not_released"
            refused.jsonPath().getString("error.message").contains("Release it from the UI first") shouldBe true
        }
    }

    @Test
    @Order(4)
    fun `a human releases it over the REST route, and the pointer moves to version 1`() {
        val released =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .header(IF_MATCH, draftHash)
                .`when`()
                .post("/api/v1/pipelines/$pipelineId/release")
                .then()
                .statusCode(200)
                .extract()

        withClue("the first release of a pipeline — a state that could not exist before 099") {
            released.jsonPath().getString("data.status") shouldBe "RELEASED"
            released.jsonPath().getInt("data.version") shouldBe 1
            released.jsonPath().getInt("data.current_version") shouldBe 1
        }
    }

    @Test
    @Order(5)
    fun `after the release the default is the release, and a new draft takes the default back`() {
        val afterRelease = execute()
        afterRelease.single { it.first == "execution_started" }.second["pipeline_version"].asInt() shouldBe 1

        // The agent iterates: a PUT opens draft v2, whose body selects a SECOND column, so the
        // rows themselves say which version ran.
        val updated =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .header(IF_MATCH, releasedHash())
                .body(pipelineBody(SELECT_ID_AND_LABEL))
                .`when`()
                .put("/api/v1/pipelines/$pipelineId")
                .then()
                .statusCode(200)
                .extract()
        updated.jsonPath().getString("data.status") shouldBe "DRAFT"
        updated.jsonPath().getInt("data.version") shouldBe 2
        withClue("the released pointer does not move while a draft exists (§3.4)") {
            updated.jsonPath().getInt("data.current_version") shouldBe 1
        }

        val onDraft = execute()
        val dataReady = onDraft.single { it.first == "data_ready" }.second
        withClue("'the LAST version' is the draft again — release v1 plus draft v2 runs v2") {
            onDraft.single { it.first == "execution_started" }.second["pipeline_version"].asInt() shouldBe 2
            // Two columns: only v2's body declares the id-and-label default.
            dataReady["schema"].size() shouldBe 2
        }
    }

    @Test
    @Order(6)
    fun `the executions surface says which version ran and marks the draft runs`() {
        val executions =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions?limit=50")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()

        val byId =
            executions
                .getList<Map<String, Any?>>("data.items")
                .associateBy { it["execution_id"] as String }
        withClue("the very first run was a draft run — no released_at existed at all") {
            byId.getValue(firstExecutionId)["pipeline_version"] shouldBe 1
            byId.getValue(firstExecutionId)["draft_run"] shouldBe true
        }
        withClue("the run of v2 is a draft run; every run of the released v1 after the release is not") {
            byId.values.single { it["pipeline_version"] == 2 }["draft_run"] shouldBe true
            byId.values.count { it["draft_run"] == false } shouldBe 1
        }
    }

    // ------------------------------------------------------------------------------- helpers

    /** Executes with NO `version` in the body — the whole point of the suite. */
    private fun execute(): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                    .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            E2eSse.parseEvents(response.body(), mapper)
        }

    private fun releasedHash(): String =
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/pipelines/$pipelineId")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("data.body_hash")

    private fun pipelineBody(select: String): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to 1,
                "name" to PIPELINE_NAME,
                "display_name" to "Draft-first golden path",
                "description" to "The 099 golden path",
                // The projection rides a DECLARED PARAMETER's DEFAULT, which is part of the
                // pipeline BODY — so v1 and v2 differ in what they RETURN, and a run of the
                // wrong version brings back the wrong column list. Nothing supplies `columns`
                // at execute time; the version that runs decides.
                "parameters" to
                    mapOf(
                        "columns" to
                            mapOf(
                                "type" to "STRING",
                                "required" to false,
                                "default" to select,
                                "description" to "Which projection this version of the pipeline selects.",
                            ),
                    ),
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "rows",
                            "type" to "DQL",
                            "source" to H2_DATASOURCE,
                            "description" to "Reads the seeded rows",
                            "template" to mapOf("id" to TEMPLATE_ID, "version" to 1),
                            "depends_on" to emptyList<String>(),
                        ),
                    ),
            ),
        )

    private fun registerH2Datasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$H2_DATASOURCE", "display_name": "Draft-first H2", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun seedH2() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE draft_first_rows (id INT PRIMARY KEY, label VARCHAR(64) NOT NULL)")
                (1..SEEDED_ROWS).forEach { statement.execute("INSERT INTO draft_first_rows VALUES ($it, 'row-$it')") }
            }
        }
    }

    private fun seedAuthRows() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', 'e2e-draft-first@datapipelines.test', 'E2E Draft First',
                            'test', 'e2e-draft-first-sub', TRUE, TRUE)
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

    companion object {
        private const val SECRET_BYTES = 32
        private const val SSE_BUDGET_MINUTES = 2L
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val IF_MATCH = "If-Match"

        private const val H2_DATASOURCE = "h2-draft-first"
        private const val H2_JDBC_URL = "jdbc:h2:mem:draftfirstdb;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"

        private const val TEMPLATE_ID = "test/draft_first_rows.sql"
        private const val PIPELINE_NAME = "test/draft_first_rows"
        private const val SEEDED_ROWS = 3

        /**
         * The node's `description` selects the projection, so v1 and v2 differ in their OUTPUT and
         * not merely in a field nobody reads: the template branches on it. A run of the wrong
         * version brings back the wrong column list, which no ordering accident can fake.
         */
        private const val SELECT_ID_ONLY = "id-only"
        private const val SELECT_ID_AND_LABEL = "id-and-label"

        private val TEMPLATE_BODY =
            """
            SELECT id<#if columns == "$SELECT_ID_AND_LABEL">, label</#if>
              FROM draft_first_rows
             ORDER BY id
            """.trimIndent()

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val random = SecureRandom()
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-draft-first-key", arrayOf("read", "execute", "author"))

        private var pipelineId: String = ""
        private var draftHash: String = ""
        private var firstExecutionId: String = ""

        private val postgres get() = SharedE2e.postgres

        private val redis get() = SharedE2e.redis

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
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            listOf("google").forEachIndexed { index, name ->
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
