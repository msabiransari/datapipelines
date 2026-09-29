package co.datapipelines.integration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalManagementPort
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
 * #266b — the ruling, over the REAL application with its SHIPPED persistence configuration: no
 * `datapipelines.persistence.*` key is set here, so `application.yml`'s defaults bind, and
 * `datapipelines.persistence.audit.enabled` ships false (configuration §3.32). Every audit row is
 * then the direct INSERT — the path the batched suites (`PersistenceBatchingE2eTest`,
 * `AuditTransactionProbeE2eTest`) switch away from explicitly.
 *
 * Two witnesses, because either alone could be fooled: `AuditLogger`'s DEBUG line reports the path
 * each row took (`event=audit.write … path=direct`), and the audit writer's own meters — still
 * bound, the writer is built idle — read zero batches. The rows chosen are the request-path ones
 * the writer exists for and would carry if it were on: an MCP call's `mcp.tool.called` and a
 * rejected credential's `auth.api_key.rejected`. Red with the default flipped in `application.yml`.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension::class)
class AuditDirectByDefaultE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

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
    fun `with the shipped configuration, request-path audit rows take the direct INSERT and the audit writer commits nothing`(
        output: CapturedOutput,
    ) {
        checkNotNull(metric("datapipelines.persistence.batch.size", "store:audit")) {
            "the audit writer's meters are bound whether or not it is used — absent means the wiring changed"
        }

        mcp(MCP_KEY.plaintext).statusCode() shouldBe 200
        mcp(E2eAuth.generateKey("default-266b-unknown").plaintext).statusCode() shouldBe 401

        val census =
            Regex("event=audit\\.write audit_event=(\\S+) path=(\\S+)")
                .findAll(output.out)
                .map { it.groupValues[1] to it.groupValues[2] }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, paths) -> paths.toSortedSet() }
                .toSortedMap()
        println("audit-default census (event -> paths): $census")
        withClue("census: $census") {
            census["mcp.tool.called"] shouldBe sortedSetOf("direct")
            census["auth.api_key.rejected"] shouldBe sortedSetOf("direct")
        }
        withClue("non-vacuity: the MCP call's row was written — by the direct INSERT") { toolCalledRows() shouldBe 1 }
        withClue("the audit writer committed nothing — not these rows, not any row since the application started") {
            statistic(metric("datapipelines.persistence.batch.size", "store:audit").shouldNotBeNull(), "COUNT") shouldBe 0.0
        }
    }

    private fun mcp(plaintext: String): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
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

    /**
     * `/actuator/metrics/{name}` on the management port; null when THAT meter does not exist. The
     * endpoint itself must answer — reading "unreachable" as "zero" would pass the assertions above.
     */
    private fun metric(
        name: String,
        tag: String,
    ): JsonNode? {
        fun get(url: String) =
            http.send(
                HttpRequest
                    .newBuilder(URI.create(url))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        val root = get("http://localhost:$managementPort/actuator/metrics")
        check(root.statusCode() == 200) { "the metrics endpoint answered ${root.statusCode()} — the case cannot read a meter" }
        val response = get("http://localhost:$managementPort/actuator/metrics/$name?tag=$tag")
        return if (response.statusCode() == 200) mapper.readTree(response.body()) else null
    }

    private fun statistic(
        node: JsonNode,
        statistic: String,
    ): Double = node["measurements"].single { it["statistic"].asText() == statistic }["value"].asDouble()

    private fun toolCalledRows(): Int {
        val pg = SharedE2e.postgres
        return DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection
                .prepareStatement("SELECT COUNT(*) FROM audit_log WHERE event = 'mcp.tool.called' AND key_id = ?")
                .use { ps ->
                    ps.setString(1, MCP_KEY.id)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }
    }

    private fun seedRows() {
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                        "('$ADMIN_ID', 'default-266b-$RUN_ID@datapipelines.test', 'Default 266b', 'test', 'default-266b-$RUN_ID', TRUE, TRUE)",
                )
                statement.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                        "VALUES ('$DEFAULT_WORKSPACE', '$ADMIN_ID', 'workspace_admin')",
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
        private val KEY_IDENTITY: UUID = UUID.randomUUID()
        private val MCP_KEY = E2eAuth.generateKey("default-266b-mcp-$RUN_ID")
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID.toString(), "default-266b-$RUN_ID@datapipelines.test")
        private val random = SecureRandom()
        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            // NO datapipelines.persistence.* key: the shipped defaults are what this case is about.
            registry.add("management.server.port") { "0" }
            registry.add("management.endpoints.web.exposure.include") { "health,metrics" }
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
