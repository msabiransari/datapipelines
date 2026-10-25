package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * #517 — the dependency release is ONE metadata transaction (release-dependencies record §4.1 step 7, `:130`, `:156`;
 * §13 `:307`; §11 `:261-263`; brief A.5), proved over the REAL wired application on the shared test Postgres with the
 * #205 trigger-plant mould (`RemoveMemberRollbackIntegrationTest:33-51`): a plpgsql function that raises `planted by
 * #517`, wired by a trigger narrowed by `WHEN` to the exact row transition, created inside the case and dropped in
 * `finally`; [afterAll] proves the drop (`pg_trigger` count = 0).
 *
 * Pipeline P pins two DRAFT templates; the release ticks both, so the coordinator flips them first and P last, then
 * writes one strict audit row per flip.
 * - **T1** — `BEFORE UPDATE ON pipeline_versions WHEN (DRAFT → RELEASED of P)`: the ROOT flip throws after both
 *   template flips ran. Proved on the plan path and on the legacy alias path (both route through the coordinator).
 * - **T2** — `BEFORE INSERT ON audit_log WHEN (the root's release row of this workspace)`: the LAST audit insert is
 *   refused after every flip and the dependencies' rows ran — unavailable audit storage refuses the release.
 *
 * "Ran before the throw" is witnessed, not assumed: the trigger function bumps a sequence (`nextval` is not
 * transactional, so the bump survives the rollback) only when it sees, inside the release's own transaction, that the
 * dependency — or for T2 the root — is already RELEASED. A witness of 1 means the plant fired AFTER the flips the
 * case claims were rolled back.
 *
 * Every case then reads the committed state straight from SQL — each version's status and body hash, each entity's
 * current pointer, the workspace's audit rows — and requires it equal to the snapshot taken before the attempt. With
 * the coordinator's one transaction split into one per flip, those committed-row assertions go RED (falsification 1);
 * the final case releases the same plan with no plant and sees all three flips and three rows, so the plants are the
 * only reason for the refusals above.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PipelineDependencyReleaseRollbackE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()
    private var rpcId = 0
    private lateinit var pipelineId: String
    private lateinit var pipelineHash: String
    private val templateHashes = linkedMapOf<String, String>()

    // ---- the committed state, read straight from SQL -----------------------------------------------------------

    private fun connect(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun rows(
        sql: String,
        vararg args: String,
    ): List<String> =
        connect().use { connection ->
            connection.prepareStatement(sql).use { ps ->
                args.forEachIndexed { index, arg -> ps.setString(index + 1, arg) }
                ps.executeQuery().use { rs ->
                    val columns = rs.metaData.columnCount
                    buildList { while (rs.next()) add((1..columns).joinToString(" | ") { rs.getString(it) ?: "null" }) }
                }
            }
        }

    /** Every version's status and hash, every entity's current pointer, and the workspace's audit rows. */
    private fun committed(): Map<String, List<String>> =
        mapOf(
            "templates" to
                rows(
                    "SELECT t.name, t.current_version, v.version, v.status, v.body_hash FROM templates t" +
                        " JOIN template_versions v ON v.template_id = t.id WHERE t.workspace_id = ?::uuid ORDER BY t.name, v.version",
                    WORKSPACE_ID,
                ),
            "pipelines" to
                rows(
                    "SELECT p.name, p.current_version, v.version, v.status, v.body_hash FROM pipelines p" +
                        " JOIN pipeline_versions v ON v.pipeline_id = p.id WHERE p.workspace_id = ?::uuid ORDER BY p.name, v.version",
                    WORKSPACE_ID,
                ),
            "audit" to
                rows(
                    "SELECT id, event, details_json ->> 'release_id' FROM audit_log WHERE details_json ->> 'workspace_id' = ? ORDER BY id",
                    WORKSPACE_ID,
                ),
        )

    // ---- the plant ----------------------------------------------------------------------------------------------

    /**
     * The refusal and its witness. TG_ARGV: the family whose in-transaction RELEASED status proves the flips ran
     * (`template` or `pipeline`). Both arguments are this class's own constants — no request value reaches the DDL.
     */
    private fun plantRefuseFunction() {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SEQUENCE $WITNESS")
                statement.execute(
                    """
                    CREATE FUNCTION $FUNCTION() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                    BEGIN
                        IF (TG_ARGV[0] = 'template' AND EXISTS (
                                SELECT 1 FROM template_versions v JOIN templates t ON t.id = v.template_id
                                 WHERE t.workspace_id = '$WORKSPACE_ID'::uuid AND v.status = 'RELEASED'))
                           OR (TG_ARGV[0] = 'pipeline' AND EXISTS (
                                SELECT 1 FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id
                                 WHERE p.workspace_id = '$WORKSPACE_ID'::uuid AND v.status = 'RELEASED')) THEN
                            PERFORM nextval('$WITNESS');
                        END IF;
                        RAISE EXCEPTION 'planted by #517';
                    END
                    ${'$'}${'$'}
                    """.trimIndent(),
                )
            }
        }
    }

    private fun plantTrigger(ddl: String) {
        plantRefuseFunction()
        connect().use { connection -> connection.createStatement().use { it.execute(ddl) } }
    }

    private fun dropPlant() {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TRIGGER IF EXISTS $ROOT_FLIP_TRIGGER ON pipeline_versions")
                statement.execute("DROP TRIGGER IF EXISTS $AUDIT_TRIGGER ON audit_log")
                statement.execute("DROP FUNCTION IF EXISTS $FUNCTION()")
                statement.execute("DROP SEQUENCE IF EXISTS $WITNESS")
            }
        }
    }

    /** 1 when the plant fired after the flips it witnesses; 0 when it fired before them (or never). */
    private fun witnessed(): Int = rows("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM $WITNESS").single().toInt()

    /** The root flip of P only: the exact DRAFT → RELEASED transition the coordinator performs last. */
    private fun rootFlipTrigger(): String =
        "CREATE TRIGGER $ROOT_FLIP_TRIGGER BEFORE UPDATE ON pipeline_versions FOR EACH ROW WHEN" +
            " (OLD.status = 'DRAFT' AND NEW.status = 'RELEASED' AND OLD.pipeline_id = '${UUID.fromString(pipelineId)}'::uuid)" +
            " EXECUTE FUNCTION $FUNCTION('template')"

    /** The root's release row of this workspace only — the one carrying `dependencies_released`, written last. */
    private fun auditTrigger(): String =
        "CREATE TRIGGER $AUDIT_TRIGGER BEFORE INSERT ON audit_log FOR EACH ROW WHEN" +
            " (NEW.details_json ->> 'workspace_id' = '$WORKSPACE_ID' AND NEW.details_json -> 'dependencies_released' IS NOT NULL)" +
            " EXECUTE FUNCTION $FUNCTION('pipeline')"

    /** A refused attempt: nothing committed (checked FIRST — the falsification's target), then the refusal itself. */
    private fun refusedWithNothingCommitted(
        before: Map<String, List<String>>,
        attempt: () -> Response,
    ) {
        val response = attempt()
        withClue("the plant fired after the flips this case rolls back") { witnessed() shouldBe 1 }
        withClue("committed state after the refused release equals the state before it") { committed() shouldBe before }
        withClue(response.asString()) { response.statusCode shouldBe INTERNAL_ERROR }
    }

    // ---- wire helpers -------------------------------------------------------------------------------------------

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to ++rpcId,
                                "method" to "tools/call",
                                "params" to mapOf("name" to name, "arguments" to arguments),
                            ),
                        ),
                    ),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body())
        withClue("JSON-RPC error on $name: ${response.body()}") { body.has("error") shouldBe false }
        val result = body["result"]
        return mapper.readTree(result["content"][0]["text"].asText()) to result.path("isError").asBoolean(false)
    }

    private fun releaseWithPlan(): Response =
        given()
            .port(port)
            .asSession(SESSION)
            .header("If-Match", pipelineHash)
            .contentType(ContentType.JSON)
            .body(mapper.writeValueAsString(mapOf("tree_hash" to previewOfP(), "include_dependencies" to listOf("n1", "n2"))))
            .post("/api/v1/pipelines/$pipelineId/release")

    private fun releaseWithAlias(): Response =
        given()
            .port(port)
            .asSession(SESSION)
            .header("If-Match", pipelineHash)
            .queryParam("release_pinned_templates", "true")
            .post("/api/v1/pipelines/$pipelineId/release")

    /**
     * P's plan tree hash, recomputed INDEPENDENTLY of the product (record §2.1, format `v1`) exactly as
     * `ReleaseCascadeE2eTest` does: P (n0) pins TEMPLATE_ONE (n1) and TEMPLATE_TWO (n2), all DRAFT, no pointers.
     */
    private fun previewOfP(): String {
        fun id(
            family: String,
            key: String,
        ) = "[\"$family\",\"$key\",1]"
        val p = id("pipeline", PIPELINE)
        val nodes =
            listOf(Triple(p, pipelineHash, pipelineId)) +
                TEMPLATES.map { Triple(id("template", it), templateHashes.getValue(it), templateEntityUuid(it)) }
        val nodeJson =
            nodes.map { (n, hash, uuid) ->
                "{\"id\":$n,\"entity_uuid\":\"$uuid\",\"status\":\"DRAFT\",\"body_hash\":\"$hash\",\"current\":null}"
            }
        val edgeJson =
            TEMPLATES.mapIndexed { index, t -> "{\"from\":$p,\"to\":${id("template", t)},\"path\":\"nodes[$index].template\"}" }
        val canonical =
            "{\"format\":\"v1\",\"workspace\":\"$WORKSPACE_ID\",\"root\":$p," +
                "\"nodes\":${nodeJson.sorted().joinToString(",", "[", "]")},\"edges\":${edgeJson.sorted().joinToString(",", "[", "]")}}"
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
        return "v1:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun templateEntityUuid(name: String): String =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("SELECT id FROM templates WHERE workspace_id = ?::uuid AND name = ?").use { statement ->
                statement.setString(1, WORKSPACE_ID)
                statement.setString(2, name)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }

    private fun registerDatasource() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute("SELECT 1") }
        given()
            .port(port)
            .asSession(SESSION)
            .contentType(ContentType.JSON)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Rollback (517)", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    // ---- the cases ----------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `the fixture - P pins two DRAFT templates, nothing released`() {
        registerDatasource()
        TEMPLATES.forEach { template ->
            val (created, isError) =
                callTool(
                    "templates_create",
                    mapOf(
                        "id" to template,
                        "dialect" to "H2",
                        "display_name" to "Rollback (517)",
                        "description" to "Expects: nothing (plain SQL).",
                        "body" to "SELECT 1 AS n",
                    ),
                )
            withClue("templates_create must succeed: $created") { isError shouldBe false }
            templateHashes[template] = created["body_hash"].asText()
        }
        val (created, isError) =
            callTool(
                "pipelines_create",
                mapOf(
                    "name" to PIPELINE,
                    "display_name" to "Rollback (517)",
                    "description" to "Pins two draft templates the release flips first.",
                    "nodes" to
                        TEMPLATES.mapIndexed { index, template ->
                            val node =
                                mutableMapOf<String, Any?>(
                                    "id" to "n$index",
                                    "type" to "DQL",
                                    "source" to DATASOURCE,
                                    "description" to "One row",
                                    "template" to mapOf("id" to template, "version" to 1),
                                    "depends_on" to emptyList<String>(),
                                )
                            // At most one node answers the caller (§12): the first stages to tempdb.
                            if (index < TEMPLATES.lastIndex) node["output"] = mapOf("target" to "tempdb", "table" to "step_$index")
                            node
                        },
                ),
            )
        withClue("pipelines_create must succeed: $created") { isError shouldBe false }
        pipelineId = created["id"].asText()
        pipelineHash = created["draft"]["body_hash"].asText()

        val state = committed()
        withClue(state) {
            state.getValue("templates").map { it.split(" | ")[STATUS_COLUMN] } shouldBe listOf("DRAFT", "DRAFT")
            state.getValue("pipelines").map { it.split(" | ")[STATUS_COLUMN] } shouldBe listOf("DRAFT")
        }
    }

    @Test
    @Order(2)
    fun `T1 plan path - the root flip throws after both templates flipped, and every flip, pointer and row rolls back`() {
        val before = committed()
        plantTrigger(rootFlipTrigger())
        try {
            refusedWithNothingCommitted(before) { releaseWithPlan() }
        } finally {
            dropPlant()
        }
    }

    @Test
    @Order(3)
    fun `T1 legacy alias - the same root failure rolls the alias's template flips back too`() {
        val before = committed()
        plantTrigger(rootFlipTrigger())
        try {
            refusedWithNothingCommitted(before) { releaseWithAlias() }
        } finally {
            dropPlant()
        }
    }

    @Test
    @Order(4)
    fun `T2 - a refused audit insert refuses the release, after every flip ran - nothing is committed`() {
        val before = committed()
        plantTrigger(auditTrigger())
        try {
            refusedWithNothingCommitted(before) { releaseWithPlan() }
        } finally {
            dropPlant()
        }
    }

    @Test
    @Order(5)
    fun `control - the same plan with no plant releases all three in one commit with one row per flip`() {
        val response = releaseWithPlan()

        withClue(response.asString()) { response.statusCode shouldBe 200 }
        val state = committed()
        withClue(state) {
            state.getValue("templates").map { it.split(" | ")[STATUS_COLUMN] } shouldBe listOf("RELEASED", "RELEASED")
            state.getValue("pipelines").map { it.split(" | ")[STATUS_COLUMN] } shouldBe listOf("RELEASED")
            // The creates above audited too; the release's rows are the ones carrying a release_id.
            val releaseIds = state.getValue("audit").map { it.split(" | ")[2] }.filter { it != "null" }
            releaseIds.size shouldBe TEMPLATES.size + 1
            releaseIds.toSet().size shouldBe 1
        }
    }

    @AfterAll
    fun dropAndProve() {
        dropPlant()
        val leftovers =
            rows(
                "SELECT (SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'dp517%')" +
                    " + (SELECT count(*) FROM pg_proc WHERE proname LIKE 'dp517%')" +
                    " + (SELECT count(*) FROM pg_class WHERE relname LIKE 'dp517%')",
            ).single()
        // The evidence line the brief asks for, in this class's XML system-out.
        println("pg_trigger/pg_proc/pg_class dp517 objects after the class = $leftovers")
        oidc.close()
        leftovers shouldBe "0"
    }

    companion object {
        private const val TEMPLATE_ONE = "test/rollback_517_one.sql"
        private const val TEMPLATE_TWO = "test/rollback_517_two.sql"
        private val TEMPLATES = listOf(TEMPLATE_ONE, TEMPLATE_TWO)
        private const val PIPELINE = "test/rollback_517"
        private const val DATASOURCE = "h2-rollback-517"
        private const val H2_JDBC_URL = "jdbc:h2:mem:rollback517;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val WORKSPACE = "rollback-517"

        private const val FUNCTION = "dp517_refuse"
        private const val WITNESS = "dp517_witness"
        private const val ROOT_FLIP_TRIGGER = "dp517_root_flip_refuse"
        private const val AUDIT_TRIGGER = "dp517_audit_refuse"

        /** The unhandled store failure's answer: refused, with a correlation id; no release code exists for it. */
        private const val INTERNAL_ERROR = 500
        private const val STATUS_COLUMN = 3

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val KEY = E2eAuth.generateKey("e2e-517-rollback-key")
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-517-rollback@datapipelines.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("datapipelines.redis.password") { "" }
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
            // API mode: this suite only authors and releases — no dispatching context may take other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Rollback 517')",
                    )
                    // A super admin (instance datasources) who is also this workspace's admin (an author there).
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-517-rollback@datapipelines.test', 'E2E 517', 'test', 'e2e-517-rollback-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${KEY.id.lowercase()}@keys.invalid', '${KEY.name}', 'key', " +
                            "'${KEY.id}', TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?::uuid, ?::uuid, ?, ?, ?::uuid, 'mcp', 'workspace_admin')",
                    ).use { ps ->
                        ps.setString(1, KEY.id)
                        ps.setString(2, KEY_IDENTITY)
                        ps.setString(3, ADMIN_ID)
                        ps.setString(4, KEY.name)
                        ps.setString(5, KEY.hash)
                        ps.setString(6, WORKSPACE_ID)
                        ps.executeUpdate()
                    }
            }
        }
    }
}
