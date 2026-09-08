package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

/**
 * Published endpoints, end to end over HTTP (074, rest-api §19).
 *
 * Everything here is driven through the product's own surfaces — publish over
 * `POST /api/v1/endpoints`, mint-and-bind over `POST /api/v1/auth/api-keys`, call over
 * `GET /api/x/…` — because that is the only way to prove the pieces agree. The unit suites
 * already prove each rule in isolation; what they cannot prove is that the matcher, the
 * authorizer, the validator, the executor and the result store are wired to each other.
 *
 * The cases are chosen for what they would catch, not for coverage:
 *
 * - **`has_more` with `DP-Result-Page-Rows: 1`** — R-EP4's header is a real clamp on a real
 *   store read, not a field that is echoed back. With the header ignored the seeded rows fit in
 *   one page and `has_more` is false, so this fails.
 * - **three simultaneous request defects** — one `400` carrying all of them; a fail-fast
 *   validator reports one and passes every other assertion in this file.
 * - **an endpoint key on a DIFFERENT published subtree** — 403 rather than 404, which is what
 *   separates "no binding decides here" from "nothing is published here".
 * - **a DML pipeline refused at publish** — the rule that makes serving over `GET` safe, checked
 *   where an author meets it.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishedEndpointE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var endpointKey: String
    private lateinit var foreignKey: String

    @BeforeAll
    fun seed() {
        E2eClean.beforeSeeding()
        seedRows()
        seedSourceData()
        registerDatasource()
        createTemplates()
        createPipelines()
        publishEndpoints()
        endpointKey = mintEndpointKey()
        foreignKey = FOREIGN_KEY.plaintext
    }

    // -------------------------------------------------------------------------------------
    // The happy path
    // -------------------------------------------------------------------------------------

    @Test
    fun `a bound endpoint key gets the data_ready payload with the first page and the cursor`() {
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/x/nyc/revenue/Manhattan")
            .then()
            .statusCode(200)
            .header("Cache-Control", equalTo("no-store"))
            .header("DP-Execution-Id", org.hamcrest.Matchers.notNullValue())
            // The §6.4.7 payload, verbatim — every field a client codes against.
            .body("execution_id", org.hamcrest.Matchers.notNullValue())
            .body("schema.size()", equalTo(2))
            .body("rows.size()", equalTo(3))
            .body("row_count", equalTo(3))
            .body("total_rows", equalTo(3))
            .body("has_more", equalTo(false))
            .body("result_url", org.hamcrest.Matchers.containsString("/executions/"))
            .body("expires_at", org.hamcrest.Matchers.notNullValue())
            .body("ttl_seconds", org.hamcrest.Matchers.notNullValue())
            // The path variable actually filtered: Manhattan has 3 of the 5 seeded rows.
            .body("rows[0][0]", equalTo("Manhattan"))
    }

    @Test
    fun `DP-Result-Page-Rows clamps the inline page and has_more says so`() {
        // R-EP4. Ignoring the header would return all 3 rows with has_more false — this is the
        // assertion that makes the header a real clamp on a real store read.
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .header("DP-Result-Page-Rows", "1")
            .`when`()
            .get("/api/x/nyc/revenue/Manhattan")
            .then()
            .statusCode(200)
            .body("rows.size()", equalTo(1))
            .body("row_count", equalTo(1))
            .body("total_rows", equalTo(3))
            .body("has_more", equalTo(true))
    }

    @Test
    fun `the cursor serves the rest of the result to the same endpoint key`() {
        // §7.7 — an endpoint key may read the cursor of an execution IT started, proved by the
        // serve audit row rather than by triggered_by (which is the key's owner).
        val executionId =
            given()
                .port(port)
                .header(API_KEY_HEADER, endpointKey)
                .`when`()
                .get("/api/x/nyc/revenue/Manhattan")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("execution_id")

        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/v1/executions/$executionId/result")
            .then()
            .statusCode(200)
            .body("data.rows.size()", equalTo(3))
    }

    // -------------------------------------------------------------------------------------
    // Request validation (§19.3)
    // -------------------------------------------------------------------------------------

    @Test
    fun `a bad parameter type is one 400 naming one error`() {
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/x/nyc/revenue/Manhattan?start_date=bogus")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.request.invalid"))
            .body("error.details.errors", hasSize<Any>(1))
            .body("error.details.errors[0].parameter", equalTo("start_date"))
            .body("error.details.errors[0].code", equalTo("pipeline.execution.invalid_parameter_type"))
    }

    @Test
    fun `an unknown query parameter is refused rather than ignored`() {
        // Strict on purpose: ParameterBinder ignores undeclared inputs, which is right for the
        // execute body and wrong here — a typo would silently run the default and return
        // plausible, wrong rows.
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/x/nyc/revenue/Manhattan?nope=1")
            .then()
            .statusCode(400)
            .body("error.details.errors[0].code", equalTo("endpoint.request.parameter_unknown"))
    }

    @Test
    fun `three defects are reported together, not one at a time`() {
        // The design's headline property. A fail-fast validator passes every other assertion in
        // this file and fails only this one.
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/x/nyc/revenue/Manhattan?start_date=bogus&nope=1&also_nope=2")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.request.invalid"))
            .body("error.details.errors", hasSize<Any>(3))
    }

    @Test
    fun `an unacceptable Accept is 406, not one more entry in the 400`() {
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .header("Accept", "text/csv")
            .`when`()
            .get("/api/x/nyc/revenue/Manhattan")
            .then()
            .statusCode(406)
            .body("error.code", equalTo("endpoint.not_acceptable"))
    }

    // -------------------------------------------------------------------------------------
    // Authorisation (§7.7)
    // -------------------------------------------------------------------------------------

    @Test
    fun `a user key of another workspace is refused`() {
        given()
            .port(port)
            .header(API_KEY_HEADER, foreignKey)
            .`when`()
            .get("/api/x/nyc/revenue/Manhattan")
            .then()
            .statusCode(403)
    }

    @Test
    fun `the endpoint key is refused on a published subtree it is not bound to`() {
        // 403, not 404: /trade/summary IS published, so the refusal is the authorisation rule
        // speaking. That is what separates "no binding decides here" from "nothing is here".
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/x/trade/summary")
            .then()
            .statusCode(403)
            .body("error.code", equalTo("endpoint.key_kind_refused"))
    }

    @Test
    fun `an unauthenticated call is 401`() {
        given()
            .port(port)
            .`when`()
            .get("/api/x/nyc/revenue/Manhattan")
            .then()
            .statusCode(401)
    }

    // -------------------------------------------------------------------------------------
    // The status table (§19.6)
    // -------------------------------------------------------------------------------------

    @Test
    fun `any method but GET is 405 with Allow GET`() {
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .contentType(ContentType.JSON)
            .body("{}")
            .`when`()
            .post("/api/x/nyc/revenue/Manhattan")
            .then()
            .statusCode(405)
            .header("Allow", equalTo("GET"))
            .body("error.code", equalTo("endpoint.method_not_allowed"))
    }

    @Test
    fun `an unpublished path is 404`() {
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/x/nothing/here")
            .then()
            .statusCode(404)
            .body("error.code", equalTo("endpoint.not_found"))
    }

    // -------------------------------------------------------------------------------------
    // Publish-time refusals (§19.2)
    // -------------------------------------------------------------------------------------

    @Test
    fun `a pipeline with a DML node cannot be published`() {
        // The rule that makes GET safe, met where an author meets it.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body("""{"path": "/writes/things", "pipeline": "test/writes_things"}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(409)
            .body("error.code", equalTo("endpoint.pipeline_not_readonly"))
            .body("error.details.node_id", equalTo("insert_row"))
    }

    @Test
    fun `a path that could match an existing one is refused, naming the other`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            // A LITERAL third segment, not another variable: §4.2's checks run cheapest-first, so
            // `/nyc/revenue/{anything}` would be refused as an undeclared path variable (400)
            // before it ever reached the conflict check. This one is a legal path that happens to
            // collide, which is exactly what `endpoint.path_conflict` is for.
            .body("""{"path": "/nyc/revenue/manhattan", "pipeline": "test/revenue_by_borough"}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(409)
            .body("error.code", equalTo("endpoint.path_conflict"))
            .body("error.details.conflicting_path", equalTo("/nyc/revenue/{borough}"))
    }

    @Test
    fun `a path variable the pipeline does not declare is refused`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body("""{"path": "/nyc/by/{ghost}", "pipeline": "test/revenue_by_borough"}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.path_variable_unknown"))
    }

    // -------------------------------------------------------------------------------------
    // Timeout (§19.4 / ruling R-EP3)
    // -------------------------------------------------------------------------------------

    @Test
    fun `a serve that outlives its timeout is 202 and the execution keeps running`() {
        // R-EP3's whole point: NOT a 504. The upstream is fine and the answer is coming, so the
        // response names the execution and the cursor to come back to — and the run must still
        // be alive to finish, which the second half of this test proves by reading its result.
        val accepted =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/x/slow/thing")
                .then()
                .statusCode(202)
                .header("DP-Execution-Id", org.hamcrest.Matchers.notNullValue())
                .body("execution_id", org.hamcrest.Matchers.notNullValue())
                .body("result_url", org.hamcrest.Matchers.containsString("/executions/"))
                .body("status_url", org.hamcrest.Matchers.notNullValue())
                .body("expires_at", org.hamcrest.Matchers.notNullValue())
                .extract()
                .jsonPath()
                .getString("execution_id")

        // The execution was NOT cancelled by the expiring wait: withTimeoutOrNull cancels the
        // AWAIT, and the Deferred belongs to the application scope. If it had been cancelled, the
        // cursor would never serve and this loop would exhaust its budget.
        val deadline = System.currentTimeMillis() + CURSOR_BUDGET_MS
        var status = 0
        while (System.currentTimeMillis() < deadline && status != 200) {
            Thread.sleep(POLL_MS)
            status =
                given()
                    .port(port)
                    .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                    .`when`()
                    .get("/api/v1/executions/$accepted/result")
                    .then()
                    .extract()
                    .statusCode()
        }

        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/executions/$accepted/result")
            .then()
            .statusCode(200)
            .body("data.rows.size()", equalTo(1))
    }

    // -------------------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------------------

    private fun mintEndpointKey(): String =
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body("""{"name": "nyc-serving", "kind": "endpoint", "bindings": ["/nyc"]}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(201)
            .body("data.kind", equalTo("endpoint"))
            // An endpoint key carries NO scopes; its authority is the binding.
            .body("data.scopes", hasSize<Any>(0))
            .extract()
            .jsonPath()
            .getString("data.key")

    private fun publishEndpoints() {
        publish("/nyc/revenue/{borough}", "test/revenue_by_borough")
        publish("/trade/summary", "test/trade_summary")
        // timeout_seconds = 1 against a 3-second query: the 202 path, deterministically.
        publishWithTimeout("/slow/thing", "test/slow_sleep", 1)
    }

    private fun publish(
        path: String,
        pipeline: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body("""{"path": "$path", "pipeline": "$pipeline", "timeout_seconds": 60}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(201)
    }

    private fun publishWithTimeout(
        path: String,
        pipeline: String,
        timeoutSeconds: Int,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body("""{"path": "$path", "pipeline": "$pipeline", "timeout_seconds": $timeoutSeconds}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(201)
    }

    private fun registerDatasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "ep-source", "display_name": "Endpoint source", "dialect": "POSTGRES",
                 "jdbc_url": "${source.jdbcUrl}", "username": "${source.username}", "password": "${source.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun createTemplates() {
        template(
            "test/revenue_by_borough.sql",
            "SELECT borough, revenue FROM boroughs WHERE borough = :borough AND as_of >= :start_date ORDER BY revenue DESC",
        )
        template("test/trade_summary.sql", "SELECT borough, revenue FROM boroughs ORDER BY revenue DESC")
        template("test/insert_row.sql", "INSERT INTO boroughs (borough, revenue, as_of) VALUES ('X', 1, DATE '2024-01-01')")
        // The multi-instance harness's pg_sleep idea, shortened: long enough to outlive a
        // 1-second endpoint timeout, short enough that the cursor serves within this test.
        template("test/slow.sql", "SELECT pg_sleep(3) AS slept, 1 AS marker")
    }

    private fun template(
        id: String,
        body: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "$id", "dialect": "POSTGRES", "display_name": "$id",
                 "description": "074 endpoint E2E fixture.", "imports": [], "body": "$body"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
            .body("data.status", equalTo("DRAFT"))

        // Templates lock first (versioning §6): the pipelines above are RELEASED, and a released
        // pipeline may only pin RELEASED template versions.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .header("If-Match", releasedHashOfTemplate(id))
            .body("""{"name": "$id"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    /** The draft hash of the template just created — the precondition its release carries. */
    private fun releasedHashOfTemplate(id: String): String =
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .queryParam("name", id)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("data.body_hash")

    private fun createPipelines() {
        pipeline(
            """
            {"schema_version": 1, "name": "test/revenue_by_borough", "display_name": "Revenue by borough",
             "description": "074 E2E.",
             "parameters": {"borough": {"type": "STRING", "required": true},
                            "start_date": {"type": "DATE", "required": false, "default": "2024-01-01"}},
             "nodes": [{"id": "revenue", "description": "Revenue for one borough", "type": "DQL",
                        "source": "ep-source", "template": {"id": "test/revenue_by_borough.sql", "version": 1},
                        "depends_on": []}]}
            """.trimIndent(),
        )
        pipeline(
            """
            {"schema_version": 1, "name": "test/trade_summary", "display_name": "Trade summary",
             "description": "074 E2E — a second published subtree.", "parameters": {},
             "nodes": [{"id": "summary", "description": "All rows", "type": "DQL",
                        "source": "ep-source", "template": {"id": "test/trade_summary.sql", "version": 1},
                        "depends_on": []}]}
            """.trimIndent(),
        )
        pipeline(
            """
            {"schema_version": 1, "name": "test/slow_sleep", "display_name": "Slow sleep",
             "description": "074 E2E — outlives a 1s endpoint timeout.", "parameters": {},
             "nodes": [{"id": "sleep", "description": "pg_sleep(3)", "type": "DQL",
                        "source": "ep-source", "template": {"id": "test/slow.sql", "version": 1},
                        "depends_on": []}]}
            """.trimIndent(),
        )
        pipeline(
            """
            {"schema_version": 1, "name": "test/writes_things", "display_name": "Writes things",
             "description": "074 E2E — the read-only rule's negative fixture.", "parameters": {},
             "nodes": [{"id": "insert_row", "description": "A write", "type": "DML",
                        "source": "ep-source", "template": {"id": "test/insert_row.sql", "version": 1},
                        "depends_on": []}]}
            """.trimIndent(),
        )
    }

    /**
     * Creates the pipeline and RELEASES it — an endpoint serves the latest RELEASED version and
     * nothing else (§5.1), and since D55 a create lands a DRAFT, so a fixture that only created
     * would be publishing over something this surface is right to refuse. The release is the human
     * step the product requires, done here the way a person does it.
     */
    private fun pipeline(body: String) {
        val created =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(body)
                .`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .body("data.status", equalTo("DRAFT"))
                .extract()

        val id = created.jsonPath().getString("data.id")
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))
    }

    /** The rows the endpoint serves: 3 Manhattan, 2 elsewhere, so a path variable can be seen to filter. */
    private fun seedSourceData() {
        DriverManager.getConnection(source.jdbcUrl, source.username, source.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE boroughs (borough TEXT NOT NULL, revenue INT NOT NULL, as_of DATE NOT NULL)")
                statement.execute(
                    """
                    INSERT INTO boroughs (borough, revenue, as_of) VALUES
                      ('Manhattan', 300, DATE '2024-03-01'),
                      ('Manhattan', 200, DATE '2024-02-01'),
                      ('Manhattan', 100, DATE '2024-01-01'),
                      ('Queens', 90, DATE '2024-01-01'),
                      ('Bronx', 80, DATE '2024-01-01')
                    """.trimIndent(),
                )
            }
        }
    }

    /** Users, workspaces and the two SQL-seeded keys. The endpoint key is minted over REST. */
    private fun seedRows() {
        DriverManager
            .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER', 'ep-admin@datapipelines.test', 'EP Admin', 'test', 'ep-admin', TRUE, TRUE)
                        """.trimIndent(),
                    )
                    // A SECOND workspace, so "a user key of another workspace" is a real refusal
                    // rather than a missing membership.
                    statement.execute(
                        """
                        INSERT INTO workspaces (id, name, display_name, created_by)
                        VALUES ('$OTHER_WORKSPACE', 'other', 'Other workspace', '$ADMIN_USER')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_members (workspace_id, user_id, role)
                        VALUES ('$OTHER_WORKSPACE', '$ADMIN_USER', 'owner')
                        """.trimIndent(),
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id, kind)" +
                            " VALUES (?, ?, ?, ?, ?, ?::uuid, ?)",
                    ).use { ps ->
                        listOf(ADMIN_KEY to DEFAULT_WORKSPACE, FOREIGN_KEY to OTHER_WORKSPACE).forEach { (key, workspace) ->
                            ps.setString(1, key.id)
                            ps.setObject(2, UUID.fromString(ADMIN_USER))
                            ps.setString(3, key.name)
                            ps.setString(4, key.hash)
                            ps.setArray(5, connection.createArrayOf("text", key.scopes))
                            ps.setString(6, workspace)
                            ps.setString(7, "user")
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
            }
    }

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val OTHER_WORKSPACE = "defa0000-0000-0000-0000-0000000000ee"
        private val ADMIN_USER: String = UUID.randomUUID().toString()

        private val ADMIN_KEY = E2eAuth.generateKey("ep-admin-key", arrayOf("admin"))
        private val FOREIGN_KEY = E2eAuth.generateKey("ep-foreign-key", arrayOf("execute"))

        private val postgres get() = SharedE2e.postgres
        private val source = SharedE2e.scratchDatabase("endpoints_source")
        private val redis get() = SharedE2e.redis

        private const val SECRET_BYTES = 32

        /** Generous: the run is ~3s and a shared box is slow; the assertion is convergence. */
        private const val CURSOR_BUDGET_MS = 30_000L
        private const val POLL_MS = 250L

        private fun randomSecret(): String =
            Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { java.security.SecureRandom().nextBytes(it) })

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
            // Local accounts rather than an OIDC stub: §7 needs SOME auth method configured, and
            // every request here presents an API key.
            registry.add("datapipelines.auth.local.enabled") { "true" }
        }
    }
}
