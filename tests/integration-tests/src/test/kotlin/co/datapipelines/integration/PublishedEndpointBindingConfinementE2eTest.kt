package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
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
 * #191 — an endpoint key binding stays INSIDE its workspace, end to end over HTTP.
 *
 * The rule has two halves and this file proves both, because they fail differently:
 *
 * - **Bind time** — `POST /api/v1/auth/api-keys` with `bindings` and `POST
 *   /api/v1/endpoints/bindings` refuse a prefix unless it is at or above a path the CALLER'S
 *   workspace publishes. The refusal names only the caller's own tree: whether another
 *   workspace publishes there is exactly what it must not reveal.
 * - **Serve time** — a binding row of another workspace (here: written directly, the way a row
 *   from an older release could have been left behind) is INERT: serving workspace B's
 *   endpoint with A's key answers the byte-for-byte body an unbound key gets, and B's own key
 *   still serves. This is the composition — SQL predicate and in-memory backstop together —
 *   that neither unit suite can prove.
 *
 * Non-vacuity is carried by the assertions themselves: one served 200 (B's own key) and one
 * refused body (A's key) per arm.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishedEndpointBindingConfinementE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var ownKey: String

    @BeforeAll
    fun seed() {
        E2eClean.beforeSeeding()
        seedRows()
        registerDatasource()
        createTemplateAndPipeline()
        publish()
        ownKey = mintOwnKey()
    }

    // ------------------------------------------------------------------ bind time (#191)

    @Test
    fun `minting a key bound at another workspace's published prefix is refused`() {
        // Workspace B published /conf/v1/p; workspace A's admin names /conf. The prefix is
        // exactly what makes the attack interesting: /conf IS published — by someone else.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, FOREIGN_KEY.plaintext)
            .body("""{"name": "cross-ws", "kind": "endpoint", "bindings": ["/conf"]}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.path_invalid"))
            // The message names only the caller's own tree — no other workspace's shape leaks.
            .body("error.message", equalTo(REFUSAL_MESSAGE))
    }

    @Test
    fun `binding an existing key at another workspace's published prefix is refused`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, FOREIGN_KEY.plaintext)
            .body("""{"api_key_name": "ep-foreign-key", "path_prefix": "/conf"}""")
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("endpoint.path_invalid"))
            .body("error.message", equalTo(REFUSAL_MESSAGE))
    }

    @Test
    fun `a node of the workspace's OWN published tree is still accepted`() {
        // The rule narrows; it does not strangle. B published /conf/v1/p, so B's admin binds a
        // deeper node of the same tree by name — the legitimate act this fix keeps working.
        // (The seed's 201 mint bound at /conf is the same property through issuance.)
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body("""{"api_key_name": "conf-admin-key", "path_prefix": "/conf/v1"}""")
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(201)
    }

    // ----------------------------------------------------------------- serve time (#191)

    @Test
    fun `a foreign binding row is inert - the answer is the unbound key's, error payload for payload`() {
        // Before the row exists: the unbound refusal, captured.
        val unbound =
            given()
                .port(port)
                .header(API_KEY_HEADER, FOREIGN_KEY.plaintext)
                .`when`()
                .get("/api/conf/v1/p")
                .then()
                .statusCode(403)
                .extract()
                .jsonPath()
                .getMap<String, Any>("error")

        // The row an older release could have left behind: A's key bound at /conf, written
        // directly. The bind-time rule above cannot see history; serve time must not care.
        insertForeignBinding()

        val withForeignRow =
            given()
                .port(port)
                .header(API_KEY_HEADER, FOREIGN_KEY.plaintext)
                .`when`()
                .get("/api/conf/v1/p")
                .then()
                .statusCode(403)
                // A USER key of another workspace on a path nobody of ITS workspace bound is
                // the unbound rule's other-workspace arm.
                .body("error.code", equalTo("endpoint.key_not_bound"))
                .extract()
                .jsonPath()
                .getMap<String, Any>("error")

        // Same refusal, field for field of the error payload (the envelope's correlation_id
        // is per-request by design): the foreign row neither authorised nor shadowed anything
        // — the walk behaves exactly as if the node carried no binding.
        withForeignRow shouldBe unbound
    }

    @Test
    fun `the workspace's own bound key still serves its own endpoint`() {
        val executionId =
            given()
                .port(port)
                .header(API_KEY_HEADER, ownKey)
                .`when`()
                .get("/api/conf/v1/p")
                .then()
                .statusCode(200)
                .body("rows.size()", equalTo(1))
                .body("rows[0][0]", equalTo(1))
                .extract()
                .jsonPath()
                .getString("execution_id")

        // #192 — the serve audit's outcome rows, as read from the DATABASE: the pre-answer
        // `started` row and the terminal `completed` row for the same execution.
        serveAuditOutcomes(executionId) shouldBe listOf("started", "completed")
    }

    /** The `outcome` values of the execution's `endpoint.served` rows, oldest first. */
    private fun serveAuditOutcomes(executionId: String): List<String> =
        DriverManager
            .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
            .use { connection -> serveAuditOutcomes(connection, executionId) }

    private fun serveAuditOutcomes(
        connection: java.sql.Connection,
        executionId: String,
    ): List<String> =
        connection
            .prepareStatement(
                "SELECT details_json ->> 'outcome' FROM audit_log " +
                    "WHERE event = 'endpoint.served' AND details_json ->> 'execution_id' = ? ORDER BY id",
            ).use { ps ->
                ps.setString(1, executionId)
                ps.executeQuery().use { rs ->
                    val seen = mutableListOf<String>()
                    while (rs.next()) seen += rs.getString(1)
                    seen
                }
            }

    // ------------------------------------------------------------------------ fixture

    /**
     * Creates the pipeline and RELEASES it — an endpoint serves the latest RELEASED version
     * and nothing else (§5.1); the release is the human step done the way a person does it.
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

        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/${created.jsonPath().getString("data.id")}/release")
            .then()
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))
    }

    private fun insertForeignBinding() {
        DriverManager
            .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
            .use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO endpoint_key_bindings (path_prefix, api_key_id, workspace_id, created_by)" +
                            " VALUES ('/conf', ?, ?::uuid, ?::uuid) ON CONFLICT DO NOTHING",
                    ).use { ps ->
                        ps.setString(1, FOREIGN_KEY.id)
                        ps.setString(2, OTHER_WORKSPACE)
                        ps.setString(3, ADMIN_USER)
                        ps.executeUpdate()
                    }
            }
    }

    private fun mintOwnKey(): String =
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body("""{"name": "conf-serving", "kind": "endpoint", "bindings": ["/conf"]}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.key")

    private fun publish() {
        publish("/conf/v1/p", pipeline = "conf/x_summary")
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

    private fun registerDatasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "conf-source", "display_name": "Confinement source", "dialect": "POSTGRES",
                 "jdbc_url": "${source.jdbcUrl}", "username": "${source.username}", "password": "${source.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun createTemplateAndPipeline() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "conf/x_summary.sql", "dialect": "POSTGRES", "display_name": "conf/x_summary.sql",
                 "description": "191 E2E fixture.", "imports": [], "body": "SELECT 1 AS marker"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)

        // Templates lock first (versioning §6): release each, then the pipelines that pin them.
        listOf("conf/x_summary.sql").forEach { id ->
            val hash =
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
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .header("If-Match", hash)
                .body("""{"name": "$id"}""")
                .`when`()
                .post("/api/v1/templates/release")
                .then()
                .statusCode(200)
        }

        pipeline(
            """
            {"schema_version": 1, "name": "conf/x_summary", "display_name": "X summary",
             "description": "191 E2E.", "parameters": {},
             "nodes": [{"id": "summary", "description": "One row", "type": "DQL",
                        "source": "conf-source", "template": {"id": "conf/x_summary.sql", "version": 1},
                        "depends_on": []}]}
            """.trimIndent(),
        )
    }

    /** Two workspaces: B (the default) publishes; A is the workspace whose key reaches across. */
    private fun seedRows() {
        DriverManager
            .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER', 'conf-admin@datapipelines.test', 'Conf Admin', 'test', 'conf-admin', TRUE, TRUE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspaces (id, name, display_name, created_by)
                        VALUES ('$OTHER_WORKSPACE', 'conf-other', 'Confinement other', '$ADMIN_USER')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_members (workspace_id, user_id, role)
                        VALUES ('$OTHER_WORKSPACE', '$ADMIN_USER', 'workspace_admin')
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
        private const val OTHER_WORKSPACE = "defa0000-0000-0000-0000-0000000000ed"
        private val ADMIN_USER: String = UUID.randomUUID().toString()

        /**
         * The one refusal message, asserted verbatim in both bind-time arms: it names only the
         * caller's own tree, so a probe cannot learn whether another workspace publishes at the
         * prefix (auth.md §11A.1's non-disclosure rule, applied to bindings).
         */
        private const val REFUSAL_MESSAGE =
            "No published path of your workspace lies at or under '/conf'. " +
                "Bind the key at a node of your own published tree, or at the root."

        private val ADMIN_KEY = E2eAuth.generateKey("conf-admin-key", arrayOf("read", "execute", "author"))
        private val FOREIGN_KEY = E2eAuth.generateKey("ep-foreign-key", arrayOf("read", "execute", "author"))

        private val postgres get() = SharedE2e.postgres
        private val source = SharedE2e.scratchDatabase("conf_source")

        private fun randomSecret(): String =
            Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            registry.add("datapipelines.auth.local.enabled") { "true" }
        }
    }
}
