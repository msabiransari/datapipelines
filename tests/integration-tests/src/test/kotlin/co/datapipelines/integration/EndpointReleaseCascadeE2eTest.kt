package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.core.type.TypeReference
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
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #786 A42 — an API draft is a releasable root: the release cascade flips every selected dependency FIRST and the API
 * LAST, in ONE metadata transaction with one strict audit row per flip (record §8, §11; the
 * [ReleaseCascadeE2eTest] / [PipelineDependencyReleaseRollbackE2eTest] molds), over the real wired application.
 *
 * API A's v1 DRAFT pins pipeline P's v1 DRAFT. The release posts a plan whose `tree_hash` is computed by this test's
 * own oracle ([treeHash]), never by the product, and ticks P.
 *
 * - **Child first, witnessed.** A trigger on the API's DRAFT → RELEASED flip bumps one of two sequences (`nextval` is
 *   not transactional, so the bump survives a rollback): `child_first` when P's v1 is ALREADY RELEASED inside the
 *   release's own transaction at that moment, `root_first` when it is not. Falsification: swap the coordinator's flip
 *   order so the root flips before its dependencies → `root_first` = 1 → red.
 * - **Atomicity baseline.** The context's release audit sink is the production strict sink behind a switch
 *   ([FailingAuditConfiguration]); armed, it throws a JAVA exception when the API's own `endpoint.released` row is
 *   written — after both flips and P's row. Every flip, pointer and audit row must roll back (read at the TABLE).
 *   Java, not a database error, on purpose: a PostgreSQL error would abort the transaction by itself and hide the
 *   mutant below. Mutant: catch and swallow that audit failure inside the coordinator → BOTH flips commit (P and A read
 *   RELEASED, the persisted state the "all unchanged" assertion then sees) → red.
 * - **Control.** The same plan with nothing armed releases P then A in one commit: two rows sharing one `release_id`,
 *   P's first.
 * - **#894 provenance.** A second closure — API v1 DRAFT pins pipeline Q's v2 DRAFT (v1 released, so the two version
 *   numbers differ), Q pins two DRAFT templates — released with every dependency ticked: exactly those two
 *   `template.version.released` rows, each naming the API in `cascade_from_api_endpoint_id` with the API's version in
 *   `cascade_from_version`, no `cascade_from_pipeline_id`, `cascade_root` unchanged (release-dependencies record §11,
 *   the 2026-11-11 amendment). Falsified by sending the API root back through the pipeline arm (red on the key) and by
 *   taking the version from Q (red on the version).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(EndpointReleaseCascadeE2eTest.FailingAuditConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EndpointReleaseCascadeE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private lateinit var pipelineId: String
    private lateinit var pipelineHash: String
    private lateinit var endpointId: String
    private lateinit var endpointPath: String
    private lateinit var endpointHash: String

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

    /** P's versions and pointer, A's versions and pointer, and the workspace's audit rows. */
    private fun committed(): Map<String, List<String>> =
        mapOf(
            "pipeline" to
                rows(
                    "SELECT p.current_version, v.version, v.status, v.body_hash FROM pipelines p" +
                        " JOIN pipeline_versions v ON v.pipeline_id = p.id WHERE p.workspace_id = ?::uuid ORDER BY v.version",
                    WORKSPACE_ID,
                ),
            "api" to
                rows(
                    "SELECT e.current_version, v.version, v.status, v.body_hash FROM published_endpoints e" +
                        " JOIN published_endpoint_versions v ON v.endpoint_id = e.id WHERE e.workspace_id = ?::uuid ORDER BY v.version",
                    WORKSPACE_ID,
                ),
            "audit" to
                rows(
                    "SELECT id, event, details_json ->> 'release_id' FROM audit_log WHERE details_json ->> 'workspace_id' = ? ORDER BY id",
                    WORKSPACE_ID,
                ),
        )

    private fun statuses(
        state: Map<String, List<String>>,
        key: String,
    ): List<String> = state.getValue(key).map { it.split(" | ")[STATUS_COLUMN] }

    // ---- the witness --------------------------------------------------------------------------------------------

    /** The child-first witness on A's flip. Every name is this class's constant; no request value reaches the DDL. */
    private fun plantWitness() {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SEQUENCE $CHILD_FIRST")
                statement.execute("CREATE SEQUENCE $ROOT_FIRST")
                statement.execute(
                    """
                    CREATE FUNCTION $FUNCTION() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                    BEGIN
                        IF EXISTS (SELECT 1 FROM pipeline_versions
                                    WHERE pipeline_id = '$pipelineId'::uuid AND version = 1 AND status = 'RELEASED') THEN
                            PERFORM nextval('$CHILD_FIRST');
                        ELSE
                            PERFORM nextval('$ROOT_FIRST');
                        END IF;
                        RETURN NEW;
                    END
                    ${'$'}${'$'}
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE TRIGGER $TRIGGER BEFORE UPDATE ON published_endpoint_versions FOR EACH ROW WHEN" +
                        " (OLD.status = 'DRAFT' AND NEW.status = 'RELEASED' AND OLD.endpoint_id = '$endpointId'::uuid)" +
                        " EXECUTE FUNCTION $FUNCTION()",
                )
            }
        }
    }

    private fun dropWitness() {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TRIGGER IF EXISTS $TRIGGER ON published_endpoint_versions")
                statement.execute("DROP FUNCTION IF EXISTS $FUNCTION()")
                statement.execute("DROP SEQUENCE IF EXISTS $CHILD_FIRST")
                statement.execute("DROP SEQUENCE IF EXISTS $ROOT_FIRST")
            }
        }
    }

    private fun count(sequence: String): Int = rows("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM $sequence").single().toInt()

    // ---- wire helpers -------------------------------------------------------------------------------------------

    private fun releaseWithPlan(): Response =
        given()
            .port(port)
            .asSession(SESSION)
            .header("If-Match", endpointHash)
            .contentType(ContentType.JSON)
            .body(mapper.writeValueAsString(mapOf("tree_hash" to treeHash(), "include_dependencies" to listOf("n1"))))
            .post("/api/v1/endpoints/$endpointId/release")

    /**
     * A's plan tree hash, recomputed INDEPENDENTLY of the product (record §2.1, format `v1`): A (the API family's wire
     * name `api_endpoint`, keyed by its immutable path) at v1 DRAFT and current, pinning P v1 DRAFT (no pointer) at the
     * field path `pipeline_version`. Nodes and edges are byte-sorted; these strings are ASCII, so string order IS the
     * byte order.
     */
    private fun treeHash(): String {
        val a = "[\"api_endpoint\",\"$endpointPath\",1]"
        val p = "[\"pipeline\",\"$PIPELINE\",1]"
        val nodes =
            listOf(
                "{\"id\":$a,\"entity_uuid\":\"$endpointId\",\"status\":\"DRAFT\",\"body_hash\":\"$endpointHash\",\"current\":1}",
                "{\"id\":$p,\"entity_uuid\":\"$pipelineId\",\"status\":\"DRAFT\",\"body_hash\":\"$pipelineHash\",\"current\":null}",
            )
        val edges = listOf("{\"from\":$a,\"to\":$p,\"path\":\"pipeline_version\"}")
        val canonical =
            "{\"format\":\"v1\",\"workspace\":\"$WORKSPACE_ID\",\"root\":$a," +
                "\"nodes\":${nodes.sorted().joinToString(",", "[", "]")},\"edges\":${edges.sorted().joinToString(",", "[", "]")}}"
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "v1:" + digest.joinToString("") { "%02x".format(it) }
    }

    // ---- the cases ----------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `the fixture - API v1 DRAFT pins pipeline v1 DRAFT, nothing released`() {
        val created =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(SESSION)
                .body(
                    """
                    {"name": "$PIPELINE", "display_name": "Cascade 786", "description": "786 A42 fixture.",
                     "nodes": [{"id": "fq", "type": "CALCULATOR", "kind": "fiscal_quarter", "context_key": "run_fiscal_quarter",
                                "inputs": {"date": "${'$'}current_date", "fiscal_start": "${'$'}org_fiscal_start_date"}}]}
                    """.trimIndent(),
                ).post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
        pipelineId = created.getString("data.id")
        pipelineHash = created.getString("data.body_hash")

        endpointId =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(SESSION)
                .body("""{"path": "/cascade786/v1/probe", "pipeline": "$PIPELINE", "pipeline_version": 1}""")
                .post("/api/v1/endpoints")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.id")
        endpointPath = rows("SELECT path_pattern FROM published_endpoints WHERE id = ?::uuid", endpointId).single()
        endpointHash =
            given()
                .port(port)
                .asSession(SESSION)
                .get("/api/v1/endpoints/$endpointId/versions/1")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.body_hash")

        val state = committed()
        withClue(state) {
            statuses(state, "pipeline") shouldBe listOf("DRAFT")
            statuses(state, "api") shouldBe listOf("DRAFT")
        }
    }

    @Test
    @Order(2)
    fun `without the dependency ticked the API release is refused - a draft pin is a blocker`() {
        val before = committed()
        val response =
            given()
                .port(port)
                .asSession(SESSION)
                .header("If-Match", endpointHash)
                .post("/api/v1/endpoints/$endpointId/release")
        withClue(response.asString()) {
            response.statusCode shouldBe CONFLICT
            response.jsonPath().getString("error.code") shouldBe "endpoint.release.dependency_not_released"
        }
        committed() shouldBe before
    }

    @Test
    @Order(3)
    fun `atomicity - the API's audit row fails after both flips, and every flip, pointer and row rolls back`() {
        val before = committed()
        plantWitness()
        FailingAuditConfiguration.armed.set(true)
        try {
            val response = releaseWithPlan()
            withClue("both flips ran before the audit failure - P first (the witness fired on A's flip)") {
                count(CHILD_FIRST) shouldBe 1
            }
            withClue("committed state after the refused release equals the state before it") { committed() shouldBe before }
            withClue(response.asString()) { response.statusCode shouldBe INTERNAL_ERROR }
        } finally {
            FailingAuditConfiguration.armed.set(false)
            dropWitness()
        }
    }

    @Test
    @Order(4)
    fun `control - the plan releases the pipeline first and the API last, in one commit with one row per flip`() {
        plantWitness()
        try {
            val response = releaseWithPlan()
            withClue("A42: P was already RELEASED inside the transaction when A flipped") {
                count(CHILD_FIRST) shouldBe 1
                count(ROOT_FIRST) shouldBe 0
            }
            withClue(response.asString()) { response.statusCode shouldBe OK }
            response.jsonPath().getList<Map<String, Any?>>("data.dependencies_released").map { it["name"] } shouldBe listOf(PIPELINE)
        } finally {
            dropWitness()
        }
        val state = committed()
        withClue(state) {
            statuses(state, "pipeline") shouldBe listOf("RELEASED")
            statuses(state, "api") shouldBe listOf("RELEASED")
            // The creates audited too; the release's rows are the ones carrying a release_id — P's first, A's last.
            val released = state.getValue("audit").map { it.split(" | ") }.filter { it[2] != "null" }
            released.map { it[1] } shouldBe listOf("pipeline.version.released", "endpoint.released")
            released.map { it[2] }.toSet().size shouldBe 1
        }
    }

    // ---- #894: under an API root a cascaded template row names the API -----------------------------------------

    private lateinit var auditPipelineId: String
    private lateinit var auditEndpointId: String

    private fun send(
        path: String,
        body: String?,
        ifMatch: String? = null,
        method: String = "POST",
        session: String = SESSION,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .apply { ifMatch?.let { header("If-Match", it) } }
            .apply { body?.let { contentType(ContentType.JSON).body(it) } }
            .request(method, path)

    private fun answered(
        response: Response,
        status: Int,
    ): Response {
        withClue(response.asString()) { response.statusCode shouldBe status }
        return response
    }

    /** The audit pipeline's body: v1 one CALCULATOR node, v2 one DQL node per [AUDIT_TEMPLATES] member, in order. */
    private fun auditPipelineBody(withTemplates: Boolean): String {
        val calculator =
            mapOf(
                "id" to "fq",
                "type" to "CALCULATOR",
                "kind" to "fiscal_quarter",
                "context_key" to "run_fiscal_quarter",
                "inputs" to mapOf("date" to "\$current_date", "fiscal_start" to "\$org_fiscal_start_date"),
            )
        val dql =
            AUDIT_TEMPLATES.mapIndexed { index, template ->
                // At most one node answers the caller: the others stage to tempdb.
                buildMap<String, Any?> {
                    put("id", "n$index")
                    put("type", "DQL")
                    put("source", AUDIT_DATASOURCE)
                    put("description", "One row")
                    put("template", mapOf("id" to template, "version" to 1))
                    put("depends_on", emptyList<String>())
                    if (index < AUDIT_TEMPLATES.lastIndex) put("output", mapOf("target" to "tempdb", "table" to "step_$index"))
                }
            }
        return mapper.writeValueAsString(
            mapOf(
                "name" to AUDIT_PIPELINE,
                "display_name" to "Cascade 894",
                "description" to "894 fixture.",
                "nodes" to if (withTemplates) dql else listOf(calculator),
            ),
        )
    }

    /** One version row, read at the TABLE: `[family, key, version]`, the entity's uuid, its pointer, status and hash. */
    private data class AuditNode(
        val id: String,
        val uuid: String,
        val current: String,
        val status: String,
        val hash: String,
    )

    private fun auditNode(
        family: String,
        key: String,
        version: Int,
        sql: String,
        vararg args: String,
    ): AuditNode {
        val (uuid, current, status, hash) = rows(sql, *args).single().split(" | ")
        return AuditNode("[\"$family\",\"$key\",$version]", uuid, current, status, hash)
    }

    /** The API's closure as the oracle sees it: n0 the API v1, n1 the pipeline v2, n2/n3 the templates in pin order. */
    private fun auditClosure(): List<AuditNode> {
        val path = rows("SELECT path_pattern FROM published_endpoints WHERE id = ?::uuid", auditEndpointId).single()
        val api =
            auditNode(
                "api_endpoint",
                path,
                API_VERSION,
                "SELECT e.id, e.current_version, v.status, v.body_hash FROM published_endpoints e" +
                    " JOIN published_endpoint_versions v ON v.endpoint_id = e.id WHERE e.id = ?::uuid AND v.version = $API_VERSION",
                auditEndpointId,
            )
        val pipeline =
            auditNode(
                "pipeline",
                AUDIT_PIPELINE,
                PIPELINE_VERSION,
                "SELECT p.id, p.current_version, v.status, v.body_hash FROM pipelines p" +
                    " JOIN pipeline_versions v ON v.pipeline_id = p.id WHERE p.id = ?::uuid AND v.version = $PIPELINE_VERSION",
                auditPipelineId,
            )
        val templates =
            AUDIT_TEMPLATES.map { name ->
                auditNode(
                    "template",
                    name,
                    1,
                    "SELECT t.id, t.current_version, v.status, v.body_hash FROM templates t" +
                        " JOIN template_versions v ON v.template_id = t.id WHERE t.workspace_id = ?::uuid AND t.name = ? AND v.version = 1",
                    WORKSPACE_ID,
                    name,
                )
            }
        return listOf(api, pipeline) + templates
    }

    /** The §2.1 `v1` tree hash of [closure], recomputed independently of the product (the [treeHash] mold). */
    private fun auditTreeHash(closure: List<AuditNode>): String {
        val (api, pipeline) = closure
        val nodes =
            closure.map {
                "{\"id\":${it.id},\"entity_uuid\":\"${it.uuid}\",\"status\":\"${it.status}\"," +
                    "\"body_hash\":\"${it.hash}\",\"current\":${it.current}}"
            }
        val edges =
            listOf("{\"from\":${api.id},\"to\":${pipeline.id},\"path\":\"pipeline_version\"}") +
                closure.drop(2).mapIndexed { index, template ->
                    "{\"from\":${pipeline.id},\"to\":${template.id},\"path\":\"nodes[$index].template\"}"
                }
        val canonical =
            "{\"format\":\"v1\",\"workspace\":\"$WORKSPACE_ID\",\"root\":${api.id}," +
                "\"nodes\":${nodes.sorted().joinToString(",", "[", "]")},\"edges\":${edges.sorted().joinToString(",", "[", "]")}}"
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "v1:" + digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    @Order(5)
    fun `894 fixture - API v1 DRAFT pins pipeline v2 DRAFT, which pins two DRAFT templates`() {
        DriverManager.getConnection(H2_JDBC_URL, "sa", "sa").use { it.createStatement().execute("SELECT 1") }
        answered(
            send(
                "/api/v1/datasources",
                """{"name": "$AUDIT_DATASOURCE", "display_name": "Cascade 894", "dialect": "H2",
                   "jdbc_url": "$H2_JDBC_URL", "username": "sa", "password": "sa"}""",
                session = SUPER_SESSION,
            ),
            CREATED,
        )
        AUDIT_TEMPLATES.forEach { name ->
            val template =
                mapOf(
                    "id" to name,
                    "dialect" to "H2",
                    "display_name" to "Cascade 894",
                    "description" to "894 fixture.",
                    "imports" to emptyList<Any>(),
                    "body" to "SELECT 1 AS n",
                )
            answered(send("/api/v1/templates", mapper.writeValueAsString(template)), CREATED)
        }
        // v1 pins nothing and is released at once, so the API pins the pipeline's v2: the two version numbers differ.
        val v1 = answered(send("/api/v1/pipelines", auditPipelineBody(withTemplates = false)), CREATED).jsonPath()
        auditPipelineId = v1.getString("data.id")
        answered(send("/api/v1/pipelines/$auditPipelineId/release", null, v1.getString("data.body_hash")), OK)
        val v1Hash = rows("SELECT body_hash FROM pipeline_versions WHERE pipeline_id = ?::uuid AND version = 1", auditPipelineId).single()
        answered(send("/api/v1/pipelines/$auditPipelineId", auditPipelineBody(withTemplates = true), v1Hash, "PUT"), OK)
        auditEndpointId =
            answered(
                send(
                    "/api/v1/endpoints",
                    """{"path": "/cascade894/v1/probe", "pipeline": "$AUDIT_PIPELINE", "pipeline_version": $PIPELINE_VERSION}""",
                ),
                CREATED,
            ).jsonPath().getString("data.id")

        val closure = auditClosure()
        withClue(closure) {
            closure.map { it.status } shouldBe listOf("DRAFT", "DRAFT", "DRAFT", "DRAFT")
            closure[1].current shouldBe "1"
            (closure[0].uuid == closure[1].uuid) shouldBe false
        }
    }

    @Test
    @Order(6)
    fun `894 - under an API root each cascaded template row names the API and the API's version, never a pipeline`() {
        val watermark = rows("SELECT coalesce(max(id), 0) FROM audit_log").single()
        val closure = auditClosure()
        val plan = mapOf("tree_hash" to auditTreeHash(closure), "include_dependencies" to listOf("n1", "n2", "n3"))
        answered(send("/api/v1/endpoints/$auditEndpointId/release", mapper.writeValueAsString(plan), closure[0].hash), OK)

        val written =
            rows(
                "SELECT event, details_json::text FROM audit_log WHERE details_json ->> 'workspace_id' = ? AND id > ?::bigint ORDER BY id",
                WORKSPACE_ID,
                watermark,
            ).map { it.split(" | ", limit = 2) }
        val templateRows =
            written.filter { it[0] == "template.version.released" }.map { mapper.readValue(it[1], DETAILS) }
        withClue("exactly the two cascaded template rows, by template id, before any row is read: $written") {
            templateRows.map { it["template_id"] }.sortedBy { it.toString() } shouldBe AUDIT_TEMPLATES.sorted()
        }
        val apiRoot = mapOf("family" to "api_endpoint", "id" to auditEndpointId, "version" to API_VERSION)
        templateRows.forEach { row ->
            withClue(row) {
                row["cascade_from_api_endpoint_id"] shouldBe auditEndpointId
                row.containsKey("cascade_from_pipeline_id") shouldBe false
                row["cascade_from_version"] shouldBe API_VERSION
                row["cascade_root"] shouldBe apiRoot
                row.filterKeys { it != "release_id" } shouldBe
                    mapOf(
                        "template_id" to row["template_id"],
                        "version" to 1,
                        "via" to "session",
                        "cascade_from_api_endpoint_id" to auditEndpointId,
                        "cascade_from_version" to API_VERSION,
                        "scope" to "version",
                        "cascade_root" to apiRoot,
                        "workspace_id" to WORKSPACE_ID,
                    )
            }
        }
        println("894 API-root cascaded template row: ${written.first { it[0] == "template.version.released" }[1]}")
    }

    @AfterAll
    fun dropAndProve() {
        dropWitness()
        val leftovers =
            rows(
                "SELECT (SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'dp786%')" +
                    " + (SELECT count(*) FROM pg_proc WHERE proname LIKE 'dp786%')" +
                    " + (SELECT count(*) FROM pg_class WHERE relname LIKE 'dp786%')",
            ).single()
        println("pg_trigger/pg_proc/pg_class dp786 objects after the class = $leftovers")
        oidc.close()
        leftovers shouldBe "0"
    }

    /**
     * The release audit sink of this context: the PRODUCTION strict sink (bean `releaseAuditSink`), wrapped in a JDK
     * proxy that throws when armed and the API's own release row is written, and otherwise delegates unchanged. Wrapped
     * by name and by reflection because this module compiles against the app only (PermissionSeamE2eTest's way);
     * imported explicitly because a nested `@TestConfiguration` is not picked up with `@SpringBootTest(classes = …)`.
     */
    @TestConfiguration
    @Suppress("UtilityClassWithPublicConstructor")
    class FailingAuditConfiguration {
        companion object {
            val armed = AtomicBoolean(false)

            @JvmStatic
            @Bean
            fun failingReleaseAuditSink(): BeanPostProcessor =
                object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(
                        bean: Any,
                        beanName: String,
                    ): Any =
                        if (beanName != SINK_BEAN) {
                            bean
                        } else {
                            Proxy.newProxyInstance(bean.javaClass.classLoader, bean.javaClass.interfaces) { _, method, args ->
                                val event = args?.firstOrNull()?.let { row -> row.javaClass.getMethod("getEvent").invoke(row) }
                                check(!(armed.get() && method.name == "record" && event == "endpoint.released")) {
                                    "planted by #786: the API's audit row is refused"
                                }
                                try {
                                    method.invoke(bean, *(args ?: emptyArray()))
                                } catch (e: InvocationTargetException) {
                                    throw e.targetException
                                }
                            }
                        }
                }

            private const val SINK_BEAN = "releaseAuditSink"
        }
    }

    companion object {
        private const val PIPELINE = "test/cascade_786"
        private const val WORKSPACE = "cascade-786"

        private const val FUNCTION = "dp786_api_flip_witness"
        private const val TRIGGER = "dp786_api_flip_witness_trigger"
        private const val CHILD_FIRST = "dp786_child_first"
        private const val ROOT_FIRST = "dp786_root_first"

        private const val AUDIT_PIPELINE = "test/cascade_894"
        private const val AUDIT_DATASOURCE = "h2-cascade-894"
        private const val H2_JDBC_URL = "jdbc:h2:mem:cascade894;DB_CLOSE_DELAY=-1"
        private val AUDIT_TEMPLATES = listOf("test/cascade_894/t_a.sql", "test/cascade_894/t_b.sql")
        private const val API_VERSION = 1
        private val DETAILS = object : TypeReference<Map<String, Any?>>() {}
        private const val PIPELINE_VERSION = 2

        private const val OK = 200
        private const val CREATED = 201
        private const val CONFLICT = 409
        private const val INTERNAL_ERROR = 500
        private const val STATUS_COLUMN = 2

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-786-cascade@datapipelines.test", WORKSPACE)

        /** #894: member datasource management is off on this server, so a super admin member registers the H2 source. */
        private val SUPER_ID: String = UUID.randomUUID().toString()
        private val SUPER_SESSION get() = E2eSession.jwt(JWT_SECRET, SUPER_ID, "e2e-894-super@datapipelines.test", WORKSPACE)

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
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Cascade 786')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-786-cascade@datapipelines.test', 'E2E 786', 'test', 'e2e-786-cascade-sub', TRUE, FALSE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                            "VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$SUPER_ID', 'e2e-894-super@datapipelines.test', 'E2E 894', 'test', 'e2e-894-super-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                            "VALUES ('$WORKSPACE_ID', '$SUPER_ID', 'workspace_admin')",
                    )
                }
            }
        }
    }
}
