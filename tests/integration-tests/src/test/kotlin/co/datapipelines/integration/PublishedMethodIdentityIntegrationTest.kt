package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #1037 — a route is a method+path pair, and a mutation grant is an exact route entity, end to
 * end over HTTP (rest-api §19.3/§19.5, auth.md §7.7 "Method grants").
 *
 * Everything is driven through the product's own surfaces — publish over `POST /api/v1/endpoints`,
 * grants over `POST /api/v1/endpoints/bindings`, calls on `/api/mix/v1/…` with an `endpoint` key —
 * because the point is that the matcher, the grant tables, the visibility computation and the §4.4
 * gate order agree with each other. The unit suites pin each rule in isolation; this class pins
 * their wiring.
 *
 * The cases are chosen for what they would catch, not for coverage:
 *
 * - **four methods at one path** — the identity split itself; a registry keyed on path alone
 *   (the pre-V65 world) answers 409 on the second publish, and a create that ignores `method`
 *   collapses the rows into one.
 * - **the root-bound key's 405** — the ancestor walk must not authorise a mutation: with the GET
 *   walk feeding every method, POST on a root-bound key would serve, not refuse.
 * - **the 405 to 503 transition** — the ONLY wire-visible difference between "no grant" and
 *   "grant held, build gate" (this build never serves a mutation; the flag is on so the 503 is
 *   the build gate, proving the grant was seen).
 * - **the redacted cross-workspace 409** — the refusal must name NOTHING of the other workspace;
 *   a leaked `conflicting_path` is exactly what the Named shape would print.
 * - **the recreated route's 405** — the grant follows the ENTITY id, not the pattern; a grant
 *   re-derived from the prefix would survive the delete and serve the recreated route.
 * - **pin 503 before mutations 503** — §4.4's order; a gate that ran before the pin check would
 *   answer `mutations_disabled` on a route with nothing servable pinned.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // On: the authorized-but-unservable branch of the build gate. The 405s do not depend on it;
    // the 503s must be the "route and grant are valid" one, not the "operator withheld" one.
    properties = ["datapipelines.endpoints.mutations-enabled=true"],
)
@Suppress("LargeClass") // one route family, one seed; the cases share it rather than re-seeding
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
class PublishedMethodIdentityIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var rootKey: String
    private lateinit var rootKeyId: String

    @BeforeAll
    fun seed() {
        E2eClean.beforeSeeding()
        seedRows()
        seedSourceData()
        registerDatasource()
        createTemplates()
        createPipelines()
        publishRoutes()
        mintRootKey()
    }

    // -----------------------------------------------------------------------------------------
    // Identity — four methods at one path, and the legacy default
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `four methods at one path are four routes - list carries each, and a create with no method is GET`() {
        // The GET was published WITHOUT a method field — the pre-method era's only input shape;
        // it must land on the GET identity, not be refused.
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/v1/endpoints?path=/mix/v1/notes&method=GET")
            .then()
            .statusCode(200)
            .body("data.method", equalTo("GET"))
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            // No ?method=: the pre-method addressing shape, answered for the GET it always meant.
            .get("/api/v1/endpoints?path=/mix/v1/notes")
            .then()
            .statusCode(200)
            .body("data.method", equalTo("GET"))
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/v1/endpoints?path=/mix/v1/notes&method=POST")
            .then()
            .statusCode(200)
            .body("data.method", equalTo("POST"))
            .body("data.path", equalTo("/mix/v1/notes"))
        // The whole family is in the listing, one row per method.
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/v1/endpoints")
            .then()
            .statusCode(200)
            .body("data.method", hasItem("GET"))
            .body("data.method", hasItem("POST"))
            .body("data.method", hasItem("PUT"))
            .body("data.method", hasItem("DELETE"))
    }

    @Test
    @Order(2)
    fun `a duplicate method at a taken path is 409 naming the pattern - and another method is not a duplicate`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"path": "/mix/v1/notes", "method": "POST", "pipeline": "$WRITE_PIPELINE", "pipeline_version": 1}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(409)
            .body("error.code", equalTo("endpoint.path_conflict"))
            .body("error.details.path_pattern", equalTo("/mix/v1/notes"))
            .body("error.details.conflicting_path", equalTo("/mix/v1/notes"))
        // The GET identity is taken too — by the legacy-shaped publish, not just explicit ones.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"path": "/mix/v1/notes", "pipeline": "$READ_PIPELINE", "pipeline_version": 1}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(409)
            .body("error.code", equalTo("endpoint.path_conflict"))
    }

    @Test
    @Order(3)
    fun `a verb this surface cannot publish is 400 method_invalid`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"path": "/mix/v1/patched", "method": "PATCH", "pipeline": "$READ_PIPELINE", "pipeline_version": 1}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.method_invalid"))
            .body("error.details.method", equalTo("PATCH"))
    }

    // -----------------------------------------------------------------------------------------
    // Grants — the GET walk never authorises a mutation; an exact grant opens exactly one method
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(4)
    fun `a root-bound key serves GET and is refused 405 Allow GET on every mutation`() {
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .get("/api/mix/v1/notes")
            .then()
            .statusCode(200)
            .body("row_count", equalTo(SEEDED_ROWS))
        listOf("POST", "PUT", "DELETE").forEach { verb ->
            given()
                .port(port)
                .header(API_KEY_HEADER, rootKey)
                .`when`()
                .request(verb, "/api/mix/v1/notes")
                .then()
                .statusCode(405)
                .header("Allow", equalTo("GET"))
                .body("error.code", equalTo("endpoint.method_not_allowed"))
                .body("error.details.method", equalTo(verb))
                .body("error.details.allow", hasItem("GET"))
        }
        // A verb no surface can publish follows the same rule and never reaches the gates.
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .request("PATCH", "/api/mix/v1/notes")
            .then()
            .statusCode(405)
            .header("Allow", equalTo("GET"))
    }

    @Test
    @Order(5)
    fun `an exact grant opens exactly its method at exactly its route`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"api_key_id": "$rootKeyId", "path_prefix": "/mix/v1/notes", "method": "POST"}""")
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(201)
            .body("data.path_prefix", equalTo("/mix/v1/notes"))

        // The grant was SEEN: the 503 is the build gate ("route and grant are valid"), not the
        // operator gate — and nothing was written either way.
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(503)
            .body("error.code", equalTo("endpoint.mutations_disabled"))
            .body("error.details.method", equalTo("POST"))
            .body("error.message", containsString("nothing was written"))

        // The sibling methods gained nothing — Allow grows by exactly POST.
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .put("/api/mix/v1/notes")
            .then()
            .statusCode(405)
            .header("Allow", equalTo("GET, POST"))
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .delete("/api/mix/v1/notes")
            .then()
            .statusCode(405)
            .header("Allow", equalTo("GET, POST"))

        // The grant is exact to its ROUTE ENTITY: the sibling path — same prefix, same method
        // published, the key's GET walk covering it — is still refused. A prefix grant would
        // open it.
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .post("/api/mix/v1/sibling")
            .then()
            .statusCode(405)
            .header("Allow", equalTo("GET"))

        // And the GET walk the key always had is unchanged.
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .get("/api/mix/v1/notes")
            .then()
            .statusCode(200)
    }

    @Test
    @Order(6)
    fun `unbinding the exact grant closes the method again`() {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/endpoints/bindings?pathPrefix=/mix/v1/notes&apiKeyId=$rootKeyId&method=POST")
            .then()
            .statusCode(204)
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(405)
            .header("Allow", equalTo("GET"))
    }

    // -----------------------------------------------------------------------------------------
    // Opacity — the other workspace's key, and the other workspace's publish
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(7)
    fun `an MCP key is refused by kind - and the other workspace's endpoint key gets GET 403, mutation 404`() {
        // #215 B2 first, for BOTH verbs: the MCP key never reaches a binding decision, and the
        // answer is the same for the admin's own workspace and another one.
        listOf("get", "post").forEach { verb ->
            given()
                .port(port)
                .header(API_KEY_HEADER, FOREIGN_KEY.plaintext)
                .`when`()
                .request(verb, "/api/mix/v1/notes")
                .then()
                .statusCode(403)
                .body("error.code", equalTo("endpoint.key_kind_refused"))
                .body("error.details.reason", equalTo("mcp_key_off_surface"))
        }

        // An ENDPOINT-kind key of the OTHER workspace — root-bound there, so its grants are
        // real, just not here. The GET branch keeps its pre-#1037 answer for a published-but-
        // ungranted path: 403 key_not_bound, "no binding decides here".
        val foreignEndpointKey =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(WORKSPACE_HEADER, OTHER_WORKSPACE)
                .asSession(ADMIN_SESSION)
                .body("""{"name": "mi-foreign-endpoint", "kind": "endpoint", "bindings": ["/"]}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.key")
        given()
            .port(port)
            .header(API_KEY_HEADER, foreignEndpointKey)
            .`when`()
            .get("/api/mix/v1/notes")
            .then()
            .statusCode(403)
            .body("error.code", equalTo("endpoint.key_not_bound"))
        // The mutation branch never names the route: nothing is visible to this key, so the
        // path is — as far as it can learn — not published at all.
        given()
            .port(port)
            .header(API_KEY_HEADER, foreignEndpointKey)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(404)
            .body("error.code", equalTo("endpoint.not_found"))
    }

    @Test
    @Order(8)
    fun `a cross-workspace publish conflicts for every method and reveals nothing`() {
        // The other workspace needs its OWN template, datasource and pipeline to publish at all —
        // all three are workspace-scoped (V4 re-key), so the conflict below is the registry's,
        // not a lookup refusal's. The datasource points at the same scratch database.
        fun asOther() =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(WORKSPACE_HEADER, OTHER_WORKSPACE)
                .asSession(ADMIN_SESSION)
        asOther()
            .body(
                """
                {"name": "ep-source-mi-other", "display_name": "Method identity source (other)",
                 "dialect": "POSTGRES", "jdbc_url": "${source.jdbcUrl}",
                 "username": "${source.username}", "password": "${source.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
        asOther()
            .body(
                """
                {"id": "test/mi_other_write.sql", "dialect": "POSTGRES", "display_name": "mi_other_write",
                 "description": "1037 E2E — the other workspace's write template.", "imports": [],
                 "body": "INSERT INTO boroughs (borough, revenue, as_of) VALUES ('X', 1, DATE '2024-01-01')"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
        asOther()
            .header("If-Match", draftHashAsOther("test/mi_other_write.sql"))
            .body("""{"name": "test/mi_other_write.sql"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
        val created =
            asOther()
                .body(
                    """
                    {"schema_version": 1, "name": "$OTHER_WRITE_PIPELINE", "display_name": "MI other write",
                     "description": "1037 E2E — the other workspace's pipeline.", "parameters": {},
                     "nodes": [{"id": "write", "description": "A write", "type": "DML",
                                "source": "ep-source-mi-other", "template": {"id": "test/mi_other_write.sql", "version": 1},
                                "depends_on": []}]}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        asOther()
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/${created.jsonPath().getString("data.id")}/release")
            .then()
            .statusCode(200)
        // The GET variant needs a READ pipeline of their own — the §4.2 read-only rule is a GET
        // rule and runs before the registry conflict, so a write pin would refuse for the wrong
        // reason here.
        asOther()
            .body(
                """
                {"id": "test/mi_other_read.sql", "dialect": "POSTGRES", "display_name": "mi_other_read",
                 "description": "1037 E2E — the other workspace's read template.", "imports": [],
                 "body": "SELECT borough, revenue FROM boroughs ORDER BY revenue DESC"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
        asOther()
            .header("If-Match", draftHashAsOther("test/mi_other_read.sql"))
            .body("""{"name": "test/mi_other_read.sql"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
        val readCreated =
            asOther()
                .body(
                    """
                    {"schema_version": 1, "name": "$OTHER_READ_PIPELINE", "display_name": "MI other read",
                     "description": "1037 E2E — the other workspace's read pipeline.", "parameters": {},
                     "nodes": [{"id": "read", "description": "A read", "type": "DQL",
                                "source": "ep-source-mi-other", "template": {"id": "test/mi_other_read.sql", "version": 1},
                                "depends_on": []}]}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        asOther()
            .header("If-Match", readCreated.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/${readCreated.jsonPath().getString("data.id")}/release")
            .then()
            .statusCode(200)

        // Same pattern, ANY method: the deployment-wide-per-method rule — a URL has one meaning
        // per method across the whole deployment, so the other workspace cannot take POST here
        // either. The refusal is REDACTED: the caller's own pattern travels, nothing else does.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(WORKSPACE_HEADER, OTHER_WORKSPACE)
            .asSession(ADMIN_SESSION)
            .body("""{"path": "/mix/v1/notes", "method": "POST", "pipeline": "$OTHER_WRITE_PIPELINE", "pipeline_version": 1}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(409)
            .body("error.code", equalTo("endpoint.path_conflict"))
            .body("error.details.path_pattern", equalTo("/mix/v1/notes"))
            // The Named shape's tell — absent here, because there is nothing this caller may learn.
            .body("error.details.conflicting_path", equalTo(null))
            .body("error.message", not(containsString("GET")))
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(WORKSPACE_HEADER, OTHER_WORKSPACE)
            .asSession(ADMIN_SESSION)
            .body("""{"path": "/mix/v1/notes", "pipeline": "$OTHER_READ_PIPELINE", "pipeline_version": 1}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(409)
            .body("error.code", equalTo("endpoint.path_conflict"))
            .body("error.details.conflicting_path", equalTo(null))
    }

    // -----------------------------------------------------------------------------------------
    // Lifecycle — the grant follows the entity; the pin refusal precedes the build gate
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(9)
    fun `a recreated route does not inherit the old grant, and the grant dies with its route`() {
        // Grant the draft-only scratch route, exactly as the product would.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"api_key_id": "$rootKeyId", "path_prefix": "/mix/v1/scratch", "method": "POST"}""")
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(201)
        jdbcCount("SELECT COUNT(*) FROM endpoint_key_bindings WHERE path_prefix = '/mix/v1/scratch' AND method = 'POST'")
            .shouldBe(1)

        // Unpublish the draft-only route — the one verb a draft-only entity admits.
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/endpoints?path=/mix/v1/scratch&method=POST")
            .then()
            .statusCode(204)

        // The observable database result, from a second client: the cascade killed the grant.
        jdbcCount(
            "SELECT COUNT(*) FROM endpoint_key_bindings WHERE path_prefix = '/mix/v1/scratch' AND method = 'POST'",
        ).shouldBe(0)

        // Recreate: a NEW entity id, and a grant derived from the pattern would serve — the
        // entity-scoped grant does not.
        val recreated =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"path": "/mix/v1/scratch", "method": "POST", "pipeline": "$WRITE_PIPELINE", "pipeline_version": 1}""")
                .`when`()
                .post("/api/v1/endpoints")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.id")
        recreated shouldNotBe scratchEntityId
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .post("/api/mix/v1/scratch")
            .then()
            // No GET route at this path and no grant on the new entity: nothing is visible to
            // this key, and the refusal is the opaque 404. A grant derived from the PATTERN
            // would instead answer the build gate's 503 — that difference is the case.
            .statusCode(404)
            .body("error.code", equalTo("endpoint.not_found"))
    }

    @Test
    @Order(10)
    fun `the pin refusal precedes the mutations gate - and restore puts the route back`() {
        val order =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"path": "/mix/v1/order", "method": "POST", "pipeline": "$WRITE_PIPELINE", "pipeline_version": 1}""")
                .`when`()
                .post("/api/v1/endpoints")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.id")
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"api_key_id": "$rootKeyId", "path_prefix": "/mix/v1/order", "method": "POST"}""")
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(201)

        // Release the draft, then discard the released version: the entity stays, its current
        // pointer goes — the §5.6 pin refusal, which §4.4 answers BEFORE the mutations gate.
        val draftHash =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/endpoints/$order/versions/1")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", draftHash)
            .`when`()
            .post("/api/v1/endpoints/$order/release")
            .then()
            .statusCode(200)
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .post("/api/v1/endpoints/$order/versions/1/discard")
            .then()
            .statusCode(200)

        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .post("/api/mix/v1/order")
            .then()
            // The pin 503, NOT the mutations 503 — the grant is held, so a gate that ran first
            // would answer mutations_disabled here.
            .statusCode(503)
            .body("error.code", equalTo("endpoint.pipeline_not_released"))

        // Restore: the pin returns, and the answer returns to the build gate — release, discard
        // and restore exercised on a POST route with the GET sibling untouched.
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .post("/api/v1/endpoints/$order/versions/1/restore")
            .then()
            .statusCode(200)
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .post("/api/mix/v1/order")
            .then()
            .statusCode(503)
            .body("error.code", equalTo("endpoint.mutations_disabled"))
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/v1/endpoints?path=/mix/v1/notes&method=GET")
            .then()
            .statusCode(200)
            .body("data.method", equalTo("GET"))
    }

    // -----------------------------------------------------------------------------------------
    // Contention and retirement
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(11)
    fun `concurrent creates of one route admit exactly one`() {
        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { pool ->
            val futures =
                (1..2).map {
                    pool.submit<Int> {
                        barrier.await(BARRIER_TIMEOUT_S, TimeUnit.SECONDS)
                        given()
                            .port(port)
                            .contentType(ContentType.JSON)
                            .asSession(ADMIN_SESSION)
                            .body("""{"path": "/mix/v1/race", "method": "POST", "pipeline": "$WRITE_PIPELINE", "pipeline_version": 1}""")
                            .`when`()
                            .post("/api/v1/endpoints")
                            .then()
                            .extract()
                            .statusCode()
                    }
                }
            val codes = futures.map { it.get(FUTURE_TIMEOUT_S, TimeUnit.SECONDS) }.sorted()
            codes shouldBe listOf(201, 409)
        }
    }

    @Test
    @Order(12)
    fun `a revoked key authenticates as no one - GET and mutation alike`() {
        val minted =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"name": "mi-revoked", "kind": "endpoint", "bindings": ["/"]}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
        val key = minted.getString("data.key")
        val keyId = minted.getString("data.id")
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"api_key_id": "$keyId", "path_prefix": "/mix/v1/notes", "method": "POST"}""")
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(201)
        // Sanity while live: the exact grant answers the build gate, not 405.
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(503)
            .body("error.code", equalTo("endpoint.mutations_disabled"))

        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/auth/api-keys/$keyId")
            .then()
            .statusCode(204)

        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .`when`()
            .get("/api/mix/v1/notes")
            .then()
            .statusCode(401)
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(401)
    }

    // -----------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------

    private fun publishRoutes() {
        // The legacy input shape: no `method` — the pre-#1037 publish, mapping to GET.
        publish(path = "/mix/v1/notes", method = null, pipeline = READ_PIPELINE)
        publish(path = "/mix/v1/notes", method = "POST", pipeline = WRITE_PIPELINE)
        publish(path = "/mix/v1/notes", method = "PUT", pipeline = WRITE_PIPELINE)
        publish(path = "/mix/v1/notes", method = "DELETE", pipeline = WRITE_PIPELINE)
        // The exactness case's sibling: BOTH methods live here, so an ungranted POST at it is a
        // 405 with Allow GET — not the 404 a path with no GET route would answer.
        publish(path = "/mix/v1/sibling", method = null, pipeline = READ_PIPELINE)
        publish(path = "/mix/v1/sibling", method = "POST", pipeline = WRITE_PIPELINE)
        // The cascade case's route, left draft-only so unpublish can take it.
        publish(path = "/mix/v1/scratch", method = "POST", pipeline = WRITE_PIPELINE)
            .also { id -> scratchEntityId = id }
    }

    private fun publish(
        path: String,
        method: String?,
        pipeline: String,
    ): String {
        // Absent method is the pre-#1037 input shape — legacy input mapping to GET.
        val methodField = method?.let { """"method": "$it", """ } ?: ""
        return given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"path": "$path", $methodField"pipeline": "$pipeline", "pipeline_version": 1, "timeout_seconds": 60}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(201)
            .body("data.path", equalTo(path))
            .body("data.method", equalTo(method ?: "GET"))
            .extract()
            .jsonPath()
            .getString("data.id")
    }

    /** The key every grant case holds: root-bound for the GET walk, exact grants added per case. */
    private fun mintRootKey() {
        val minted =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"name": "mi-root", "kind": "endpoint", "bindings": ["/"]}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .body("data.kind", equalTo("endpoint"))
                .extract()
                .jsonPath()
        rootKey = minted.getString("data.key")
        rootKeyId = minted.getString("data.id")
    }

    private fun registerDatasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "ep-source-mi", "display_name": "Method identity source", "dialect": "POSTGRES",
                 "jdbc_url": "${source.jdbcUrl}", "username": "${source.username}", "password": "${source.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun createTemplates() {
        template("test/mi_read.sql", "SELECT borough, revenue FROM boroughs ORDER BY revenue DESC")
        template("test/mi_write.sql", "INSERT INTO boroughs (borough, revenue, as_of) VALUES ('MI', 1, DATE '2024-01-01')")
    }

    private fun template(
        id: String,
        body: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"id": "$id", "dialect": "POSTGRES", "display_name": "$id",
                 "description": "1037 method-identity E2E fixture.", "imports": [], "body": "$body"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
            .body("data.status", equalTo("DRAFT"))
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

    private fun createPipelines() {
        pipeline(
            """
            {"schema_version": 1, "name": "$READ_PIPELINE", "display_name": "MI read",
             "description": "1037 E2E — the GET identity's pipeline.", "parameters": {},
             "nodes": [{"id": "read", "description": "All rows", "type": "DQL",
                        "source": "ep-source-mi", "template": {"id": "test/mi_read.sql", "version": 1},
                        "depends_on": []}]}
            """.trimIndent(),
        )
        // A DML pipeline: publishable ONLY because its route is not GET — the read-only rule is
        // a GET rule since #1037, and pinning this pipeline to the POST identity is the proof.
        pipeline(
            """
            {"schema_version": 1, "name": "$WRITE_PIPELINE", "display_name": "MI write",
             "description": "1037 E2E — the mutation identities' pipeline.", "parameters": {},
             "nodes": [{"id": "write", "description": "A write", "type": "DML",
                        "source": "ep-source-mi", "template": {"id": "test/mi_write.sql", "version": 1},
                        "depends_on": []}]}
            """.trimIndent(),
        )
    }

    /** Creates the pipeline and RELEASES it — an API pins an exact version, and only a released one is eligible. */
    private fun pipeline(body: String) {
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
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/${created.jsonPath().getString("data.id")}/release")
            .then()
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))
    }

    /** The rows the GET identity serves. */
    private fun seedSourceData() {
        DriverManager.getConnection(source.jdbcUrl, source.username, source.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE boroughs (borough TEXT NOT NULL, revenue INT NOT NULL, as_of DATE NOT NULL)")
                statement.execute(
                    """
                    INSERT INTO boroughs (borough, revenue, as_of) VALUES
                      ('Manhattan', 300, DATE '2024-03-01'),
                      ('Queens', 90, DATE '2024-01-01')
                    """.trimIndent(),
                )
            }
        }
    }

    /** Users, the second workspace and its key — the cross-tenant fixtures. */
    private fun seedRows() {
        DriverManager
            .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER', 'mi-admin@datapipelines.test', 'MI Admin', 'test', 'mi-admin', TRUE, TRUE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspaces (id, name, display_name, created_by)
                        VALUES ('$OTHER_WORKSPACE_ID', '$OTHER_WORKSPACE', 'MI other workspace', '$ADMIN_USER')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_members (workspace_id, user_id, role)
                        VALUES ('$OTHER_WORKSPACE_ID', '$ADMIN_USER', 'workspace_admin')
                        """.trimIndent(),
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "(gen_random_uuid(), '${FOREIGN_KEY.id.lowercase()}@keys.invalid', 'mi-foreign-key', 'key', " +
                            "'${FOREIGN_KEY.id}', TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, (SELECT id FROM users WHERE provider_subject = ?), ?, ?, ?, ?::uuid, 'mcp', ?)",
                    ).use { ps ->
                        ps.setString(1, FOREIGN_KEY.id)
                        ps.setString(2, FOREIGN_KEY.id)
                        ps.setObject(3, UUID.fromString(ADMIN_USER))
                        ps.setString(4, "mi-foreign-key")
                        ps.setString(5, FOREIGN_KEY.hash)
                        ps.setString(6, OTHER_WORKSPACE_ID)
                        ps.setString(7, "workspace_admin")
                        ps.executeUpdate()
                    }
            }
    }

    /** A second client's read of the shipped database — the cascade evidence. */
    private fun jdbcCount(sql: String): Int =
        DriverManager
            .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }

    /**
     * The other workspace's draft hash — templates are workspace-scoped, so this read carries
     * the workspace header too.
     */
    private fun draftHashAsOther(id: String): String =
        given()
            .port(port)
            .header(WORKSPACE_HEADER, OTHER_WORKSPACE)
            .asSession(ADMIN_SESSION)
            .queryParam("name", id)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("data.body_hash")

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val WORKSPACE_HEADER = "DP-Workspace"
        private const val OTHER_WORKSPACE_ID = "defa0000-0000-0000-0000-0000000000ee"

        /** The header's value is the NAME — DP-Workspace resolves by name, not id. */
        private const val OTHER_WORKSPACE = "mi-other"
        private const val READ_PIPELINE = "test/mi_read"
        private const val WRITE_PIPELINE = "test/mi_write"
        private const val OTHER_WRITE_PIPELINE = "test/mi_other_write"
        private const val OTHER_READ_PIPELINE = "test/mi_other_read"
        private const val SEEDED_ROWS = 2

        /** Named, because a bare sleep in a barrier await is how flakes are born. */
        private const val BARRIER_TIMEOUT_S = 10L
        private const val FUTURE_TIMEOUT_S = 20L

        private val ADMIN_USER: String = UUID.randomUUID().toString()
        private val FOREIGN_KEY = E2eAuth.generateKey("mi-foreign-key")

        /** The per-run JWT secret — the admin's REST work is a session's (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER, "mi-admin@datapipelines.test")

        private val source = SharedE2e.scratchDatabase("method_identity_source")

        /** The first /mix/v1/scratch entity's id — the recreated route must get a NEW one. */
        private lateinit var scratchEntityId: String

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            registry.add("datapipelines.auth.local.enabled") { "true" }
        }

        private fun randomSecret(): String =
            Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
    }
}
