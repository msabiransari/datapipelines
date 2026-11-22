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
import org.hamcrest.Matchers.nullValue
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
 * - **the 405 to 400 transition** — the wire-visible difference between "no grant" and "grant
 *   held": since #1039 a granted mutation reaches admission, and a keyless call is refused there
 *   with the `Idempotency-Key` entry (`400`) before anything is reserved or run — proving the
 *   grant was seen without writing anything.
 * - **the redacted cross-workspace 409** — the refusal must name NOTHING of the other workspace;
 *   a leaked `conflicting_path` is exactly what the Named shape would print.
 * - **the recreated route's 405** — the grant follows the ENTITY id, not the pattern; a grant
 *   re-derived from the prefix would survive the delete and serve the recreated route.
 * - **the pin 503 for a fresh keyed call** — a route with nothing servable pinned answers
 *   `pipeline_not_released` and reserves nothing; after restore the pin's own body rule answers.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // On: a fresh keyed mutation reaches the pin (#1039). The 405s and the keyless 400s do not
    // depend on it; the pin's 503 must not be the "operator withheld" one.
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

    /** The ids of the second-generation pipelines, for the discard-refusal (incoming) reads. */
    private lateinit var readBId: String
    private lateinit var writeBId: String

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

        // The grant was SEEN: the call reached admission, which refuses a keyless mutation (#1039)
        // before anything is reserved or run — a 400, not the 405 an ungranted key gets.
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.request.invalid"))
            .body("error.details.errors[0].code", equalTo("endpoint.request.idempotency_key_invalid"))
            .body("error.details.errors[0].reason", equalTo("missing"))

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
    @Suppress("LongMethod") // every method's redacted refusal is one ordered cross-tenant walk
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
                                "operation": "update", "output": {"target": "discard"}, "depends_on": []}]}
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
    // Lifecycle — the grant follows the entity; the pin refusal of a fresh keyed call
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
            // would instead reach admission's keyless 400 — that difference is the case.
            .statusCode(404)
            .body("error.code", equalTo("endpoint.not_found"))
    }

    @Test
    @Order(10)
    @Suppress("LongMethod") // discard, refusal and restore are one ordered route lifecycle
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
            .header(IDEMPOTENCY_HEADER, "mi-order-1")
            .`when`()
            .post("/api/mix/v1/order")
            .then()
            // A fresh, keyed, granted call with nothing servable pinned: the pin 503, and nothing
            // reserved (the same key is answered the same way again below, not replayed).
            .statusCode(503)
            .body("error.code", equalTo("endpoint.pipeline_not_released"))
        jdbcCount("SELECT count(*) FROM published_mutation_reservations WHERE endpoint_id = '$order'") shouldBe 0

        // Restore: the pin returns, and the pin's OWN rule answers — this route takes no body, so a
        // body is refused 400 before anything is reserved or run. Release, discard and restore are
        // exercised on a POST route with the GET sibling untouched.
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
            .header(IDEMPOTENCY_HEADER, "mi-order-1")
            .contentType(ContentType.JSON)
            .body("{}")
            .`when`()
            .post("/api/mix/v1/order")
            .then()
            .statusCode(400)
            .body("error.details.errors[0].code", equalTo("endpoint.request.body_invalid"))
            .body("error.details.errors[0].reason", equalTo("not_accepted"))
        jdbcCount("SELECT count(*) FROM published_mutation_reservations WHERE endpoint_id = '$order'") shouldBe 0
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
        // Sanity while live: the exact grant reaches admission (the keyless 400), not 405.
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.request.invalid"))
            .body("error.details.errors[0].code", equalTo("endpoint.request.idempotency_key_invalid"))
            .body("error.details.errors[0].reason", equalTo("missing"))

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
    // Roles on the method-aware management routes (auth.md §7.6 — cells unchanged by #1037)
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(13)
    @Suppress("LongMethod") // every role x route cell is one matrix row; splitting it orphans a cell
    fun `the method-aware management routes keep their role cells - viewer reads, author publishes, only admins grant`() {
        // endpoint.read — every member role reads a mutation route by path AND method.
        given()
            .port(port)
            .asSession(VIEWER_SESSION)
            .`when`()
            .get("/api/v1/endpoints?path=/mix/v1/notes&method=POST")
            .then()
            .statusCode(200)
            .body("data.method", equalTo("POST"))

        // endpoint.publish — viewer and promoter refused, author admitted, for a NON-GET route.
        listOf(VIEWER_SESSION, PROMOTER_SESSION).forEach { session ->
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(session)
                .body("""{"path": "/mix/v1/authored", "method": "POST", "pipeline": "$WRITE_PIPELINE", "pipeline_version": 1}""")
                .`when`()
                .post("/api/v1/endpoints")
                .then()
                .statusCode(403)
        }
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(AUTHOR_SESSION)
            .body("""{"path": "/mix/v1/authored", "method": "POST", "pipeline": "$WRITE_PIPELINE", "pipeline_version": 1}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(201)
            .body("data.method", equalTo("POST"))

        // api_key.bind — the author (and viewer, promoter) may not grant a method; a workspace
        // admin may, and may take it back.
        listOf(VIEWER_SESSION, AUTHOR_SESSION, PROMOTER_SESSION).forEach { session ->
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(session)
                .body("""{"api_key_id": "$rootKeyId", "path_prefix": "/mix/v1/authored", "method": "POST"}""")
                .`when`()
                .post("/api/v1/endpoints/bindings")
                .then()
                .statusCode(403)
        }
        jdbcCount("SELECT count(*) FROM endpoint_key_bindings WHERE path_prefix = '/mix/v1/authored'") shouldBe 0
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(WS_ADMIN_SESSION)
            .body("""{"api_key_id": "$rootKeyId", "path_prefix": "/mix/v1/authored", "method": "POST"}""")
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(201)
        jdbcCount(
            "SELECT count(*) FROM endpoint_key_bindings b JOIN published_endpoints e ON e.id = b.endpoint_id" +
                " WHERE b.path_prefix = '/mix/v1/authored' AND b.method = 'POST' AND e.method = 'POST'",
        ) shouldBe 1
        listOf(VIEWER_SESSION, AUTHOR_SESSION, PROMOTER_SESSION).forEach { session ->
            given()
                .port(port)
                .asSession(session)
                .`when`()
                .delete("/api/v1/endpoints/bindings?pathPrefix=/mix/v1/authored&apiKeyId=$rootKeyId&method=POST")
                .then()
                .statusCode(403)
        }
        given()
            .port(port)
            .asSession(WS_ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/endpoints/bindings?pathPrefix=/mix/v1/authored&apiKeyId=$rootKeyId&method=POST")
            .then()
            .statusCode(204)

        // endpoint.unpublish — viewer and promoter refused; the author deletes the draft-only POST route.
        listOf(VIEWER_SESSION, PROMOTER_SESSION).forEach { session ->
            given()
                .port(port)
                .asSession(session)
                .`when`()
                .delete("/api/v1/endpoints?path=/mix/v1/authored&method=POST")
                .then()
                .statusCode(403)
        }
        given()
            .port(port)
            .asSession(AUTHOR_SESSION)
            .`when`()
            .delete("/api/v1/endpoints?path=/mix/v1/authored&method=POST")
            .then()
            .statusCode(204)
        jdbcCount("SELECT count(*) FROM published_endpoints WHERE path_pattern = '/mix/v1/authored'") shouldBe 0
    }

    // -----------------------------------------------------------------------------------------
    // The machine-only gate — 401 before CSRF on the published subtree, CSRF kept elsewhere
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(14)
    fun `a keyless or cookie-only mutation is 401 with no Allow - with or without a CSRF token`() {
        // No credential at all.
        given()
            .port(port)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(401)
            .header("Allow", nullValue())
        // A valid session WITH the double-submit token: still not a credential this surface accepts.
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(401)
            .header("Allow", nullValue())
            .body("error.code", equalTo("auth.session.required"))
        // A valid session WITHOUT the token: the key gate answers, not the CSRF filter.
        given()
            .port(port)
            .cookie(E2eSession.COOKIE, ADMIN_SESSION)
            .`when`()
            .delete("/api/mix/v1/notes")
            .then()
            .statusCode(401)
            .header("Allow", nullValue())
            .body("error.code", equalTo("auth.session.required"))
        // The exemption is scoped to the published subtree: the framework's own cookie-backed
        // write without the token is still the CSRF refusal.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .cookie(E2eSession.COOKIE, ADMIN_SESSION)
            .body("""{"path": "/mix/v1/csrf", "method": "POST", "pipeline": "$WRITE_PIPELINE", "pipeline_version": 1}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(403)
            .body("error.code", equalTo("auth.csrf.invalid"))
        jdbcCount("SELECT count(*) FROM published_endpoints WHERE path_pattern = '/mix/v1/csrf'") shouldBe 0
    }

    @Test
    @Order(15)
    fun `a pattern that could match a polling suffix is refused at publish, naming the suffix`() {
        mapOf(
            "/mix/v1/{dataset}/{record}" to "executions/{id}",
            "/mix/v1/feed/executions/{id}" to "executions/{id}",
            "/mix/v1/{a}/{b}/result" to "executions/{id}/result",
        ).forEach { (path, suffix) ->
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"path": "$path", "method": "POST", "pipeline": "$WRITE_PIPELINE", "pipeline_version": 1}""")
                .`when`()
                .post("/api/v1/endpoints")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("endpoint.path_reserved"))
                .body("error.details.segment", equalTo(suffix))
        }
        jdbcCount("SELECT count(*) FROM published_endpoints WHERE path_pattern LIKE '/mix/v1/%/%/%'") shouldBe 0
    }

    // -----------------------------------------------------------------------------------------
    // Upgrade — a binding written with the pre-V65 column list is the GET grant it always was
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(16)
    @Suppress("LongMethod") // the upgrade-shaped row, its GET and the grant are one ordered scenario
    fun `a pre-V65 root binding serves its GET and gains a mutation only by an exact grant`() {
        val minted =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"name": "mi-upgraded", "kind": "endpoint", "bindings": []}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
        val key = minted.getString("data.key")
        val keyId = minted.getString("data.id")
        // The pre-#1037 writer's INSERT, verbatim in shape: no method, no endpoint_id. V65's
        // DEFAULT is the backfill, so this row is what every upgraded binding looks like.
        DriverManager
            .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
            .use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO endpoint_key_bindings (path_prefix, api_key_id, workspace_id, created_by)" +
                            " VALUES ('/', ?, (SELECT id FROM workspaces WHERE name = 'default'), ?::uuid)",
                    ).use { ps ->
                        ps.setString(1, keyId)
                        ps.setString(2, ADMIN_USER)
                        ps.executeUpdate() shouldBe 1
                    }
            }
        jdbcCount(
            "SELECT count(*) FROM endpoint_key_bindings WHERE api_key_id = '$keyId' AND path_prefix = '/'" +
                " AND method = 'GET' AND endpoint_id IS NULL",
        ) shouldBe 1

        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .`when`()
            .get("/api/mix/v1/notes")
            .then()
            .statusCode(200)
            .body("row_count", equalTo(SEEDED_ROWS))
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(405)
            .header("Allow", equalTo("GET"))

        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"api_key_id": "$keyId", "path_prefix": "/mix/v1/notes", "method": "POST"}""")
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(201)
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.request.invalid"))
            .body("error.details.errors[0].code", equalTo("endpoint.request.idempotency_key_invalid"))
            .body("error.details.errors[0].reason", equalTo("missing"))
    }

    @Test
    @Order(17)
    @Suppress("LongMethod") // both keys' answers at one path are compared in one scenario
    fun `a write-only key and a root-bound key are judged independently at one path`() {
        val minted =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"name": "mi-writer", "kind": "endpoint", "bindings": []}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
        val writer = minted.getString("data.key")
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"api_key_id": "${minted.getString("data.id")}", "path_prefix": "/mix/v1/notes", "method": "PUT"}""")
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(201)

        // The writer: its PUT is seen, its Allow is PUT alone, and the GET it was never granted is
        // the GET handler's unchanged refusal — a mutation grant never implies a read.
        given()
            .port(port)
            .header(API_KEY_HEADER, writer)
            .`when`()
            .put("/api/mix/v1/notes")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.request.invalid"))
            .body("error.details.errors[0].code", equalTo("endpoint.request.idempotency_key_invalid"))
            .body("error.details.errors[0].reason", equalTo("missing"))
        given()
            .port(port)
            .header(API_KEY_HEADER, writer)
            .`when`()
            .post("/api/mix/v1/notes")
            .then()
            .statusCode(405)
            .header("Allow", equalTo("PUT"))
        given()
            .port(port)
            .header(API_KEY_HEADER, writer)
            .`when`()
            .get("/api/mix/v1/notes")
            .then()
            .statusCode(403)
            .body("error.code", equalTo("endpoint.key_not_bound"))

        // The root-bound key: unchanged by the writer's grant.
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .put("/api/mix/v1/notes")
            .then()
            .statusCode(405)
            .header("Allow", equalTo("GET"))
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .`when`()
            .get("/api/mix/v1/notes")
            .then()
            .statusCode(200)
    }

    // -----------------------------------------------------------------------------------------
    // Same-path lifecycle — the release graph, the dependency tree and the version verbs carry the method
    // -----------------------------------------------------------------------------------------

    /** The route the reviewer's shared-path release cases run on: GET and POST both live, both still at v1. */
    @Test
    @Order(18)
    fun `GET releases while a POST shares its path and POST releases while a GET shares its`() {
        // Both siblings are v1 DRAFT with different pins; before the method rode the graph key, the walk read one
        // path and found two version-1 rows (a missing node), so the release answered 409 snapshot_changed.
        val get = releaseDraft("/mix/v1/notes", "GET")
        val post = releaseDraft("/mix/v1/notes", "POST")
        get.pipeline shouldBe READ_PIPELINE
        post.pipeline shouldBe WRITE_PIPELINE
        // Each release flipped only its own entity: the sibling's version row is the one that was released for it.
        versionStatus(get.entityId, 1) shouldBe "RELEASED"
        versionStatus(post.entityId, 1) shouldBe "RELEASED"
        get.entityId shouldNotBe post.entityId
    }

    @Test
    @Order(19)
    fun `a same-path sibling's release never touches the other method's versions`() {
        // PUT and DELETE at /mix/v1/notes are still drafts, though their GET and POST siblings were just released.
        for (method in listOf("PUT", "DELETE")) {
            versionStatus(endpointId("/mix/v1/notes", method), 1) shouldBe "DRAFT"
        }
        // And the released siblings are current at v1 — the pointer moved on the entity that released.
        for (method in listOf("GET", "POST")) {
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .get("/api/v1/endpoints?path=/mix/v1/notes&method=$method")
                .then()
                .statusCode(200)
                .body("data.current_version", equalTo(1))
        }
    }

    @Test
    @Order(20)
    @Suppress("LongMethod") // one scenario: each sibling's tree, release, pins and refusals read side by side
    fun `GET and POST at one path resolve their own dependency trees, release and pin independently`() {
        publish(path = SHARED_PATH, method = null, pipeline = READ_PIPELINE)
        publish(path = SHARED_PATH, method = "POST", pipeline = WRITE_PIPELINE)
        val getId = endpointId(SHARED_PATH, "GET")
        val postId = endpointId(SHARED_PATH, "POST")

        // The tree of each sibling is its OWN: its route key names it, and its one pipeline edge is its own pin.
        treeOf(SHARED_PATH, 1).pipelineNames() shouldBe listOf(READ_PIPELINE)
        treeOf("POST $SHARED_PATH", 1).pipelineNames() shouldBe listOf(WRITE_PIPELINE)
        treeOf(SHARED_PATH, 1).rootName() shouldBe SHARED_PATH
        treeOf("POST $SHARED_PATH", 1).rootName() shouldBe "POST $SHARED_PATH"
        treeOf("POST $SHARED_PATH", 1).rootEntityId() shouldBe postId
        treeOf(SHARED_PATH, 1).rootEntityId() shouldBe getId
        // A method with no route at the path has no tree: the sibling's is not substituted for it.
        treeRequest("PUT $SHARED_PATH", 1).statusCode(404).body("error.code", equalTo("endpoint.not_found"))

        // Release each; both succeed at version 1, and neither answers for the other.
        releaseDraft(SHARED_PATH, "GET").entityId shouldBe getId
        releaseDraft(SHARED_PATH, "POST").entityId shouldBe postId
        treeOf(SHARED_PATH, 1).rootLifecycle() shouldBe "RELEASED"
        treeOf("POST $SHARED_PATH", 1).rootLifecycle() shouldBe "RELEASED"

        // Overlapping version numbers on distinct pins: each saves a v2 draft on its own entity and releases it.
        saveAndRelease(postId, WRITE_PIPELINE_B)
        versionIds(getId) shouldBe listOf(1)
        saveAndRelease(getId, READ_PIPELINE_B)
        versionIds(postId) shouldBe listOf(2, 1)
        versionIds(getId) shouldBe listOf(2, 1)
        treeOf(SHARED_PATH, 2).pipelineNames() shouldBe listOf(READ_PIPELINE_B)
        treeOf("POST $SHARED_PATH", 2).pipelineNames() shouldBe listOf(WRITE_PIPELINE_B)
        treeOf(SHARED_PATH, 1).pipelineNames() shouldBe listOf(READ_PIPELINE)
        treeOf("POST $SHARED_PATH", 1).pipelineNames() shouldBe listOf(WRITE_PIPELINE)

        // The pipeline side names the route that pins it: a POST route is `POST /path`, never a bare path that a
        // GET at the same path would also be called.
        referencingApiPaths(writeBId, 1) shouldBe listOf("POST $SHARED_PATH")
        referencingApiPaths(readBId, 1) shouldBe listOf(SHARED_PATH)
    }

    @Test
    @Order(21)
    @Suppress("LongMethod") // switch, discard, restore and purge-guard of two siblings, asserted against each other
    fun `save, switch, discard and restore on one same-path sibling never operate on the other`() {
        val getId = endpointId(SHARED_PATH, "GET")
        val postId = endpointId(SHARED_PATH, "POST")
        currentVersion(getId) shouldBe 2
        currentVersion(postId) shouldBe 2

        // switch: POST back to v1 — GET keeps v2.
        switchTo(postId, 1)
        currentVersion(postId) shouldBe 1
        currentVersion(getId) shouldBe 2

        // discard POST v2 (not current) — GET's v2 is a different row and stays RELEASED.
        postVersion(postId, 2, "discard").statusCode(200).body("data.version", equalTo(2))
        versionStatus(postId, 2) shouldBe "DISCARDED"
        versionStatus(getId, 2) shouldBe "RELEASED"
        treeOf("POST $SHARED_PATH", 2).rootLifecycle() shouldBe "DISCARDED"
        treeOf(SHARED_PATH, 2).rootLifecycle() shouldBe "RELEASED"

        // restore POST v2: the restore recheck walks the POST pin; GET's v2 is untouched, still pinning its own pipeline.
        postVersion(postId, 2, "restore").statusCode(200).body("data.version", equalTo(2))
        versionStatus(postId, 2) shouldBe "RELEASED"
        // A restore moves the pointer only upward: POST is current at 2 again, and GET never left 2.
        currentVersion(postId) shouldBe 2
        currentVersion(getId) shouldBe 2
        versionBody(getId, 2) shouldBe READ_PIPELINE_B
        versionBody(postId, 2) shouldBe WRITE_PIPELINE_B

        // a draft saved on GET is GET's alone: POST has no draft, so releasing POST's draft is refused, not GET's.
        saveDraft(getId, READ_PIPELINE, expectedHash = versionHash(getId, 2)).statusCode(200)
        postRelease(postId, versionHash(postId, 2)).statusCode(409).body("error.code", equalTo("endpoint.version.not_draft"))
        versionStatus(getId, 3) shouldBe "DRAFT"
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .get("/api/v1/endpoints/$postId/versions/3")
            .then()
            .statusCode(404)
    }

    @Test
    @Order(22)
    @Suppress("LongMethod") // one case per role and key cell, each asserted against both siblings
    fun `the same-path lifecycle and tree surfaces keep their role and key cells`() {
        val postId = endpointId(SHARED_PATH, "POST")
        val hash = versionHash(postId, 1)
        // endpoint.read: every member role reads a mutation route's tree; an endpoint KEY is refused by kind.
        for (session in listOf(VIEWER_SESSION, AUTHOR_SESSION, WS_ADMIN_SESSION)) {
            given()
                .port(port)
                .asSession(session)
                .queryParam("name", "POST $SHARED_PATH")
                .queryParam("version", 1)
                .get("/api/v1/dependencies/api_endpoint")
                .then()
                .statusCode(200)
        }
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .queryParam("name", "POST $SHARED_PATH")
            .queryParam("version", 1)
            .get("/api/v1/dependencies/api_endpoint")
            .then()
            .statusCode(403)
        given()
            .port(port)
            .queryParam("name", "POST $SHARED_PATH")
            .queryParam("version", 1)
            .get("/api/v1/dependencies/api_endpoint")
            .then()
            .statusCode(401)
        // endpoint.version.manage / endpoint.release: viewer and promoter are refused on the sibling's routes, and the
        // key is refused by kind; nothing moved.
        for (session in listOf(VIEWER_SESSION, PROMOTER_SESSION)) {
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(session)
                .body("""{"version": 1}""")
                .post("/api/v1/endpoints/$postId/current")
                .then()
                .statusCode(403)
            given()
                .port(port)
                .asSession(session)
                .post("/api/v1/endpoints/$postId/versions/2/discard")
                .then()
                .statusCode(403)
            given()
                .port(port)
                .asSession(session)
                .post("/api/v1/endpoints/$postId/versions/2/restore")
                .then()
                .statusCode(403)
            given()
                .port(port)
                .asSession(session)
                .header("If-Match", hash)
                .post("/api/v1/endpoints/$postId/release")
                .then()
                .statusCode(403)
        }
        given()
            .port(port)
            .header(API_KEY_HEADER, rootKey)
            .header("If-Match", hash)
            .post("/api/v1/endpoints/$postId/release")
            .then()
            .statusCode(403)
        currentVersion(postId) shouldBe 2
        versionStatus(postId, 2) shouldBe "RELEASED"
        // The author may switch (endpoint.version.manage ✓), proving the refusals above were the role and not the route.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(AUTHOR_SESSION)
            .body("""{"version": 1}""")
            .post("/api/v1/endpoints/$postId/current")
            .then()
            .statusCode(200)
        currentVersion(postId) shouldBe 1
        currentVersion(endpointId(SHARED_PATH, "GET")) shouldBe 2
    }

    @Test
    @Order(23)
    fun `a maximum-length published path retains its dependency tree for every method`() {
        val path = "/mix/v1/" + "a".repeat(64) + "/" + "b".repeat(64) + "/" + "c".repeat(62)
        path.length shouldBe 200
        for (method in listOf("GET", "POST", "PUT", "DELETE")) {
            publish(path = path, method = method, pipeline = READ_PIPELINE)
            val name = if (method == "GET") path else "$method $path"
            treeRequest(name, 1).statusCode(200)
        }
    }

    // -- same-path lifecycle helpers ----------------------------------------------------------

    private class Released(
        val entityId: String,
        val pipeline: String,
    )

    private fun endpointId(
        path: String,
        method: String,
    ): String =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .queryParam("path", path)
            .queryParam("method", method)
            .get("/api/v1/endpoints")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("data.id")

    private fun versionStatus(
        entityId: String,
        version: Int,
    ): String =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .get("/api/v1/endpoints/$entityId/versions/$version")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("data.status")

    private fun versionHash(
        entityId: String,
        version: Int,
    ): String =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .get("/api/v1/endpoints/$entityId/versions/$version")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("data.body_hash")

    private fun versionBody(
        entityId: String,
        version: Int,
    ): String =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .get("/api/v1/endpoints/$entityId/versions/$version")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("data.pipeline")

    private fun versionIds(entityId: String): List<Int> =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .get("/api/v1/endpoints/$entityId/versions")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data.version")

    private fun currentVersion(entityId: String): Int =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .get("/api/v1/endpoints/$entityId/versions")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getInt("data.find { it.is_current }.version")

    /** Releases the entity's v1 draft at its own body hash through the shared planner; 200 is the assertion. */
    private fun releaseDraft(
        path: String,
        method: String,
    ): Released {
        val entityId = endpointId(path, method)
        val response = postRelease(entityId, versionHash(entityId, 1))
        response.statusCode(200).body("data.status", equalTo("RELEASED"))
        return Released(entityId, response.extract().jsonPath().getString("data.pipeline"))
    }

    private fun postRelease(
        entityId: String,
        hash: String,
    ) = given()
        .port(port)
        .asSession(ADMIN_SESSION)
        .header("If-Match", hash)
        .post("/api/v1/endpoints/$entityId/release")
        .then()

    private fun saveDraft(
        entityId: String,
        pipeline: String,
        expectedHash: String,
    ) = given()
        .port(port)
        .contentType(ContentType.JSON)
        .asSession(ADMIN_SESSION)
        .header("If-Match", expectedHash)
        .body("""{"pipeline": "$pipeline", "pipeline_version": 1, "timeout_seconds": 60}""")
        .put("/api/v1/endpoints/$entityId/draft")
        .then()

    /** Saves a draft pinning [pipeline] on the entity's current version, then releases it. */
    private fun saveAndRelease(
        entityId: String,
        pipeline: String,
    ) {
        val current =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .get("/api/v1/endpoints/$entityId/versions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        val currentHash = current.getString("data.find { it.is_current }.body_hash")
        val saved = saveDraft(entityId, pipeline, currentHash).statusCode(200).extract().jsonPath()
        postRelease(entityId, saved.getString("data.body_hash")).statusCode(200).body("data.pipeline", equalTo(pipeline))
    }

    private fun switchTo(
        entityId: String,
        version: Int,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"version": $version}""")
            .post("/api/v1/endpoints/$entityId/current")
            .then()
            .statusCode(200)
    }

    private fun postVersion(
        entityId: String,
        version: Int,
        verb: String,
    ) = given()
        .port(port)
        .asSession(ADMIN_SESSION)
        .post("/api/v1/endpoints/$entityId/versions/$version/$verb")
        .then()

    private fun treeRequest(
        routeKey: String,
        version: Int,
    ) = given()
        .port(port)
        .asSession(ADMIN_SESSION)
        .queryParam("name", routeKey)
        .queryParam("version", version)
        .get("/api/v1/dependencies/api_endpoint")
        .then()

    private fun treeOf(
        routeKey: String,
        version: Int,
    ): io.restassured.path.json.JsonPath = treeRequest(routeKey, version).statusCode(200).extract().jsonPath()

    private fun io.restassured.path.json.JsonPath.pipelineNames(): List<String> =
        getList<String>("data.nodes.findAll { it.family == 'pipeline' }.name")

    private fun io.restassured.path.json.JsonPath.rootName(): String = getString("data.nodes.find { it.node_id == 'n0' }.name")

    private fun io.restassured.path.json.JsonPath.rootEntityId(): String = getString("data.nodes.find { it.node_id == 'n0' }.entity_id")

    private fun io.restassured.path.json.JsonPath.rootLifecycle(): String = getString("data.nodes.find { it.node_id == 'n0' }.lifecycle")

    /** The API routes still pinning [pipelineId]@[version], as the discard refusal names them. */
    private fun referencingApiPaths(
        pipelineId: String,
        version: Int,
    ): List<String> =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .post("/api/v1/pipelines/$pipelineId/versions/$version/discard")
            .then()
            .statusCode(409)
            .body("error.code", equalTo("pipeline.version.pinned"))
            .extract()
            .jsonPath()
            .getList("error.details.referencing_api_versions.path")

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
                        "operation": "update", "output": {"target": "discard"}, "depends_on": []}]}
            """.trimIndent(),
        )
        // The same-path lifecycle's second generation: distinct pipelines (so distinct pins) over the same templates.
        readBId =
            pipeline(
                """
                {"schema_version": 1, "name": "$READ_PIPELINE_B", "display_name": "MI read B",
                 "description": "1037 E2E — the GET identity's v2 pin.", "parameters": {},
                 "nodes": [{"id": "read", "description": "All rows", "type": "DQL",
                            "source": "ep-source-mi", "template": {"id": "test/mi_read.sql", "version": 1},
                            "depends_on": []}]}
                """.trimIndent(),
            )
        writeBId =
            pipeline(
                """
                {"schema_version": 1, "name": "$WRITE_PIPELINE_B", "display_name": "MI write B",
                 "description": "1037 E2E — the POST identity's v2 pin.", "parameters": {},
                 "nodes": [{"id": "write", "description": "A write", "type": "DML",
                            "source": "ep-source-mi", "template": {"id": "test/mi_write.sql", "version": 1},
                            "operation": "update", "output": {"target": "discard"}, "depends_on": []}]}
                """.trimIndent(),
            )
    }

    /** Creates the pipeline and RELEASES it — an API pins an exact version, and only a released one is eligible. */
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
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/${created.jsonPath().getString("data.id")}/release")
            .then()
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))
        return created.jsonPath().getString("data.id")
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
    @Suppress("LongMethod") // the seed IS the fixture: identities, memberships and keys in one spelled-out block
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
                    // The member roles of auth.md §7.6, in the default workspace the sessions act in.
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                          ('$VIEWER_USER', 'mi-viewer@datapipelines.test', 'MI Viewer', 'test', 'mi-viewer', TRUE, FALSE),
                          ('$AUTHOR_USER', 'mi-author@datapipelines.test', 'MI Author', 'test', 'mi-author', TRUE, FALSE),
                          ('$PROMOTER_USER', 'mi-promoter@datapipelines.test', 'MI Promoter', 'test', 'mi-promoter', TRUE, FALSE),
                          ('$WS_ADMIN_USER', 'mi-wsadmin@datapipelines.test', 'MI Workspace Admin', 'test', 'mi-wsadmin', TRUE, FALSE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_members (workspace_id, user_id, role)
                        SELECT w.id, m.user_id::uuid, m.role FROM workspaces w,
                          (VALUES ('$VIEWER_USER', 'viewer'), ('$AUTHOR_USER', 'author'),
                                  ('$PROMOTER_USER', 'promoter'), ('$WS_ADMIN_USER', 'workspace_admin')) AS m(user_id, role)
                         WHERE w.name = 'default'
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
        private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        private const val WORKSPACE_HEADER = "DP-Workspace"
        private const val OTHER_WORKSPACE_ID = "defa0000-0000-0000-0000-0000000000ee"

        /** The header's value is the NAME — DP-Workspace resolves by name, not id. */
        private const val OTHER_WORKSPACE = "mi-other"
        private const val READ_PIPELINE = "test/mi_read"
        private const val WRITE_PIPELINE = "test/mi_write"
        private const val READ_PIPELINE_B = "test/mi_read_b"
        private const val WRITE_PIPELINE_B = "test/mi_write_b"

        /** The path the same-path lifecycle cases publish a GET and a POST at (both start at v1). */
        private const val SHARED_PATH = "/mix/v1/shared"
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
        private val VIEWER_USER: String = UUID.randomUUID().toString()
        private val AUTHOR_USER: String = UUID.randomUUID().toString()
        private val PROMOTER_USER: String = UUID.randomUUID().toString()
        private val WS_ADMIN_USER: String = UUID.randomUUID().toString()
        private val VIEWER_SESSION get() = E2eSession.jwt(JWT_SECRET, VIEWER_USER, "mi-viewer@datapipelines.test")
        private val AUTHOR_SESSION get() = E2eSession.jwt(JWT_SECRET, AUTHOR_USER, "mi-author@datapipelines.test")
        private val PROMOTER_SESSION get() = E2eSession.jwt(JWT_SECRET, PROMOTER_USER, "mi-promoter@datapipelines.test")
        private val WS_ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, WS_ADMIN_USER, "mi-wsadmin@datapipelines.test")

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
