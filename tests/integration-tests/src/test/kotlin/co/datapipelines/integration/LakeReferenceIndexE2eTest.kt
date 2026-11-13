package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #778 — the lake reference index and the promotion-time lake check, end to end (dp-lake design record §7 (1), slice
 * D-A…D-F, versioning §10.5).
 *
 * Two deployments, each on its own scratch database: this class's own context is the AUTHORING source (it releases,
 * imports and backfills), and a hand-booted RECEIVER (authoring off, the pre-shared promotion key) takes the batch. The
 * lake datasource has the same NAME on both; `lk.events` is registered on the source only until the last case.
 *
 * - A release indexes every version it flips, in its transaction; the weakest site decides the version's status.
 * - A scanner that throws rolls the release back: the version stays DRAFT and no scan row exists. The failure is planted
 *   in the DATABASE (a trigger refusing that version's scan row): this module sees only `app`, never the scanner's types.
 * - An import indexes the version it lands; the boot backfill re-indexes what has no row, and a second pass is a no-op
 *   (the backfill bean is reached by NAME and its `backfill()` by reflection, for the same reason).
 * - **The issue's acceptance:** the batch reading `lk.events` is refused by a receiver that has not registered it, with
 *   the catalogued 409 naming the pipeline and the table, and the receiver is byte-unchanged; the SAME batch lands once
 *   the table is registered there.
 * - #790: on a FILE-backed lake datasource, a three-part name in the file's own catalog indexes under the one-segment
 *   namespace (the datasource's default catalog is the file's name, not the parser's `memory`).
 *
 * Every assertion about what a deployment holds reads its DATABASE, not a service's report.
 */
@ExtendWith(SpringExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class LakeReferenceIndexE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val mapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @Test
    @Order(1)
    fun `fixture - the lake datasource on both deployments, lk_events registered on the source only`() {
        registerLakeDatasourceOn(port)
        registerLakeDatasourceOn(receiverPort)
        registerEventsTableOn(port)
    }

    @Test
    @Order(2)
    fun `a release indexes the pipeline it flips - the resolved table, and the weakest site's status`() {
        createTemplate(EXACT_TEMPLATE, "SELECT id FROM lk.events")
        createTemplate(BRANCHING_TEMPLATE, "<#if true>SELECT id FROM lk.events<#else>SELECT id FROM lk.retired</#if>")
        val (id, hash) = createPipeline(PIPELINE, listOf("read_exact" to EXACT_TEMPLATE, "read_branch" to BRANCHING_TEMPLATE))
        releaseTemplate(EXACT_TEMPLATE)
        releaseTemplate(BRANCHING_TEMPLATE)
        release(id, hash).first shouldBe 200

        val scan =
            sourceJdbc.row(
                "SELECT status, scanner_version, reasons_json::text AS reasons, version FROM lake_reference_scans WHERE entity_id = '$id'",
            )
        withClue("an <#if> site makes the version PARTIAL: $scan") { scan["status"] shouldBe "partial" }
        scan["version"] shouldBe "1"
        scan["scanner_version"] shouldBe SCANNER_VERSION
        val reasons = mapper.readTree(scan["reasons"])
        // A finding names its site (`node:<id>` / `selector:<name>`) and the construct, never rendered SQL.
        reasons.map { it.path("kind").asText() to it.path("site").asText() } shouldContain ("if" to "node:read_branch")
        reasons.none { it.path("site").asText() == "node:read_exact" } shouldBe true

        // Non-vacuity: at least one resolved row per fixture datasource, both nodes resolving lk.events.
        val resolved =
            sourceJdbc.rows(
                "SELECT node_id, array_to_string(namespace, '.') AS ns, table_name FROM lake_table_references " +
                    "WHERE entity_id = '$id' AND resolved AND datasource_name = '$DATASOURCE' ORDER BY node_id",
            )
        resolved.size shouldBeGreaterThanOrEqual 1
        resolved.map { Triple(it["node_id"], it["ns"], it["table_name"]) } shouldBe
            listOf(Triple("read_branch", "lk", "events"), Triple("read_exact", "lk", "events"))
    }

    @Test
    @Order(3)
    fun `a scanner that throws rolls the release back - the version stays DRAFT and no scan row exists`() {
        val (id, hash) = createPipeline(FAILING_PIPELINE, listOf("read_exact" to EXACT_TEMPLATE))
        sourceJdbc.execute(
            "CREATE FUNCTION lk778_planted_scan_failure() RETURNS trigger LANGUAGE plpgsql AS " +
                "${'$'}${'$'} BEGIN RAISE EXCEPTION 'planted scanner failure'; END ${'$'}${'$'}",
        )
        sourceJdbc.execute(
            "CREATE TRIGGER lk778_planted_scan_failure BEFORE INSERT ON lake_reference_scans FOR EACH ROW " +
                "WHEN (NEW.entity_id = '$id') EXECUTE FUNCTION lk778_planted_scan_failure()",
        )
        val (status, body) =
            try {
                release(id, hash)
            } finally {
                sourceJdbc.execute("DROP TRIGGER lk778_planted_scan_failure ON lake_reference_scans")
                sourceJdbc.execute("DROP FUNCTION lk778_planted_scan_failure()")
            }
        withClue("the planted scanner failure must refuse the release: $body") { (status >= 500) shouldBe true }
        sourceJdbc.scalar("SELECT status FROM pipeline_versions WHERE pipeline_id = '$id' AND version = 1") shouldBe "DRAFT"
        sourceJdbc.scalar("SELECT count(*) FROM pipelines WHERE id = '$id' AND current_version IS NOT NULL") shouldBe "0"
        sourceJdbc.scalar("SELECT count(*) FROM lake_reference_scans WHERE entity_id = '$id'") shouldBe "0"
    }

    @Test
    @Order(4)
    fun `an import indexes the version it lands - a same-hash re-import re-indexes it`() {
        val id = pipelineId(PIPELINE)
        sourceJdbc.execute("DELETE FROM lake_reference_scans WHERE entity_id = '$id'")
        val (status, body) = rest("POST", "/api/v1/pipelines/import", mapper.writeValueAsString(pipelinePayload()))
        withClue("the idempotent re-import must answer 200: $body") { status shouldBe 200 }
        sourceJdbc.scalar("SELECT status FROM lake_reference_scans WHERE entity_id = '$id' AND version = 1") shouldBe "partial"
    }

    @Test
    @Order(5)
    fun `the boot backfill indexes a released version with no scan row once - the second pass is a no-op`() {
        val id = pipelineId(PIPELINE)
        sourceJdbc.execute("DELETE FROM lake_reference_scans WHERE entity_id = '$id'")

        val (scanned, failed) = backfillPass()
        withClue("non-vacuity: the pass must scan the version it lacks") { scanned shouldBeGreaterThanOrEqual 1 }
        failed shouldBe 0
        sourceJdbc.scalar("SELECT count(*) FROM lake_table_references WHERE entity_id = '$id' AND resolved") shouldBe "2"

        backfillPass() shouldBe (0 to 0)
    }

    @Test
    @Order(6)
    fun `a batch reading a lake table the receiver has not registered is refused whole, naming pipeline and table`() {
        val before = receiverFingerprint()
        val (status, body) = pushBatch(batch())

        withClue("the receiver must refuse the batch: $body") { status shouldBe 409 }
        val error = body.path("error")
        error.path("code").asText() shouldBe "pipeline.promotion.lake_table_unavailable"
        val unavailable = error.path("details").path("unavailable")
        val rows = unavailable.map { it.path("node_id").asText() to it }.toMap()
        rows.keys shouldBe setOf("read_branch", "read_exact")
        rows.getValue("read_exact").let { row ->
            row.path("family").asText() shouldBe "pipeline"
            row.path("name").asText() shouldBe PIPELINE
            row.path("version").asInt() shouldBe 1
            row.path("datasource").asText() shouldBe DATASOURCE
            row.path("table").asText() shouldBe "lk.events"
            row.path("state").asText() shouldBe "absent"
            row.path("via").isArray shouldBe true
        }
        error.path("details").path("unavailable_truncated").asBoolean() shouldBe false
        withClue("a refused batch leaves the receiver byte-unchanged") { receiverFingerprint() shouldBe before }
    }

    @Test
    @Order(7)
    fun `the same batch lands once the receiver registers the table - and is indexed there`() {
        registerEventsTableOn(receiverPort)
        val (status, body) = pushBatch(batch())

        withClue("the receiver must accept the batch now: $body") { status shouldBe 200 }
        val id = pipelineId(PIPELINE)
        receiverJdbc.scalar("SELECT status FROM pipeline_versions WHERE pipeline_id = '$id' AND version = 1") shouldBe "RELEASED"
        receiverJdbc.scalar("SELECT status FROM lake_reference_scans WHERE entity_id = '$id' AND version = 1") shouldBe "partial"
    }

    @Test
    @Order(8)
    fun `a file-backed lake datasource - a three-part name in the file's own catalog indexes under the one-segment namespace`() {
        val catalog = "f790$RUN"
        val database = Files.createTempDirectory("lake-790-it").resolve("$catalog.db")
        val (registered, registration) =
            rest(
                "POST",
                "/api/v1/datasources",
                """{"name": "$FILE_DATASOURCE", "display_name": "790 file lake", "dialect": "LAKE",
                   "jdbc_url": "jdbc:duckdb:${database.toAbsolutePath()}", "credential": {"kind": "none"}}""",
            )
        withClue("the file-backed lake datasource must register: $registration") { registered shouldBe 201 }
        createTemplate(FILE_TEMPLATE, "SELECT id FROM $catalog.lk.events")
        val (id, hash) = createPipeline(FILE_PIPELINE, listOf("read_file" to FILE_TEMPLATE), source = FILE_DATASOURCE)
        releaseTemplate(FILE_TEMPLATE)
        release(id, hash).first shouldBe 200

        val row =
            sourceJdbc.row(
                "SELECT written_catalog, resolved::text AS resolved, array_to_string(namespace, '.') AS ns, table_name " +
                    "FROM lake_table_references WHERE entity_id = '$id' AND datasource_name = '$FILE_DATASOURCE'",
            )
        withClue("the file's catalog names the one-segment namespace: $row") {
            row shouldBe mapOf("written_catalog" to catalog, "resolved" to "true", "ns" to "lk", "table_name" to "events")
        }
        sourceJdbc.scalar("SELECT scanner_version::text FROM lake_reference_scans WHERE entity_id = '$id'") shouldBe SCANNER_VERSION
    }

    // ---- fixture helpers ----------------------------------------------------------------------

    private fun rest(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        target: Int = port,
    ): Pair<Int, JsonNode> {
        var request = given().port(target).contentType(ContentType.JSON).asSession(SESSION)
        headers.forEach { (name, value) -> request = request.header(name, value) }
        if (body != null) request = request.body(body)
        val sent = request.`when`()
        val response = (if (method == "POST") sent.post(path) else sent.get(path)).then().extract()
        val text = response.asString()
        return response.statusCode() to (if (text.isBlank()) mapper.createObjectNode() else mapper.readTree(text))
    }

    private fun registerLakeDatasourceOn(target: Int) {
        val (status, body) =
            rest(
                "POST",
                "/api/v1/datasources",
                """{"name": "$DATASOURCE", "display_name": "778 lake", "dialect": "LAKE",
                   "jdbc_url": "jdbc:duckdb::memory:", "credential": {"kind": "none"}}""",
                target = target,
            )
        withClue("the lake datasource must register: $body") { status shouldBe 201 }
    }

    private fun registerEventsTableOn(target: Int) {
        val (status, body) =
            rest(
                "POST",
                "/api/v1/datasources/$DATASOURCE/tables",
                """{"namespace": ["lk"], "name": "events", "format": "parquet", "location": "file://${eventsParquet.toAbsolutePath()}"}""",
                target = target,
            )
        withClue("lk.events must register: $body") { status shouldBe 201 }
    }

    private fun createTemplate(
        id: String,
        body: String,
    ) {
        val (status, created) =
            rest(
                "POST",
                "/api/v1/templates",
                """{"id": "$id", "dialect": "LAKE", "display_name": "778 lake read", "description": "lake reference index E2E",
                   "imports": [], "body": ${mapper.writeValueAsString(body)}}""",
            )
        withClue("template $id must create: $created") { status shouldBe 201 }
    }

    /** Creates a DRAFT pipeline of one independent DQL node per (node id, template); answers its id and draft body hash. */
    private fun createPipeline(
        name: String,
        nodes: List<Pair<String, String>>,
        source: String = DATASOURCE,
    ): Pair<String, String> {
        val document = mapper.createObjectNode().put("name", name)
        val array = document.putArray("nodes")
        nodes.forEachIndexed { index, (nodeId, template) ->
            array
                .addObject()
                .put("id", nodeId)
                .put("description", "read the lake events")
                .put("type", "DQL")
                .put("source", source)
                .also { node ->
                    node.putObject("template").put("id", template).put("version", 1)
                    // One caller node per pipeline: every node before the last stages into tempdb.
                    if (index == nodes.lastIndex) {
                        node.putObject("output").put("target", "caller")
                    } else {
                        node.putObject("output").put("target", "tempdb").put("table", "stg_$nodeId")
                    }
                    node.putArray("depends_on")
                }
        }
        val (status, created) = rest("POST", "/api/v1/pipelines", mapper.writeValueAsString(document))
        withClue("pipeline $name must create: $created") { status shouldBe 201 }
        val data = created.path("data")
        return data.path("id").asText() to data.path("draft").path("body_hash").asText()
    }

    /** Releases the DRAFT template [id] v1 through its own route, at its stored hash (the legacy cascade flag is gone, #876 r3). */
    private fun releaseTemplate(id: String) {
        val hash =
            sourceJdbc.scalar(
                "SELECT tv.body_hash FROM template_versions tv JOIN templates t ON t.id = tv.template_id " +
                    "WHERE t.name = '$id' AND tv.version = 1",
            )
        val (status, body) = rest("POST", "/api/v1/templates/release", """{"name": "$id"}""", mapOf("If-Match" to hash))
        withClue("template $id must release: $body") { status shouldBe 200 }
    }

    /** Releases the pipeline's draft alone, no flag and no plan: every template it pins must already be RELEASED. */
    private fun release(
        id: String,
        hash: String,
    ): Pair<Int, JsonNode> {
        val response =
            given()
                .port(port)
                .asSession(SESSION)
                .header("If-Match", hash)
                .`when`()
                .post("/api/v1/pipelines/$id/release")
                .then()
                .extract()
        return response.statusCode() to mapper.readTree(response.asString())
    }

    /** One `LakeReferenceBackfill.backfill()` pass on this context's bean: (scanned, failed). */
    private fun backfillPass(): Pair<Int, Int> {
        val bean = context.getBean("lakeReferenceBackfill")
        val outcome = bean.javaClass.getMethod("backfill").invoke(bean)

        fun count(getter: String) = outcome.javaClass.getMethod(getter).invoke(outcome) as Int
        return count("getScanned") to count("getFailed")
    }

    private fun pipelineId(name: String): String = sourceJdbc.scalar("SELECT id::text FROM pipelines WHERE name = '$name'")

    /** The source's export of [PIPELINE], as the sender ships it: body + id, version, body_hash, server fields stripped. */
    private fun export(): JsonNode {
        val (status, body) = rest("GET", "/api/v1/pipelines/${pipelineId(PIPELINE)}/export")
        withClue("the export must answer: $body") { status shouldBe 200 }
        return body.path("data")
    }

    private fun pipelinePayload(): ObjectNode {
        val payload = export().path("pipeline").deepCopy<ObjectNode>()
        listOf("status", "owner", "created_at", "updated_at", "current_version", "draft").forEach { payload.remove(it) }
        return payload
    }

    /** The §10.4 batch: the pipeline's pinned templates first, then the pipeline. */
    private fun batch(): ObjectNode {
        val exported = export()
        return mapper
            .createObjectNode()
            .put("source_env", "lk-src")
            .put("key_fingerprint", "e2e-fingerprint")
            .put("workspace", WORKSPACE)
            .also { batch ->
                batch.set<JsonNode>("templates", exported.path("templates"))
                batch.set<JsonNode>("pipelines", mapper.createArrayNode().add(pipelinePayload()))
            }
    }

    private fun pushBatch(batch: JsonNode): Pair<Int, JsonNode> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$receiverPort/api/v1/promotion/push"))
                .header("Content-Type", "application/json")
                .header(PROMOTION_HEADER, SERVER_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(batch)))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to mapper.readTree(response.body())
    }

    /** Every content table a batch could write, hashed row by row (byte order, so the two reads compare exactly). */
    private fun receiverFingerprint(): Map<String, String> =
        CONTENT_TABLES.associateWith { table ->
            receiverJdbc.scalar(
                "SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text COLLATE \"C\")), '') FROM $table t",
            )
        }

    companion object {
        private const val PROMOTION_HEADER = "DP-Promotion-Key"
        private val RUN: String = UUID.randomUUID().toString().substring(0, 8)
        private val WORKSPACE = "lk_$RUN"
        private val DATASOURCE = "lk778_$RUN"
        private val EXACT_TEMPLATE = "lk$RUN/events_exact.sql"
        private val BRANCHING_TEMPLATE = "lk$RUN/events_branching.sql"
        private val PIPELINE = "lk$RUN/lake/events_read"
        private val FAILING_PIPELINE = "lk$RUN/lake/events_read_failing"
        private val FILE_DATASOURCE = "lk790_$RUN"
        private val FILE_TEMPLATE = "lk$RUN/events_file.sql"
        private val FILE_PIPELINE = "lk$RUN/lake/events_read_file"

        private val CONTENT_TABLES =
            listOf(
                "templates",
                "template_versions",
                "pipelines",
                "pipeline_versions",
                "lake_reference_scans",
                "lake_table_references",
                "lake_tables",
            )

        /** `LakeReferenceScanService.SCANNER_VERSION` — spelled here, since this module cannot import it. */
        private const val SCANNER_VERSION = "2"

        private val sourceDb = SharedE2e.scratchDatabase("lake_reference_778_src")
        private val receiverDb = SharedE2e.scratchDatabase("lake_reference_778_recv")
        private val sourceJdbc = ScratchJdbc(sourceDb.jdbcUrl, sourceDb.username, sourceDb.password)
        private val receiverJdbc = ScratchJdbc(receiverDb.jdbcUrl, receiverDb.username, receiverDb.password)

        private val JWT_SECRET: String = E2eSession.newSecret()
        private val ENCRYPTION_KEY: String = E2eSession.newSecret()
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "e2e-778@datapipelines.test"
        private val SESSION: String get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL, workspace = WORKSPACE)
        private val SERVER_KEY: String = "dpk-778-e2e-server-${UUID.randomUUID()}"

        private var receiver: ConfigurableApplicationContext? = null
        private var receiverPort: Int = 0
        private lateinit var eventsParquet: Path

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { sourceDb.jdbcUrl }
            registry.add("spring.datasource.username") { sourceDb.username }
            registry.add("spring.datasource.password") { sourceDb.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.local.enabled") { "true" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        /**
         * The receiver, booted BEFORE the authoring context (a receiver refuses to boot beside a draft, and the source
         * makes some), on its own fresh database; both deployments' auth rows are seeded once their Flyway has run —
         * the source's in the first case's context, which is up by then.
         */
        @BeforeAll
        @JvmStatic
        fun bootReceiver() {
            eventsParquet = Files.createTempDirectory("lake-778-it").resolve("events.parquet")
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use {
                    it.execute("COPY (SELECT 1 AS id) TO '${eventsParquet.toAbsolutePath()}' (FORMAT PARQUET)")
                }
            }
            val context =
                SpringApplicationBuilder(DatapipelinesApplication::class.java).run(
                    "--server.port=0",
                    "--management.server.port=0",
                    "--spring.datasource.url=${receiverDb.jdbcUrl}",
                    "--spring.datasource.username=${receiverDb.username}",
                    "--spring.datasource.password=${receiverDb.password}",
                    "--datapipelines.redis.password=",
                    "--datapipelines.redis.host=${SharedE2e.redisHost}",
                    "--datapipelines.redis.port=${SharedE2e.redisPort}",
                    "--datapipelines.jwt.secret=$JWT_SECRET",
                    "--datapipelines.db.encryption-key=$ENCRYPTION_KEY",
                    "--datapipelines.auth.local.enabled=true",
                    "--datapipelines.auth.base-url=http://localhost:8080",
                    "--datapipelines.posture=development",
                    "--datapipelines.env=lk-receiver",
                    "--datapipelines.deployment.authoring-enabled=false",
                    "--datapipelines.deployment.promotion.server-key=$SERVER_KEY",
                )
            receiver = context
            receiverPort = Integer.parseInt(checkNotNull(context.environment.getProperty("local.server.port")))
            seed(receiverJdbc)
        }

        @AfterAll
        @JvmStatic
        fun closeReceiver() {
            receiver?.close()
        }

        /** The admin user and the one workspace, under the same names and ids on both deployments. */
        fun seed(jdbc: ScratchJdbc) {
            jdbc.execute(
                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) " +
                    "VALUES ('$ADMIN_USER_ID', '$ADMIN_EMAIL', 'E2E 778', 'test', 'e2e-778-sub', TRUE, TRUE) ON CONFLICT DO NOTHING",
            )
            val id = UUID.nameUUIDFromBytes(WORKSPACE.toByteArray(Charsets.UTF_8)).toString()
            jdbc.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$id', '$WORKSPACE', 'Lake 778') ON CONFLICT DO NOTHING")
            jdbc.execute(
                "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$id', '$ADMIN_USER_ID', 'workspace_admin') " +
                    "ON CONFLICT DO NOTHING",
            )
        }

        /** The source's auth rows go in once, after its context (and so its Flyway) is up — see [sourceSeeded]. */
        private val sourceSeeded = AtomicBoolean(false)
    }

    /** The source's auth rows, once per class, as soon as its context (and so its schema) is up. */
    @BeforeEach
    fun seedSource() {
        if (sourceSeeded.compareAndSet(false, true)) seed(sourceJdbc)
    }

    /** A tiny JDBC reader: what a deployment holds is a DATABASE fact. */
    class ScratchJdbc(
        private val url: String,
        private val user: String,
        private val password: String,
    ) {
        fun scalar(sql: String): String = row(sql).values.first()

        fun row(sql: String): Map<String, String> = rows(sql).also { check(it.isNotEmpty()) { "no row for: $sql" } }.first()

        fun rows(sql: String): List<Map<String, String>> =
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { statement -> statement.executeQuery(sql).use(::readAll) }
            }

        private fun readAll(rs: java.sql.ResultSet): List<Map<String, String>> {
            val out = ArrayList<Map<String, String>>()
            while (rs.next()) {
                out += (1..rs.metaData.columnCount).associate { i -> rs.metaData.getColumnLabel(i) to (rs.getString(i) ?: "") }
            }
            return out
        }

        fun execute(sql: String) {
            DriverManager.getConnection(url, user, password).use { connection -> connection.createStatement().use { it.execute(sql) } }
        }
    }
}
