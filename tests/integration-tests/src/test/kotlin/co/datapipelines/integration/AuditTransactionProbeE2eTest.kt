package co.datapipelines.integration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
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
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

/**
 * #266 A.1 — **which audit call sites write inside the caller's transaction?** The runtime half of
 * the census (the static half is the evidence's `audit_call_sites.py`, which cannot see a caller in
 * another class that opened the transaction).
 *
 * Over the REAL application: `AuditLogger` logs every row's path at DEBUG
 * (`event=audit.write audit_event=… path=transactional|batched|direct`), decided by
 * `TransactionSynchronizationManager.isActualTransactionActive()` on the calling thread — the same
 * test that routes the row. This probe raises that logger to DEBUG in-process, drives
 * representative flows over the wire — a key minted and revoked, a member added and removed, an
 * MCP call, a rejected credential — and prints the census the evidence records.
 *
 * What it pins: a transactional caller's row stays on the caller's connection (it commits and rolls
 * back with the business write — `api_key.created` inside the issuance transaction), and the
 * request-path rows the writer exists for (`mcp.tool.called`, `auth.api_key.rejected`) are batched —
 * with `datapipelines.persistence.audit.enabled=true` set in the property source below, since the
 * writer ships off (#266b). The default path is `AuditDirectByDefaultE2eTest`'s.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension::class)
class AuditTransactionProbeE2eTest {
    @LocalServerPort
    private var port: Int = 0

    /** By name: this module compiles against `app` alone (module-structure §4.2), never against `auth`. */
    private val auditLog = LoggerFactory.getLogger("co.datapipelines.auth.AuditLogger") as Logger
    private var previousLevel: Level? = null

    @BeforeAll
    fun seedAndListen() {
        seedRows()
        previousLevel = auditLog.level
        auditLog.level = Level.DEBUG
    }

    @AfterAll
    fun quiet() {
        auditLog.level = previousLevel
        oidc.close()
    }

    @Test
    fun `the census - each representative flow's audit rows, by the path they took`(output: CapturedOutput) {
        // A key minted and revoked through the REST surface (ApiKeyService: @Transactional).
        val minted =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"name": "probe-266-$RUN_ID", "kind": "mcp", "role": "author"}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
            .`when`()
            .delete("/api/v1/auth/api-keys/${minted.getString("data.id")}")
            .then()
            .statusCode(204)

        // A member added and removed (WorkspaceService.removeMember: @Transactional).
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"email": "$MEMBER_EMAIL", "role": "viewer"}""")
            .`when`()
            .post("/api/v1/workspaces/default/members")
            .then()
            .statusCode(200)
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
            .`when`()
            .delete("/api/v1/workspaces/default/members/$MEMBER_ID")
            .then()
            .statusCode(204)

        // An MCP tool call (McpToolDispatcher, after every call — the request path).
        mcp(MCP_KEY.plaintext).statusCode() shouldBe 200
        // A well-formed credential nobody holds (ApiKeyFilter).
        mcp(E2eAuth.generateKey("probe-266-unknown").plaintext).statusCode() shouldBe 401

        val census =
            Regex("event=audit\\.write audit_event=(\\S+) path=(\\S+)")
                .findAll(output.out)
                .map { it.groupValues[1] to it.groupValues[2] }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, paths) -> paths.toSortedSet() }
                .toSortedMap()
        println("audit-probe census (event -> paths): ")
        census.forEach { (event, paths) -> println("audit-probe | $event | ${paths.joinToString(",")} |") }

        withClue("census: $census") {
            census["auth.api_key.created"] shouldBe sortedSetOf("transactional")
            census["mcp.tool.called"] shouldBe sortedSetOf("batched")
            census["auth.api_key.rejected"] shouldBe sortedSetOf("batched")
        }
    }

    private fun mcp(plaintext: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        ObjectMapper().writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to 1,
                                "method" to "tools/call",
                                "params" to mapOf("name" to "pipelines_list", "arguments" to mapOf("prefix" to "")),
                            ),
                        ),
                    ),
                ).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun seedRows() {
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                        "('$ADMIN_ID', 'probe-266-$RUN_ID@datapipelines.test', 'Probe 266', 'test', 'probe-266-$RUN_ID', TRUE, TRUE)",
                )
                statement.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                        "VALUES ('$DEFAULT_WORKSPACE', '$ADMIN_ID', 'workspace_admin')",
                )
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active) VALUES " +
                        "('$MEMBER_ID', '$MEMBER_EMAIL', 'Probe member', 'test', 'probe-member-$RUN_ID', TRUE)",
                )
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                        "('$KEY_IDENTITY', '${MCP_KEY.id.lowercase()}@keys.invalid', '${MCP_KEY.name}', 'key', " +
                        "'${MCP_KEY.id}', TRUE, FALSE, 'service')",
                )
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'author')",
                ).use { ps ->
                    ps.setString(1, MCP_KEY.id)
                    ps.setObject(2, KEY_IDENTITY)
                    ps.setObject(3, ADMIN_ID)
                    ps.setString(4, MCP_KEY.name)
                    ps.setString(5, MCP_KEY.hash)
                    ps.setObject(6, DEFAULT_WORKSPACE)
                    ps.executeUpdate()
                }
        }
    }

    companion object {
        private val RUN_ID = Integer.toHexString(SecureRandom().nextInt(0x10000))
        private val DEFAULT_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        private val ADMIN_ID: UUID = UUID.randomUUID()
        private val MEMBER_ID: UUID = UUID.randomUUID()
        private val MEMBER_EMAIL = "probe-member-$RUN_ID@datapipelines.test"
        private val KEY_IDENTITY: UUID = UUID.randomUUID()
        private val MCP_KEY = E2eAuth.generateKey("probe-266-mcp-$RUN_ID")
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID.toString(), "probe-266-$RUN_ID@datapipelines.test")
        private val random = SecureRandom()
        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            // #266b: the audit writer ships OFF; the census reports which rows the writer carries, so it
            // runs with the writer switched on — explicitly. The default is AuditDirectByDefaultE2eTest's.
            registry.add("datapipelines.persistence.audit.enabled") { "true" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redisHost }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add(
                "datapipelines.db.encryption-key",
            ) { Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) }) }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }
    }
}
