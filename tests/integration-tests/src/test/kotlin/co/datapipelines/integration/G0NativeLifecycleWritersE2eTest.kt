package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #841 (G0) R3 of the landing review — every remaining guarded LIFECYCLE writer, at the wire over the real application
 * and the shared test Postgres: for the four artifact families that share one REST shape (pipelines, visualizations,
 * dashboards, parameter sets) the draft edit, release, current switch, version discard, version restore, draft purge,
 * version purge and entity purge; for templates the release, the REST draft purge, the version purge and the entity
 * purge; and the visualization evidence START (`TestRunRepository.guardWorkspaceOf`). Each takes the workspace writer
 * guard first (record §4.3); the mechanical inventory of guard call sites is in the #841 r2 handback.
 *
 * One shape per row, `G0WriterContentionE2eTest`'s: a raw connection holds the workspace row (`FOR NO KEY UPDATE`, as a
 * release in flight holds it); the writer is observed WAITING on it (transitive `pg_blocking_pids`, fresh connection),
 * exhausts the production bound (5 s) and answers its family's busy code with details exactly `{lock_wait_seconds}`;
 * the persisted versions (and pointer, and test runs) are unchanged; once the row is free the same request is the
 * successful control. Where the control audits, the audit is read after it: the sink is FIFO per key (one session
 * here), so the control's row being visible means a row the refused call had written would be too — exactly one.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class G0NativeLifecycleWritersE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()
    private val pool = Executors.newFixedThreadPool(2)
    private var rpcId = 0
    private val ids = mutableMapOf<Family, String>()

    /** The template whose release, draft purge and version purge are proven below. */
    private val lifecycleTemplate = G0Lifecycle.LIFECYCLE_TEMPLATE

    /** The four families with one REST lifecycle shape; tables, foreign key and audit events derive from [audit]. */
    enum class Family(
        val path: String,
        val audit: String,
        busyFamily: String,
    ) {
        PIPELINE("pipelines", "pipeline", "pipeline"),
        VISUALIZATION("visualizations", "visualization", "visualization"),
        DASHBOARD("dashboards", "dashboard", "dashboard"),
        PARAMETER_SET("parameter-sets", "parameter_set", "parameter"),
        ;

        val table = "${audit}s"
        val versions = "${audit}_versions"
        val fk = "${audit}_id"
        val busy = "$busyFamily.release.busy"
    }

    // ---- the holder, the waiter probe and the busy assertion --------------------------------------------------

    private fun holdWorkspace(): Pair<Connection, Int> {
        val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        connection.autoCommit = false
        connection.prepareStatement("SELECT id FROM workspaces WHERE id = ?::uuid FOR NO KEY UPDATE").use { ps ->
            ps.setString(1, WORKSPACE_ID)
            ps.execute()
        }
        val pid =
            connection.createStatement().use { s ->
                s.executeQuery("SELECT pg_backend_pid()").use {
                    it.next()
                    it.getInt(1)
                }
            }
        return connection to pid
    }

    private fun jdbc() = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))

    /** [writer] against a held workspace row, observed waiting (RED if it answers first), left to exhaust the bound. */
    private fun exhausts(writer: () -> Response): Response {
        val (connection, pid) = holdWorkspace()
        try {
            val answer = CompletableFuture.supplyAsync({ writer() }, pool)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PROBE_SECONDS)
            while ((jdbc().queryForObject(WAITERS_SQL, Int::class.java, pid) ?: 0) < 1) {
                withClue("the writer answered without waiting on the held row: ${if (answer.isDone) answer.join().asString() else ""}") {
                    answer.isDone shouldBe false
                }
                withClue("no waiter on the holder within the probe window") { (System.nanoTime() < deadline) shouldBe true }
                Thread.sleep(PROBE_INTERVAL_MS)
            }
            return answer.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            connection.rollback()
            connection.close()
        }
    }

    private fun assertBusy(
        response: Response,
        code: String,
    ) {
        withClue(response.asString()) { response.statusCode shouldBe 409 }
        val error = mapper.readTree(response.asString())["error"]
        error["code"].asText() shouldBe code
        error["message"].asText() shouldBe BUSY_MESSAGE
        error["details"] shouldBe mapper.readTree("""{"lock_wait_seconds": $DEFAULT_LOCK_WAIT_SECONDS}""")
    }

    private fun control(
        response: Response,
        status: Int,
    ) {
        withClue("the successful control: ${response.asString()}") { response.statusCode shouldBe status }
    }

    // ---- wire and state helpers -------------------------------------------------------------------------------

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): JsonNode {
        val id = synchronized(this) { ++rpcId }
        val body =
            mapOf("jsonrpc" to "2.0", "id" to id, "method" to "tools/call", "params" to mapOf("name" to name, "arguments" to arguments))
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val result = mapper.readTree(response.body())["result"]
        val payload = mapper.readTree(result["content"][0]["text"].asText())
        withClue("$name must succeed: $payload") { result.path("isError").asBoolean(false) shouldBe false }
        return payload
    }

    private fun rest(): RequestSpecification = given().port(port).asSession(SESSION)

    private fun json(body: String): RequestSpecification = rest().contentType(ContentType.JSON).body(body)

    private fun create(
        family: Family,
        name: String,
    ): String {
        val created = json(G0Lifecycle.document(mapper, family, name, "v1")).post("/api/v1/${family.path}")
        withClue(created.asString()) { created.statusCode shouldBe 201 }
        return created.jsonPath().getString("data.id")
    }

    private fun write(
        family: Family,
        revision: String,
    ): Response =
        json(G0Lifecycle.document(mapper, family, nameOf(family), revision))
            .header("If-Match", workingHash(family))
            .put(path(family))

    private fun release(family: Family): Response = rest().header("If-Match", draftHash(family)).post("${path(family)}/release")

    private fun path(family: Family) = "/api/v1/${family.path}/${ids.getValue(family)}"

    private fun nameOf(family: Family) = "test/g0l/${family.table}"

    /** How many [family] artifacts carry [name] (names are unique forever, so 0 or 1). */
    private fun named(
        family: Family,
        name: String,
    ): Int = jdbc().queryForObject("SELECT count(*) FROM ${family.table} WHERE name = ?", Int::class.java, name) ?: 0

    /** Every stored version, `version:STATUS` ascending, then `@current` — the persisted state a refusal must not move. */
    private fun state(
        family: Family,
        id: String = ids.getValue(family),
    ): String =
        jdbc().queryForObject(
            "SELECT coalesce(string_agg(v.version || ':' || v.status, ',' ORDER BY v.version), '') || '@' ||" +
                " coalesce((SELECT a.current_version::text FROM ${family.table} a WHERE a.id = ?::uuid), '-')" +
                " FROM ${family.versions} v WHERE v.${family.fk} = ?::uuid",
            String::class.java,
            id,
            id,
        ) ?: ""

    private fun draftHash(
        family: Family,
        id: String = ids.getValue(family),
    ): String =
        jdbc().queryForObject(
            "SELECT body_hash FROM ${family.versions} WHERE ${family.fk} = ?::uuid AND status = 'DRAFT'",
            String::class.java,
            id,
        ) ?: error("no draft")

    private fun draftVersion(family: Family): Int =
        jdbc().queryForObject(
            "SELECT version FROM ${family.versions} WHERE ${family.fk} = ?::uuid AND status = 'DRAFT'",
            Int::class.java,
            ids.getValue(family),
        ) ?: error("no draft")

    /** The working version's hash: the draft when one exists, else the current release (versioning §7.1). */
    private fun workingHash(family: Family): String =
        jdbc().queryForObject(
            "SELECT v.body_hash FROM ${family.versions} v JOIN ${family.table} a ON a.id = v.${family.fk} WHERE a.id = ?::uuid" +
                " ORDER BY (v.status = 'DRAFT') DESC, (v.version = a.current_version) DESC LIMIT 1",
            String::class.java,
            ids.getValue(family),
        ) ?: error("no working version")

    /** The visualization release's evidence: a GREEN run for the current draft (start, then results). */
    private fun evidence(id: String) {
        val started = rest().post("/api/v1/visualizations/$id/tests/sessions")
        withClue(started.asString()) { started.statusCode shouldBe 201 }
        val session = started.jsonPath().getString("data.session_id")
        val submitted = json(RESULTS).post("/api/v1/visualizations/$id/tests/sessions/$session/results")
        withClue(submitted.asString()) { submitted.jsonPath().getString("data.status") shouldBe "GREEN" }
    }

    /** Audit rows of [event] naming [id] under [key], polled until the control's row is visible (FIFO per key). */
    private fun auditedOnce(
        event: String,
        key: String,
        id: String,
        expected: Int = 1,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AUDIT_WAIT_SECONDS)
        while (auditRows(event, key, id) < expected) {
            withClue("the control's audit row $event never appeared") { (System.nanoTime() < deadline) shouldBe true }
            Thread.sleep(PROBE_INTERVAL_MS)
        }
        withClue("the refused call wrote no $event row") { auditRows(event, key, id) shouldBe expected }
    }

    private fun auditRows(
        event: String,
        key: String,
        id: String,
    ): Int =
        jdbc().queryForObject(
            "SELECT count(*) FROM audit_log WHERE event = ? AND user_id = ?::uuid AND details_json ->> ? = ?",
            Int::class.java,
            event,
            ADMIN_ID,
            key,
            id,
        ) ?: 0

    private fun audited(
        family: Family,
        event: String,
        expected: Int = 1,
    ) = auditedOnce("${family.audit}.$event", "${family.audit}_id", ids.getValue(family), expected)

    // ---- the fixture ------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `the fixture - a released template, pipeline and visualization to pin, and one v1 draft per family`() {
        val datasource =
            json(
                """
                {"name": "${G0Lifecycle.DATASOURCE}", "display_name": "G0 lifecycle writers", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).post("/api/v1/datasources")
        withClue(datasource.asString()) { datasource.statusCode shouldBe 201 }
        callTool("templates_create", G0Lifecycle.templateArguments(G0Lifecycle.TEMPLATE))
        val source = create(Family.PIPELINE, G0Lifecycle.SOURCE)
        val released =
            rest()
                .header("If-Match", draftHash(Family.PIPELINE, source))
                .queryParam("release_pinned_templates", "true")
                .post("/api/v1/pipelines/$source/release")
        withClue(released.asString()) { released.statusCode shouldBe 200 }
        val chart = create(Family.VISUALIZATION, G0Lifecycle.SOURCE_CHART)
        evidence(chart)
        val chartReleased = rest().header("If-Match", draftHash(Family.VISUALIZATION, chart)).post("/api/v1/visualizations/$chart/release")
        withClue(chartReleased.asString()) { chartReleased.statusCode shouldBe 200 }

        Family.entries.forEach { family ->
            ids[family] = create(family, nameOf(family))
            withClue(family) { state(family) shouldBe "1:DRAFT@-" }
        }
    }

    // ---- the family lifecycle, verb by verb: busy and unchanged, then the control ---------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(Family::class)
    @Order(2)
    fun `draft edit - busy, the draft keeps its hash - then the edit lands`(family: Family) {
        val before = draftHash(family)
        assertBusy(exhausts { write(family, "edited") }, family.busy)
        draftHash(family) shouldBe before
        state(family) shouldBe "1:DRAFT@-"

        control(write(family, "edited"), 200)
        draftHash(family) shouldNotBe before
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Family::class)
    @Order(3)
    fun `release - busy, nothing released and unaudited - then it releases`(family: Family) {
        if (family == Family.VISUALIZATION) evidence(ids.getValue(family))
        assertBusy(exhausts { release(family) }, family.busy)
        state(family) shouldBe "1:DRAFT@-"

        control(release(family), 200)
        state(family) shouldBe "1:RELEASED@1"
        audited(family, "version.released")
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Family::class)
    @Order(4)
    fun `current switch - busy, the pointer stays - then it switches`(family: Family) {
        control(write(family, "v2"), 200)
        if (family == Family.VISUALIZATION) evidence(ids.getValue(family))
        control(release(family), 200)
        state(family) shouldBe "1:RELEASED,2:RELEASED@2"
        val switch = { json("""{"version": 1}""").post("${path(family)}/current") }
        assertBusy(exhausts(switch), family.busy)
        state(family) shouldBe "1:RELEASED,2:RELEASED@2"

        control(switch(), 200)
        state(family) shouldBe "1:RELEASED,2:RELEASED@1"
        audited(family, "current_switched")
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Family::class)
    @Order(5)
    fun `version discard - busy, the version stays released - then it discards`(family: Family) {
        val discard = { rest().post("${path(family)}/versions/2/discard") }
        assertBusy(exhausts(discard), family.busy)
        state(family) shouldBe "1:RELEASED,2:RELEASED@1"

        control(discard(), 200)
        state(family) shouldBe "1:RELEASED,2:DISCARDED@1"
        audited(family, "version.discarded")
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Family::class)
    @Order(6)
    fun `version restore - busy, the version stays discarded - then it restores`(family: Family) {
        val restore = { rest().post("${path(family)}/versions/2/restore") }
        assertBusy(exhausts(restore), family.busy)
        state(family) shouldBe "1:RELEASED,2:DISCARDED@1"

        control(restore(), 200)
        withClue("a restore above the current version moves the pointer (versioning.md §5, restore(x))") {
            state(family) shouldBe "1:RELEASED,2:RELEASED@2"
        }
        audited(family, "version.restored")
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Family::class)
    @Order(7)
    fun `draft purge - busy, the draft stays - then it is purged`(family: Family) {
        control(write(family, "v3"), 200)
        val withDraft = state(family)
        val purge = { rest().header("If-Match", draftHash(family)).post("${path(family)}/draft/discard") }
        assertBusy(exhausts(purge), family.busy)
        state(family) shouldBe withDraft

        control(purge(), 204)
        state(family) shouldBe "1:RELEASED,2:RELEASED@2"
        audited(family, "version.purged")
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Family::class)
    @Order(8)
    fun `version purge - busy, the draft version stays - then it is purged`(family: Family) {
        control(write(family, "v3b"), 200)
        val withDraft = state(family)
        val purge = { rest().delete("${path(family)}/versions/${draftVersion(family)}") }
        assertBusy(exhausts(purge), family.busy)
        state(family) shouldBe withDraft

        control(purge(), 204)
        state(family) shouldBe "1:RELEASED,2:RELEASED@2"
        audited(family, "version.purged", expected = 2)
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Family::class)
    @Order(9)
    fun `entity purge - busy, the sole-draft artifact stays - then it is purged`(family: Family) {
        val sole = create(family, "${nameOf(family)}_sole")
        val purge = { rest().delete("/api/v1/${family.path}/$sole") }
        assertBusy(exhausts(purge), family.busy)
        state(family, sole) shouldBe "1:DRAFT@-"

        // The pipeline entity purge answers 200 with its purge report; the artifact families answer 204.
        control(purge(), if (family == Family.PIPELINE) 200 else 204)
        state(family, sole) shouldBe "@-"
        auditedOnce("${family.audit}.purged", "${family.audit}_id", sole)
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Family::class)
    @Order(15)
    fun `draft create over REST - busy, nothing created - then it is created`(family: Family) {
        val name = "${nameOf(family)}_created"
        val post = { json(G0Lifecycle.document(mapper, family, name, "v1")).post("/api/v1/${family.path}") }
        assertBusy(exhausts(post), family.busy)
        named(family, name) shouldBe 0

        control(post(), 201)
        named(family, name) shouldBe 1
    }

    // ---- the visualization evidence start -------------------------------------------------------------------------

    @Test
    @Order(10)
    fun `visualization evidence start - visualization release busy, no run started - then a session starts`() {
        val id = create(Family.VISUALIZATION, "test/g0l/evidence")
        val start = { rest().post("/api/v1/visualizations/$id/tests/sessions") }
        assertBusy(exhausts(start), Family.VISUALIZATION.busy)
        runs(id) shouldBe 0

        control(start(), 201)
        runs(id) shouldBe 1
    }

    private fun runs(visualizationId: String): Int =
        jdbc().queryForObject(
            "SELECT count(*) FROM visualization_test_runs WHERE visualization_id = ?::uuid",
            Int::class.java,
            visualizationId,
        ) ?: 0

    // ---- the template verbs not proven elsewhere: release, REST draft purge, version purge, entity purge ----------

    private fun templates(id: String): String =
        jdbc().queryForObject(
            "SELECT coalesce(string_agg(v.version || ':' || v.status, ',' ORDER BY v.version), '') FROM template_versions v" +
                " JOIN templates t ON t.id = v.template_id WHERE t.workspace_id = ?::uuid AND t.name = ?",
            String::class.java,
            WORKSPACE_ID,
            id,
        ) ?: ""

    private fun byName(id: String): RequestSpecification = json(mapper.writeValueAsString(mapOf("name" to id)))

    private fun templateHash(id: String): String =
        rest()
            .queryParam("name", id)
            .get("/api/v1/templates")
            .jsonPath()
            .getString("data.body_hash")

    /** Opens a draft on released template [id]: a new body over the working hash, through the guarded MCP update. */
    private fun openTemplateDraft(id: String) {
        val revision = synchronized(this) { ++rpcId }
        val arguments = G0Lifecycle.templateArguments(id) + mapOf("expected_hash" to templateHash(id), "body" to "SELECT $revision AS n")
        callTool("templates_update", arguments)
    }

    @Test
    @Order(11)
    fun `template release - template release busy, nothing released - then it releases`() {
        callTool("templates_create", G0Lifecycle.templateArguments(lifecycleTemplate))
        val release = { byName(lifecycleTemplate).header("If-Match", templateHash(lifecycleTemplate)).post("/api/v1/templates/release") }
        assertBusy(exhausts(release), TEMPLATE_BUSY)
        templates(lifecycleTemplate) shouldBe "1:DRAFT"

        control(release(), 200)
        templates(lifecycleTemplate) shouldBe "1:RELEASED"
        auditedOnce("template.version.released", "template_id", lifecycleTemplate)
    }

    @Test
    @Order(12)
    fun `template draft purge over REST - template release busy, the draft stays - then it is purged`() {
        openTemplateDraft(lifecycleTemplate)
        templates(lifecycleTemplate) shouldBe "1:RELEASED,2:DRAFT"
        val purge = {
            byName(
                lifecycleTemplate,
            ).header("If-Match", templateHash(lifecycleTemplate)).post("/api/v1/templates/draft/discard")
        }
        assertBusy(exhausts(purge), TEMPLATE_BUSY)
        templates(lifecycleTemplate) shouldBe "1:RELEASED,2:DRAFT"

        control(purge(), 204)
        templates(lifecycleTemplate) shouldBe "1:RELEASED"
        auditedOnce("template.version.purged", "template_id", lifecycleTemplate)
    }

    @Test
    @Order(13)
    fun `template version purge - template release busy, the draft version stays - then it is purged`() {
        openTemplateDraft(lifecycleTemplate)
        val withDraft = templates(lifecycleTemplate)
        val version = withDraft.substringAfterLast(',').substringBefore(':')
        val purge = { rest().queryParam("name", lifecycleTemplate).queryParam("version", version).delete("/api/v1/templates/version") }
        assertBusy(exhausts(purge), TEMPLATE_BUSY)
        templates(lifecycleTemplate) shouldBe withDraft

        control(purge(), 204)
        templates(lifecycleTemplate) shouldBe "1:RELEASED"
        auditedOnce("template.version.purged", "template_id", lifecycleTemplate, expected = 2)
    }

    @Test
    @Order(14)
    fun `template entity purge - template release busy, the sole-draft template stays - then it is purged`() {
        callTool("templates_create", G0Lifecycle.templateArguments(G0Lifecycle.SOLE_TEMPLATE))
        val purge = { rest().queryParam("name", G0Lifecycle.SOLE_TEMPLATE).delete("/api/v1/templates") }
        assertBusy(exhausts(purge), TEMPLATE_BUSY)
        templates(G0Lifecycle.SOLE_TEMPLATE) shouldBe "1:DRAFT"

        control(purge(), 204)
        templates(G0Lifecycle.SOLE_TEMPLATE) shouldBe ""
        auditedOnce("template.purged", "template_id", G0Lifecycle.SOLE_TEMPLATE)
    }

    @AfterAll
    fun shutdown() {
        pool.shutdownNow()
        oidc.close()
    }

    companion object {
        private const val H2_JDBC_URL = "jdbc:h2:mem:g0l841;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val WORKSPACE = "g0l-841"
        private const val TEMPLATE_BUSY = "template.release.busy"
        private const val RESULTS = """{"cases":[{"name":"one month","verdict":"green"}],"environment":{"browser":"e2e","theme":"dark"}}"""
        private const val BUSY_MESSAGE = "Another change in this workspace is in progress. Nothing was written; try again in a moment."

        /** The production default of `datapipelines.release.dependencies.lock-wait-seconds`. */
        private const val DEFAULT_LOCK_WAIT_SECONDS = 5

        /** The probe gives up before the writer's own lock wait would, so a missing wait is RED, never a busy. */
        private const val WAIT_PROBE_SECONDS = 4L
        private const val PROBE_INTERVAL_MS = 20L
        private const val WRITER_TIMEOUT_SECONDS = 30L
        private const val AUDIT_WAIT_SECONDS = 15L
        private val WAITERS_SQL =
            """
            WITH RECURSIVE waiting(pid) AS (
                SELECT a.pid FROM pg_stat_activity a WHERE ? = ANY(pg_blocking_pids(a.pid))
                UNION
                SELECT a.pid FROM pg_stat_activity a JOIN waiting w ON w.pid = ANY(pg_blocking_pids(a.pid))
            )
            SELECT count(*) FROM waiting
            """.trimIndent()

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val KEY = E2eAuth.generateKey("e2e-841-g0l-key")
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-841-g0l@datapipelines.test", WORKSPACE)

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
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'G0 lifecycle 841')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-841-g0l@datapipelines.test', 'E2E 841 lifecycle', 'test', 'e2e-841-g0l-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES" +
                            " ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${KEY.id.lowercase()}@keys.invalid', '${KEY.name}', 'key', '${KEY.id}'," +
                            " TRUE, FALSE, 'service')",
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
