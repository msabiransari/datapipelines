package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
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
 * `POST /api/v1/endpoints` and mint-and-bind over `POST /api/v1/auth/api-keys` as a signed-in
 * admin (REST is a session's surface since #215 B2), call over `GET /api/nyc/v1/…` with the
 * `api_caller` key — because that is the only way to prove the pieces agree. The unit suites
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
@Suppress("LargeClass") // one published tree, one seed; the cases share it rather than re-seeding
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishedEndpointE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var endpointKey: String
    private lateinit var foreignKey: String
    private lateinit var revenuePipelineId: String

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
            .get("/api/nyc/v1/revenue/Manhattan")
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
            .get("/api/nyc/v1/revenue/Manhattan")
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
        // serve audit row rather than by executed_by (which is the key's owner).
        val executionId =
            given()
                .port(port)
                .header(API_KEY_HEADER, endpointKey)
                .`when`()
                .get("/api/nyc/v1/revenue/Manhattan")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("execution_id")

        // Keys v2 A16: the key's own-run cursor rides the business path, not the framework read.
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/nyc/v1/revenue/Manhattan/executions/$executionId/result")
            .then()
            .statusCode(200)
            .body("data.rows.size()", equalTo(3))
    }

    @Test
    fun `a pipeline with a tempdb DDL node publishes and serves — the read-only rule's positive case`() {
        // #171: `index_stg` is a real CREATE INDEX statement, against `tempdb` — the snow-days
        // shape the rule used to refuse outright. publishEndpoints() already proved the 201; this
        // proves the served rows are the indexed table's, read back correctly.
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/nyc/v1/tempdb-ddl")
            .then()
            .statusCode(200)
            .body("rows.size()", equalTo(5))
            .body("row_count", equalTo(5))
            .body("has_more", equalTo(false))
            // Descending by revenue, exactly as the report template ordered the indexed table.
            .body("rows[0][0]", equalTo("Manhattan"))
            .body("rows[0][1]", equalTo(300))
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
            .get("/api/nyc/v1/revenue/Manhattan?start_date=bogus")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.request.invalid"))
            .body("error.details.errors", hasSize<Any>(1))
            .body("error.details.errors[0].parameter", equalTo("start_date"))
            .body("error.details.errors[0].code", equalTo("pipeline.execution.invalid_parameter_type"))
    }

    @Test
    fun `a padded number is refused at a published endpoint, never trimmed (#194)`() {
        // A deliberate break of 2026-09-26 (rest-api change log): a BIGDECIMAL used to be
        // trimmed inside the coercion, so this URL ran as 12.50.
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .queryParam("min_revenue", " 12.50 ")
            .`when`()
            .get("/api/nyc/v1/revenue/Manhattan")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.request.invalid"))
            .body("error.details.errors", hasSize<Any>(1))
            .body("error.details.errors[0].parameter", equalTo("min_revenue"))
            .body("error.details.errors[0].code", equalTo("pipeline.execution.invalid_parameter_type"))
    }

    @Test
    fun `a padded number is refused at the execute API, never trimmed (#194)`() {
        // The same value through POST /api/v1/pipelines/{id}/execute — the one binder both
        // surfaces share refuses it before anything runs.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"parameters": {"borough": "Manhattan", "min_revenue": " 12.50 "}}""")
            .`when`()
            .post("/api/v1/pipelines/$revenuePipelineId/execute")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("pipeline.execution.invalid_parameter_type"))
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
            .get("/api/nyc/v1/revenue/Manhattan?nope=1")
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
            .get("/api/nyc/v1/revenue/Manhattan?start_date=bogus&nope=1&also_nope=2")
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
            .get("/api/nyc/v1/revenue/Manhattan")
            .then()
            .statusCode(406)
            .body("error.code", equalTo("endpoint.not_acceptable"))
    }

    // -------------------------------------------------------------------------------------
    // Authorisation (§7.7)
    // -------------------------------------------------------------------------------------

    @Test
    fun `an MCP key is refused on a published endpoint - its own workspace's or another's`() {
        // #215 B2: the MCP key reaches /mcp and nothing else — refused as a KIND before any
        // binding is consulted, the same answer for the admin's own workspace and another one.
        listOf(ADMIN_KEY.plaintext, foreignKey).forEach { key ->
            given()
                .port(port)
                .header(API_KEY_HEADER, key)
                .`when`()
                .get("/api/nyc/v1/revenue/Manhattan")
                .then()
                .statusCode(403)
                .body("error.code", equalTo("endpoint.key_kind_refused"))
                .body("error.details.reason", equalTo("mcp_key_off_surface"))
        }
    }

    @Test
    fun `an unbound published path is served to no one - no key, no session`() {
        // #215 B3: /trade/v1/summary IS published and nothing is bound on its ancestors. Before
        // #215 a same-workspace user key holding `execute` could call it; that branch is gone.
        // The endpoint key bound elsewhere is refused by the authorizer (next test), the admin's
        // MCP key by its kind, and a signed-in session because this is a machine surface.
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/trade/v1/summary")
            .then()
            .statusCode(403)
            .body("error.details.reason", equalTo("mcp_key_off_surface"))
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/trade/v1/summary")
            .then()
            .statusCode(401)
    }

    @Test
    fun `the endpoint key is refused on a published subtree it is not bound to`() {
        // 403, not 404: /trade/v1/summary IS published, so the refusal is the authorisation rule
        // speaking. That is what separates "no binding decides here" from "nothing is here".
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/trade/v1/summary")
            .then()
            .statusCode(403)
            .body("error.code", equalTo("endpoint.key_kind_refused"))
    }

    @Test
    fun `an unauthenticated call is 401`() {
        given()
            .port(port)
            .`when`()
            .get("/api/nyc/v1/revenue/Manhattan")
            .then()
            .statusCode(401)
    }

    @Test
    fun `an endpoint key is refused on the product's own API`() {
        // R-EP5: the confinement follows the first segment, not a literal prefix — /api/v1 is
        // the product's namespace by construction, and an endpoint key has no business there.
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(403)
            .body("error.code", equalTo("endpoint.key_kind_refused"))
    }

    @Test
    fun `an unknown path in the product's namespace answers the product 404, not the endpoint one`() {
        // The reservation works in both directions: no endpoint can ever live under /api/v1,
        // so an unknown path there is the product's own no-handler 404 — and a caller probing
        // the boundary cannot tell the endpoint surface answers at all.
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/v1/definitely-not-a-route")
            .then()
            .statusCode(404)
            .body("error.code", equalTo("pipeline.execution.not_found"))
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
            .post("/api/nyc/v1/revenue/Manhattan")
            .then()
            .statusCode(405)
            .header("Allow", equalTo("GET"))
            .body("error.code", equalTo("endpoint.method_not_allowed"))
    }

    @Test
    fun `an unpublished path is 404`() {
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/nothing/v1/here")
            .then()
            .statusCode(404)
            .body("error.code", equalTo("endpoint.not_found"))
    }

    // -------------------------------------------------------------------------------------
    // Publish-time refusals (§19.2)
    // -------------------------------------------------------------------------------------

    @Test
    fun `the create response echoes the full served URL, and one api prefix is normalised away`() {
        // R-EP5: the stored form is always the part after /api; the response names the URL a
        // client actually calls. Published WITH the prefix to prove the normalisation.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"path": "/api/echo/v1/normalised", "pipeline": "test/trade_summary"}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(201)
            .body("data.path", equalTo("/echo/v1/normalised"))
            .body("data.url", equalTo("/api/echo/v1/normalised"))
    }

    @Test
    fun `a reserved category is refused at publish, naming the segment`() {
        // R-EP5: v<number> is the product's own namespace. An endpoint under it would be
        // indistinguishable from a product route, so the refusal comes at publish, not at
        // some future collision.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"path": "/v1/revenue/today", "pipeline": "test/trade_summary"}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.path_reserved"))
            .body("error.details.segment", equalTo("v1"))
    }

    @Test
    fun `a pipeline with a DML node cannot be published`() {
        // The rule that makes GET safe, met where an author meets it.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"path": "/writes/v1/things", "pipeline": "test/writes_things"}""")
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
            .asSession(ADMIN_SESSION)
            // A LITERAL third segment, not another variable: §4.2's checks run cheapest-first, so
            // `/nyc/revenue/{anything}` would be refused as an undeclared path variable (400)
            // before it ever reached the conflict check. This one is a legal path that happens to
            // collide, which is exactly what `endpoint.path_conflict` is for.
            .body("""{"path": "/nyc/v1/revenue/manhattan", "pipeline": "test/revenue_by_borough"}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(409)
            .body("error.code", equalTo("endpoint.path_conflict"))
            .body("error.details.conflicting_path", equalTo("/nyc/v1/revenue/{borough}"))
    }

    @Test
    fun `a path variable the pipeline does not declare is refused`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"path": "/nyc/v1/by/{ghost}", "pipeline": "test/revenue_by_borough"}""")
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
                .header(API_KEY_HEADER, endpointKey)
                .`when`()
                .get("/api/slow/v1/thing")
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
                    .header(API_KEY_HEADER, endpointKey)
                    .`when`()
                    .get("/api/nyc/v1/revenue/Manhattan/executions/$accepted/result")
                    .then()
                    .extract()
                    .statusCode()
        }

        // The key that started the run reads its result (#215 A12 — api_caller's own runs).
        given()
            .port(port)
            .header(API_KEY_HEADER, endpointKey)
            .`when`()
            .get("/api/nyc/v1/revenue/Manhattan/executions/$accepted/result")
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
            .asSession(ADMIN_SESSION)
            // /slow too: an unbound published path serves no one (#215 B3).
            .body("""{"name": "nyc-serving", "kind": "endpoint", "bindings": ["/nyc", "/slow"]}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(201)
            .body("data.kind", equalTo("endpoint"))
            // #215: an endpoint key carries the api_caller role; its reach is the binding.
            .body("data.role", equalTo("api_caller"))
            .extract()
            .jsonPath()
            .getString("data.key")

    private fun publishEndpoints() {
        publish("/nyc/v1/revenue/{borough}", "test/revenue_by_borough")
        publish("/trade/v1/summary", "test/trade_summary")
        // timeout_seconds = 1 against a 3-second query: the 202 path, deterministically.
        publishWithTimeout("/slow/v1/thing", "test/slow_sleep", 1)
        // #171 — under /nyc, so the existing endpoint key's binding covers it with no new key.
        publish("/nyc/v1/tempdb-ddl", "test/tempdb_ddl_endpoint")
    }

    private fun publish(
        path: String,
        pipeline: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
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
            .asSession(ADMIN_SESSION)
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
            .asSession(ADMIN_SESSION)
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
        // #171 — the tempdb-DDL shape: stage from Postgres, CREATE INDEX on the staged table (a
        // real DDL statement, against `tempdb`, exactly the snow-days pipeline's shape), then
        // read the staged, indexed table back to the caller. Both the index and the report
        // templates target the H2 tempdb, not Postgres.
        template("test/tempdb_ddl_stage.sql", "SELECT borough, revenue FROM boroughs ORDER BY revenue DESC")
        template("test/tempdb_ddl_index.sql", "CREATE INDEX idx_stg_ddl_boroughs_revenue ON stg_ddl_boroughs(revenue)", dialect = "H2")
        template("test/tempdb_ddl_report.sql", "SELECT borough, revenue FROM stg_ddl_boroughs ORDER BY revenue DESC", dialect = "H2")
    }

    private fun template(
        id: String,
        body: String,
        dialect: String = "POSTGRES",
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"id": "$id", "dialect": "$dialect", "display_name": "$id",
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
            .asSession(ADMIN_SESSION)
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
            .asSession(ADMIN_SESSION)
            .queryParam("name", id)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("data.body_hash")

    // `min_revenue` is declared for the value-validation cases (#194) and the SQL never reads it —
    // §12 has no unused-parameter rule, and a value is judged whether or not a template uses it.
    private fun createPipelines() {
        revenuePipelineId =
            pipeline(
                """
                {"schema_version": 1, "name": "test/revenue_by_borough", "display_name": "Revenue by borough",
                 "description": "074 E2E.",
                 "parameters": {"borough": {"type": "STRING", "required": true},
                                "start_date": {"type": "DATE", "required": false, "default": "2024-01-01"},
                                "min_revenue": {"type": "BIGDECIMAL", "precision": 12, "scale": 2, "required": false}},
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
        // #171 — a DDL node against tempdb is publishable: the snow-days shape (stage, CREATE
        // INDEX on the staged table, read it back), the case the read-only rule used to over-refuse.
        pipeline(
            """
            {"schema_version": 1, "name": "test/tempdb_ddl_endpoint", "display_name": "Tempdb DDL endpoint",
             "description": "171 E2E — a DDL node against tempdb is side-effect-free.", "parameters": {},
             "nodes": [
               {"id": "stage", "description": "Stage boroughs into tempdb", "type": "DQL",
                "source": "ep-source", "template": {"id": "test/tempdb_ddl_stage.sql", "version": 1},
                "output": {"target": "tempdb", "table": "stg_ddl_boroughs"}, "depends_on": []},
               {"id": "index_stg", "description": "CREATE INDEX on the staged table", "type": "DDL",
                "source": "tempdb", "template": {"id": "test/tempdb_ddl_index.sql", "version": 1},
                "depends_on": ["stage"]},
               {"id": "report", "description": "Read the staged, indexed table back", "type": "DQL",
                "source": "tempdb", "template": {"id": "test/tempdb_ddl_report.sql", "version": 1},
                "output": {"target": "caller"}, "depends_on": ["stage", "index_stg"]}
             ]}
            """.trimIndent(),
        )
    }

    /**
     * Creates the pipeline and RELEASES it — an endpoint serves the latest RELEASED version and
     * nothing else (§5.1), and since D55 a create lands a DRAFT, so a fixture that only created
     * would be publishing over something this surface is right to refuse. The release is the human
     * step the product requires, done here the way a person does it.
     */
    private fun pipeline(body: String): String {
        val created =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
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
            .asSession(ADMIN_SESSION)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))
        return id
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
                        VALUES ('$OTHER_WORKSPACE', '$ADMIN_USER', 'workspace_admin')
                        """.trimIndent(),
                    )
                    // Keys v2 (A13): each key acts as its own `service` identity.
                    listOf(ADMIN_KEY to "ep-admin-key", FOREIGN_KEY to "ep-foreign-key").forEach { (key, name) ->
                        statement.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                                "(gen_random_uuid(), '${key.id.lowercase()}@keys.invalid', '$name', 'key', " +
                                "'${key.id}', TRUE, FALSE, 'service')",
                        )
                    }
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, (SELECT id FROM users WHERE provider_subject = ?), ?, ?, ?, ?::uuid, 'mcp', ?)",
                    ).use { ps ->
                        listOf(ADMIN_KEY to DEFAULT_WORKSPACE, FOREIGN_KEY to OTHER_WORKSPACE).forEach { (key, workspace) ->
                            ps.setString(1, key.id)
                            ps.setString(2, key.id)
                            ps.setObject(3, UUID.fromString(ADMIN_USER))
                            ps.setString(4, key.name)
                            ps.setString(5, key.hash)
                            ps.setString(6, workspace)
                            ps.setString(7, "workspace_admin")
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

        private val ADMIN_KEY = E2eAuth.generateKey("ep-admin-key")
        private val FOREIGN_KEY = E2eAuth.generateKey("ep-foreign-key")

        /** The per-run JWT secret — registered as `datapipelines.jwt.secret`; the admin's REST work is a session's (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER, "ep-admin@datapipelines.test")

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
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            // Local accounts rather than an OIDC stub: §7 needs SOME auth method configured, and
            // every request here presents an API key.
            registry.add("datapipelines.auth.local.enabled") { "true" }
        }
    }
}
